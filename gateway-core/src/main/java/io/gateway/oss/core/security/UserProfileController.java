package io.gateway.oss.core.security;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.contract.security.UserAccount;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/auth")
public class UserProfileController {

    private final JwtService jwtService;
    private final GatewayProperties properties;
    private final UserAccountService userAccountService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthSupport authSupport;

    public UserProfileController(JwtService jwtService,
                                 GatewayProperties properties,
                                 UserAccountService userAccountService,
                                 BCryptPasswordEncoder passwordEncoder,
                                 AuthSupport authSupport) {
        this.jwtService = jwtService;
        this.properties = properties;
        this.userAccountService = userAccountService;
        this.passwordEncoder = passwordEncoder;
        this.authSupport = authSupport;
    }

    @Operation(
            summary = "Return the current authenticated user",
            description = "Requires an access token. Missing bearer returns unauthorized, malformed or invalid JWT returns invalid_token, expired JWT returns token_expired, and using a refresh token as bearer returns unauthorized."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user resolved from an access token",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = MeResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication failed; error code distinguishes unauthorized vs invalid_token cases",
                    content = @Content(schema = @Schema(implementation = io.gateway.oss.core.dto.GatewayErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Frozen account",
                    content = @Content(schema = @Schema(implementation = io.gateway.oss.core.dto.GatewayErrorResponse.class)))
    })
    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<MeResponse>> me(ServerWebExchange exchange) {
        requireAuthEnabled();
        return parseAccessClaims(exchange)
                .flatMap(claims -> {
                    AuthSupport.TokenIdentity identity = resolveIdentity(claims);
                    return userAccountService.findByUsername(identity.username())
                            .map(account -> new MeResponse(
                                    account.username(),
                                    account.role(),
                                    account.displayName(),
                                    account.email(),
                                    UserAccountCodec.maskApiKey(account.apiKey()),
                                    account.createdAt(),
                                    buildQuota(identity, account)))
                            .switchIfEmpty(Mono.fromCallable(() -> new MeResponse(
                                    identity.username(),
                                    identity.role(),
                                    null,
                                    null,
                                    null,
                                    0L,
                                    buildQuota(identity, null))))
                            .map(ResponseEntity::ok);
                });
    }

    @PutMapping("/profile")
    public Mono<ResponseEntity<MeResponse>> updateProfile(@Valid @RequestBody UpdateProfileRequest request,
                                                          ServerWebExchange exchange) {
        requireAuthEnabled();
        return parseAccessClaims(exchange)
                .flatMap(claims -> {
                    AuthSupport.TokenIdentity identity = resolveIdentity(claims);
                    // 仅动态账户支持资料落库，静态 YAML 用户保持兼容返回。
                    return userAccountService.updateProfile(identity.username(), request.displayName(), request.email())
                            .map(account -> new MeResponse(
                                    account.username(),
                                    account.role(),
                                    account.displayName(),
                                    account.email(),
                                    UserAccountCodec.maskApiKey(account.apiKey()),
                                    account.createdAt(),
                                    buildQuota(identity, account)))
                            .map(ResponseEntity::ok);
                });
    }

    @PutMapping("/password")
    public Mono<ResponseEntity<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                     ServerWebExchange exchange) {
        requireAuthEnabled();
        return parseAccessClaims(exchange)
                .flatMap(claims -> {
                    AuthSupport.TokenIdentity identity = resolveIdentity(claims);
                    if (request.newPassword() == null || request.newPassword().length() < 6) {
                        throw new io.gateway.oss.core.error.GatewayException(HttpStatus.BAD_REQUEST, "invalid_password", "New password must be at least 6 characters");
                    }
                    return userAccountService.changePassword(identity.username(), request.oldPassword(), request.newPassword())
                            .thenReturn(ResponseEntity.noContent().build());
                });
    }

    private Mono<Claims> parseAccessClaims(ServerWebExchange exchange) {
        return authSupport.parseAccessClaims(exchange);
    }

    private AuthSupport.TokenIdentity resolveIdentity(Claims claims) {
        return authSupport.resolveIdentity(claims);
    }

    private List<String> resolveScopes(String clientId) {
        return authSupport.resolveScopes(clientId);
    }

    private ClientConfig resolveEffectiveClientConfig(String clientId) {
        return authSupport.resolveEffectiveClientConfig(clientId);
    }

    private MeQuotaResponse buildQuota(AuthSupport.TokenIdentity identity, UserAccount account) {
        return authSupport.buildQuota(identity, account);
    }

    private UserAccount.UserLimits toUserLimits(io.gateway.oss.core.config.AuthRegistrationConfig registration) {
        return authSupport.toUserLimits(registration);
    }

    private java.util.Set<String> copyAllowedModels(io.gateway.oss.core.config.AuthRegistrationConfig registration) {
        return authSupport.copyAllowedModels(registration);
    }

    private void requireAuthEnabled() {
        authSupport.requireAuthEnabled();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Current authenticated user response")
    public record MeResponse(
            @Schema(description = "Stable username for the authenticated identity", example = "admin") String username,
            @Schema(description = "Resolved role for the authenticated identity", example = "admin") String role,
            @Schema(description = "用户显示名称", example = "管理员") String displayName,
            @Schema(description = "用户邮箱", example = "admin@example.com") String email,
            @Schema(description = "Masked primary API key when a persisted user account exists; omitted for compatibility-only static YAML users", example = "****c123") String apiKeyMasked,
            @Schema(description = "Account creation epoch millis for persisted users; 0 for compatibility-only static YAML users", example = "1716200000000") long createdAt,
            @Schema(description = "当前配额与预算摘要") MeQuotaResponse quota
    ) {
    }

    public record MeQuotaResponse(
            @Schema(description = "当日已用 tokens", example = "0") long dailyTokensUsed,
            @Schema(description = "当日 tokens 限额", example = "1000") Long dailyTokensLimit,
            @Schema(description = "当日已用成本", example = "0") BigDecimal dailyCostUsed,
            @Schema(description = "当日成本限额", example = "1.25") BigDecimal dailyCostLimit,
            @Schema(description = "当月已用 tokens", example = "0") long monthlyTokensUsed,
            @Schema(description = "当月 tokens 限额", example = "5000") Long monthlyTokensLimit,
            @Schema(description = "当月已用成本", example = "0") BigDecimal monthlyCostUsed,
            @Schema(description = "当月成本限额", example = "9.99") BigDecimal monthlyCostLimit,
            @Schema(description = "是否不支持月度额度", example = "false") boolean monthlyUnsupported
    ) {
    }

    public record UpdateProfileRequest(
            String displayName,
            String email
    ) {
    }

    public record ChangePasswordRequest(
            @NotBlank String oldPassword,
            @NotBlank String newPassword
    ) {
    }
}
