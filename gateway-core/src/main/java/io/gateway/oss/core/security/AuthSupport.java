package io.gateway.oss.core.security;

import io.gateway.oss.core.config.AuthRegistrationConfig;
import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.UserConfig;
import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.error.GatewayException;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auth 相关共享辅助能力。
 */
@Component
public class AuthSupport {

    private static final Logger log = LoggerFactory.getLogger(AuthSupport.class);

    private final JwtService jwtService;
    private final GatewayProperties properties;
    private final UserAccountService userAccountService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final boolean allowPlaintextPassword;

    public AuthSupport(JwtService jwtService,
                       GatewayProperties properties,
                       UserAccountService userAccountService,
                       BCryptPasswordEncoder passwordEncoder,
                       @Value("${security.password.allow-plaintext:false}") boolean allowPlaintextPassword) {
        this.jwtService = jwtService;
        this.properties = properties;
        this.userAccountService = userAccountService;
        this.passwordEncoder = passwordEncoder;
        this.allowPlaintextPassword = allowPlaintextPassword;
    }

    public void requireAuthEnabled() {
        if (!properties.getAuth().isEnabled()) {
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "auth_disabled", "JWT authentication is not enabled");
        }
    }

    /**
     * 解析并校验 access token，保持原有冻结/失效/删除用户语义不变。
     */
    public Claims parseAccessClaims(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Missing or invalid Authorization header");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        Claims claims = jwtService.parseToken(token);
        if (jwtService.isRefreshToken(claims)) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Refresh token cannot be used for this endpoint");
        }
        if (properties.getAuth().isEnabled()) {
            String username = jwtService.extractUsername(claims);
            int tokenVersion = jwtService.extractTokenVersion(claims);
            UserAccount account = userAccountService.findByUsernameSync(username);
            if (account == null && !userAccountService.isDeletedUserWithOldToken(username, tokenVersion)) {
                // Cache miss — fall back to persistent store
                account = userAccountService.findByUsername(username).block();
            }
            if (account != null) {
                if (account.frozen()) {
                    throw new GatewayException(HttpStatus.FORBIDDEN, "account_frozen", "Account is frozen");
                }
                if (account.tokenVersion() != tokenVersion) {
                    throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Token has been invalidated");
                }
            } else if (userAccountService.isDeletedUserWithOldToken(username, tokenVersion)) {
                throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Account has been deleted");
            }
        }
        return claims;
    }

    public TokenIdentity resolveIdentity(Claims claims) {
        String subject = jwtService.extractUsername(claims);
        String tokenClientId = jwtService.extractClientId(claims);
        String roleFromToken = jwtService.extractRole(claims);
        if (properties.getAuth().getUsers().containsKey(subject)) {
            UserConfig user = properties.getAuth().getUsers().get(subject);
            String role = user != null && user.getRole() != null && !user.getRole().isBlank() ? user.getRole() : roleFromToken;
            String clientId = tokenClientId != null && !tokenClientId.isBlank()
                    ? tokenClientId
                    : user != null && user.getClientId() != null ? user.getClientId() : subject;
            return new TokenIdentity(subject, role != null ? role : "user", clientId);
        }
        List<Map.Entry<String, UserConfig>> matchedUsers = properties.getAuth().getUsers().entrySet().stream()
                .filter(entry -> {
                    UserConfig user = entry.getValue();
                    String clientId = user.getClientId() != null ? user.getClientId() : entry.getKey();
                    return clientId.equals(subject);
                })
                .toList();

        if (matchedUsers.size() == 1) {
            Map.Entry<String, UserConfig> matched = matchedUsers.get(0);
            String username = matched.getKey();
            UserConfig user = matched.getValue();
            String clientId = tokenClientId != null && !tokenClientId.isBlank()
                    ? tokenClientId
                    : user.getClientId() != null ? user.getClientId() : username;
            String role = user.getRole() != null && !user.getRole().isBlank() ? user.getRole() : roleFromToken;
            return new TokenIdentity(username, role != null ? role : "user", clientId);
        }

        String role = roleFromToken != null ? roleFromToken : "user";
        return new TokenIdentity(subject, role, subject);
    }

    /**
     * 解析用户的 allowed scopes。restricted 模式下，动态注册用户返回 bootstrap policy 的 allowedModels。
     */
    public List<String> resolveScopes(String clientId) {
        ClientConfig clientConfig = resolveEffectiveClientConfig(clientId);
        if (clientConfig != null) {
            return List.copyOf(clientConfig.getAllowedModels());
        }
        return List.of();
    }

    public ClientConfig resolveEffectiveClientConfig(String clientId) {
        ClientConfig clientConfig = properties.getClients().get(clientId);
        if (clientConfig != null) {
            return clientConfig;
        }
        if (properties.getAuth().isRegistrationRestricted() && userAccountService.findByUsernameSync(clientId) != null) {
            return properties.getAuth().getRegistration().toClientConfig();
        }
        return null;
    }

    public UserAccount.UserLimits toUserLimits(AuthRegistrationConfig registration) {
        if (registration == null || registration.getLimits() == null) {
            return UserAccount.UserLimits.highDefaults();
        }
        return new UserAccount.UserLimits(
                registration.getLimits().getDailyTokens(),
                registration.getLimits().getMonthlyTokens(),
                registration.getLimits().getTokensPerMinute(),
                registration.getLimits().getMaxTokens(),
                registration.getLimits().getDailyCost(),
                registration.getLimits().getMonthlyCost()
        );
    }

    public Set<String> copyAllowedModels(AuthRegistrationConfig registration) {
        if (registration == null || registration.getAllowedModels() == null) {
            return Set.of();
        }
        return Set.copyOf(registration.getAllowedModels());
    }

    public UserProfileController.MeQuotaResponse buildQuota(TokenIdentity identity, UserAccount account) {
        ClientConfig clientConfig = resolveEffectiveClientConfig(identity.clientId());
        if (clientConfig != null && clientConfig.getLimits() != null) {
            return new UserProfileController.MeQuotaResponse(
                    0L,
                    clientConfig.getLimits().getDailyTokens(),
                    BigDecimal.ZERO,
                    clientConfig.getLimits().getDailyCost(),
                    0L,
                    clientConfig.getLimits().getMonthlyTokens(),
                    BigDecimal.ZERO,
                    clientConfig.getLimits().getMonthlyCost(),
                    false
            );
        }
        UserAccount.UserLimits limits = account != null ? account.limits() : null;
        return new UserProfileController.MeQuotaResponse(
                0L,
                limits != null ? limits.dailyTokens() : null,
                BigDecimal.ZERO,
                limits != null ? limits.dailyCost() : null,
                0L,
                limits != null ? limits.monthlyTokens() : null,
                BigDecimal.ZERO,
                limits != null ? limits.monthlyCost() : null,
                false
        );
    }

    /**
     * Verify password against stored value. BCrypt is always supported; plaintext is gated by config.
     */
    public boolean verifyPassword(String rawPassword, String storedPassword) {
        if (storedPassword == null || storedPassword.isEmpty()) {
            return false;
        }
        if (storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$") || storedPassword.startsWith("$2y$")) {
            return passwordEncoder.matches(rawPassword, storedPassword);
        }
        if (!allowPlaintextPassword) {
            log.warn("Static user password is stored in plaintext; login rejected until migrated to BCrypt or plaintext compatibility is explicitly enabled");
            return false;
        }
        log.warn("Static user password is stored in plaintext; please migrate to BCrypt hash");
        return MessageDigest.isEqual(
                rawPassword.getBytes(StandardCharsets.UTF_8),
                storedPassword.getBytes(StandardCharsets.UTF_8));
    }

    public record TokenIdentity(String username, String role, String clientId) {
    }
}
