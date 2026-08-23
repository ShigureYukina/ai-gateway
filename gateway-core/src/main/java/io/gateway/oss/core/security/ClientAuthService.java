package io.gateway.oss.core.security;

import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.RegistrationMode;
import io.gateway.oss.core.config.UserConfig;
import io.gateway.oss.core.error.GatewayException;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class ClientAuthService {

    private static final Logger log = LoggerFactory.getLogger(ClientAuthService.class);

    private final GatewayProperties properties;
    private final JwtService jwtService;
    private final UserAccountService userAccountService;

    public ClientAuthService(GatewayProperties properties, JwtService jwtService, UserAccountService userAccountService) {
        this.properties = properties;
        this.jwtService = jwtService;
        this.userAccountService = userAccountService;
    }

    /**
     * Authenticates the shared Bearer contract used by chat/model endpoints.
     * <p>
     * Recommended main path: {@code /auth/login} issues JWT access tokens for interactive clients.
     * Compatibility paths intentionally remain enabled and are tried in this order:
     * </p>
     * <ol>
     *     <li>Static client key exact match fast path</li>
     *     <li>JWT access token when {@code gateway.auth.enabled=true}</li>
     *     <li>Dynamic user API key from {@link UserAccountService}</li>
     *     <li>Static client key fallback</li>
     * </ol>
     * <p>
     * Refresh tokens are never accepted for request authentication.
     * </p>
     */
    public ClientPrincipal authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Missing or invalid Authorization header");
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();

        // Fast path: 静态 API Key 优先（避免非 JWT token 的 JWT 解析开销）
        var staticClient = properties.getClients().get(token);
        if (staticClient != null && staticClient.isEnabled()) {
            return new ClientPrincipal(token, staticClient);
        }

        // 尝试 JWT 认证（仅当 auth 启用时）
        if (properties.getAuth().isEnabled()) {
            try {
                Claims claims = jwtService.parseToken(token);
                if (isRefreshTokenClaim(claims)) {
                    throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Refresh token cannot be used for authentication");
                }

                String clientId = jwtService.extractClientId(claims);
                String username = jwtService.extractUsername(claims);
                String role = jwtService.extractRole(claims);
                int tokenVersion = jwtService.extractTokenVersion(claims);
                UserAccount account = userAccountService.findByUsernameSync(clientId);
                if (account == null) {
                    UserConfig staticUser = properties.getAuth().getUsers().get(clientId);
                    // 静态 YAML 用户的 JWT subject 始终是 username；当配置允许 username != clientId 且动态存储/缓存被清空时，
                    // 这里只按 clientId 回查会误判“账号不存在”，因此仅在静态用户回退路径补一次按 subject(username) 查找。
                    if (staticUser == null && username != null && !username.isBlank() && !username.equals(clientId)) {
                        staticUser = properties.getAuth().getUsers().get(username);
                    }
                    if (staticUser != null) {
                        String staticUsername = resolveStaticUsername(staticUser, clientId, username);
                        ClientPrincipal staticUserPrincipal = buildStaticUserJwtPrincipal(staticUsername, role, staticUser);
                        if (staticUserPrincipal != null) {
                            return staticUserPrincipal;
                        }
                    }
                    if (userAccountService.isDeletedUserWithOldToken(clientId, tokenVersion)) {
                        throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Account has been deleted");
                    }
                    log.warn("JWT authentication rejected because account was not found: username={}", clientId);
                    throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Account no longer exists");
                }

                validateJwtAccount(account, tokenVersion);
                ClientPrincipal jwtPrincipal = buildJwtPrincipal(clientId, role, account);
                if (jwtPrincipal != null) {
                    return jwtPrincipal;
                }
                throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Account no longer exists");
            } catch (JwtValidationException e) {
                // JWT 解析失败，回退到静态 API Key
            }
        }

        UserAccount account = userAccountService.findByApiKeySync(token);
        if (account != null) {
            if (account.frozen()) {
                throw new GatewayException(HttpStatus.FORBIDDEN, "account_frozen", "Account is frozen");
            }
            userAccountService.markApiKeyUsed(token);
            return buildApiKeyPrincipal(account, token);
        }

        // 静态 API Key（向后兼容）
        var client = properties.getClients().get(token);
        if (client == null || !client.isEnabled()) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid API key");
        }
        return new ClientPrincipal(token, client);
    }

    /**
     * 要求 admin 权限。非 admin 抛 403 forbidden。
     */
    public void requireAdmin(ClientPrincipal principal) {
        if (principal == null || !principal.isAdmin()) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden", "Admin access required");
        }
    }

    public void authorizeModel(ClientPrincipal principal, String modelAlias) {
        if (principal.accountFrozen()) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "account_frozen", "Account is frozen");
        }
        // Key 级别的 allowedModels 是精确约束：若 key 明确指定了可访问模型，以此为准，跳过 client config 检查
        if (principal.keyAllowedModels() != null && !principal.keyAllowedModels().isEmpty()) {
            if (!principal.keyAllowedModels().contains(modelAlias)) {
                throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden_model", "API key cannot access requested model");
            }
            return;
        }
        var client = principal.config();
        // 动态注册用户（无静态 client 配置）允许访问所有模型
        if (client == null) {
            return;
        }
        if (!isAllowed(client.getAllowedModels(), modelAlias)) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden_model", "Client cannot access requested model");
        }
    }

    public void authorizeScene(ClientPrincipal principal, String scene) {
        if (scene == null || scene.isBlank()) {
            return;
        }
        var client = principal.config();
        // 动态注册用户（无静态 client 配置）允许访问所有 scene
        if (client == null) {
            return;
        }
        if (!client.getAllowedScenes().isEmpty() && !isAllowed(client.getAllowedScenes(), scene)) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden_scene", "Client cannot access requested scene");
        }
    }

    public void validateRequestCapabilities(ClientPrincipal principal, boolean streamEnabled, Integer maxTokens) {
        var client = principal.config();
        if (client == null) {
            return;
        }
        if (streamEnabled && !client.getCapabilities().isStreaming()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "stream_not_supported", "Streaming is disabled for this client");
        }
        Integer configuredMaxTokens = client.getLimits().getMaxTokens();
        if (configuredMaxTokens != null && maxTokens != null && maxTokens > configuredMaxTokens) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "max_tokens_exceeded", "Requested max_tokens exceeds client limit");
        }
    }

    private boolean isAllowed(Set<String> allowedValues, String value) {
        return allowedValues != null && allowedValues.contains(value);
    }

    private void validateJwtAccount(UserAccount account, int tokenVersion) {
        if (account == null) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Account no longer exists");
        }
        if (account.tokenVersion() != tokenVersion) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Token has been invalidated");
        }
        if (account.frozen()) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "account_frozen", "Account is frozen");
        }
    }

    private ClientPrincipal buildJwtPrincipal(String clientId, String role, UserAccount account) {
        var client = properties.getClients().get(clientId);
        if (client != null && client.isEnabled()) {
            return new ClientPrincipal(clientId, client, role, clientId, false, Set.of(), accountLimits(account));
        }
        ClientConfig bootstrapClient = resolveBootstrapClientConfig(account);
        if (bootstrapClient != null && role != null) {
            // 动态用户且账户级有独立 allowedModels 时，覆盖 bootstrap 模板的 allowedModels
            if (account != null && account.allowedModels() != null && !account.allowedModels().isEmpty()) {
                bootstrapClient.setAllowedModels(new HashSet<>(account.allowedModels()));
            }
            return new ClientPrincipal(clientId, bootstrapClient, role, clientId, false, Set.of(), accountLimits(account));
        }
        if (account != null && role != null) {
            return new ClientPrincipal(clientId, null, role, clientId, false, Set.of(), accountLimits(account));
        }
        return null;
    }

    private ClientPrincipal buildStaticUserJwtPrincipal(String username, String roleFromToken, UserConfig staticUser) {
        String role = roleFromToken;
        if (role == null || role.isBlank()) {
            role = staticUser != null ? staticUser.getRole() : null;
        }
        if (role == null || role.isBlank()) {
            role = "user";
        }

        ClientConfig client = properties.getClients().get(username);
        if (client != null && client.isEnabled()) {
            return new ClientPrincipal(username, client, role, username, false, Set.of(), null);
        }

        String configuredClientId = staticUser != null ? staticUser.getClientId() : null;
        if (configuredClientId != null && !configuredClientId.isBlank()) {
            ClientConfig mappedClient = properties.getClients().get(configuredClientId);
            if (mappedClient != null && mappedClient.isEnabled()) {
                return new ClientPrincipal(configuredClientId, mappedClient, role, username, false, Set.of(), null);
            }
        }

        return new ClientPrincipal(username, null, role, username, false, Set.of(), null);
    }

    private String resolveStaticUsername(UserConfig staticUser, String clientId, String subjectUsername) {
        if (subjectUsername != null && !subjectUsername.isBlank()) {
            return subjectUsername;
        }
        return clientId;
    }

    private boolean isRefreshTokenClaim(Claims claims) {
        if (jwtService.isRefreshToken(claims)) {
            return true;
        }
        return "refresh".equals(claims.get("tokenType", String.class))
                || "refresh".equals(claims.get("type", String.class));
    }

    private ClientPrincipal buildApiKeyPrincipal(UserAccount account, String token) {
        var resolved = properties.getClients().get(account.username());
        if (resolved == null) {
            resolved = resolveBootstrapClientConfig(account);
            // 动态用户且账户级有独立 allowedModels 时，覆盖 bootstrap 模板的 allowedModels
            if (resolved != null && account.allowedModels() != null && !account.allowedModels().isEmpty()) {
                resolved.setAllowedModels(new HashSet<>(account.allowedModels()));
            }
        }
        UserAccount.ApiKeyRecord keyRecord = userAccountService.findApiKeyRecord(account, token);
        Set<String> allowedModels = keyRecord != null && keyRecord.allowedModels() != null ? keyRecord.allowedModels() : Set.of();
        return new ClientPrincipal(account.username(), resolved, account.role(), account.username(), account.frozen(), allowedModels, account.limits());
    }

    private ClientConfig resolveBootstrapClientConfig(UserAccount account) {
        if (account == null || properties.getAuth().registrationMode() != RegistrationMode.RESTRICTED) {
            return null;
        }
        return properties.getAuth().getRegistration().toClientConfig();
    }

    private UserAccount.UserLimits accountLimits(UserAccount account) {
        return account != null ? account.limits() : null;
    }
}
