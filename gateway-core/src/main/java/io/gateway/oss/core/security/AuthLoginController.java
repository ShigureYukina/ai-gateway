package io.gateway.oss.core.security;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.RegistrationMode;
import io.gateway.oss.core.config.UserConfig;
import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.dto.GatewayErrorResponse;
import io.gateway.oss.core.error.GatewayException;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/auth")
public class AuthLoginController {

    private static final Logger log = LoggerFactory.getLogger(AuthLoginController.class);

    private final JwtService jwtService;
    private final GatewayProperties properties;
    private final UserAccountService userAccountService;
    private final RefreshTokenBlacklistService blacklistService;
    private final LoginRateLimiter loginRateLimiter;
    private final AuthSupport authSupport;

    public AuthLoginController(JwtService jwtService,
                                GatewayProperties properties,
                                UserAccountService userAccountService,
                                RefreshTokenBlacklistService blacklistService,
                                LoginRateLimiter loginRateLimiter,
                                AuthSupport authSupport) {
        this.jwtService = jwtService;
        this.properties = properties;
        this.userAccountService = userAccountService;
        this.blacklistService = blacklistService;
        this.loginRateLimiter = loginRateLimiter;
        this.authSupport = authSupport;
    }

    @Operation(summary = "Authenticate with username/password to obtain JWT tokens")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "JWT tokens issued",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "401", description = "Invalid credentials",
                    content = @Content(schema = @Schema(implementation = GatewayErrorResponse.class)))
    })
    @SecurityRequirements
    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                     ServerWebExchange exchange) {
        requireAuthEnabled();
        loginRateLimiter.check(request.username());

        return userAccountService.authenticate(request.username(), request.password())
                .map(account -> {
                    loginRateLimiter.clear(request.username());
                    List<String> scopes = authSupport.resolveScopes(account.username());
                    String accessToken = jwtService.generateAccessToken(account.username(), account.username(), scopes, account.role(), account.tokenVersion());
                    String refreshToken = jwtService.generateRefreshToken(account.username());
                    return ResponseEntity.ok(new LoginResponse(accessToken, refreshToken, "Bearer"));
                })
                .onErrorResume(GatewayException.class, ex -> {
                    if ("invalid_credentials".equals(ex.getCode())) {
                        loginRateLimiter.recordFailure(request.username());
                        return loginFromStaticConfig(request);
                    }
                    return Mono.error(ex);
                });
    }

    /**
     * Compatibility login path for static YAML users.
     */
    private Mono<ResponseEntity<LoginResponse>> loginFromStaticConfig(LoginRequest request) {
        UserAccount existing = userAccountService.findByUsernameSync(request.username());
        if (existing != null && existing.frozen()) {
            return Mono.error(new GatewayException(HttpStatus.FORBIDDEN, "account_frozen", "Account is frozen"));
        }
        UserConfig userConfig = properties.getAuth().getUsers().get(request.username());
        if (userConfig == null || !authSupport.verifyPassword(request.password(), userConfig.getPassword())) {
            loginRateLimiter.recordFailure(request.username());
            return Mono.error(new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid username or password"));
        }

        loginRateLimiter.clear(request.username());
        String clientId = userConfig.getClientId() != null ? userConfig.getClientId() : request.username();
        ClientConfig clientConfig = properties.getClients().get(clientId);
        if (clientConfig == null || !clientConfig.isEnabled()) {
            return Mono.error(new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_client", "Client configuration not found or disabled"));
        }

        List<String> scopes = List.copyOf(clientConfig.getAllowedModels());
        String role = userConfig.getRole() != null ? userConfig.getRole() : "user";
        String accessToken = jwtService.generateAccessToken(request.username(), clientId, scopes, role);
        String refreshToken = jwtService.generateRefreshToken(request.username(), clientId);
        return Mono.just(ResponseEntity.ok(new LoginResponse(accessToken, refreshToken, "Bearer")));
    }

    @Operation(summary = "Refresh an expired access token using a valid refresh token")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New access token issued",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token",
                    content = @Content(schema = @Schema(implementation = GatewayErrorResponse.class)))
    })
    @SecurityRequirements
    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<LoginResponse>> refresh(@Valid @RequestBody RefreshRequest request,
                                                       ServerWebExchange exchange) {
        requireAuthEnabled();

        Claims claims = jwtService.parseToken(request.refreshToken());
        if (!jwtService.isRefreshToken(claims)) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_token_type", "Expected a refresh token");
        }

        return blacklistService.consumeOnce(request.refreshToken(), claims)
                .flatMap(consumed -> {
                    if (!Boolean.TRUE.equals(consumed)) {
                        return Mono.error(new GatewayException(HttpStatus.UNAUTHORIZED, "token_revoked", "Refresh token has been revoked"));
                    }
                    String username = jwtService.extractUsername(claims);
                    String clientId = jwtService.extractClientId(claims);
                    return userAccountService.findByUsername(username)
                            .map(account -> {
                                if (account.frozen()) {
                                    throw new GatewayException(HttpStatus.FORBIDDEN, "account_frozen", "Account is frozen");
                                }
                                List<String> scopes = authSupport.resolveScopes(clientId);
                                String accessToken = jwtService.generateAccessToken(account.username(), clientId, scopes, account.role(), account.tokenVersion());
                                String newRefreshToken = jwtService.generateRefreshToken(account.username(), clientId);
                                return ResponseEntity.ok(new LoginResponse(accessToken, newRefreshToken, "Bearer"));
                            })
                            .switchIfEmpty(Mono.fromCallable(() -> {
                                String effectiveClientId = clientId;
                                UserConfig userConfig = properties.getAuth().getUsers().get(username);
                                UserAccount existing = userAccountService.findByUsernameSync(username);
                                if (existing != null && existing.frozen()) {
                                    throw new GatewayException(HttpStatus.FORBIDDEN, "account_frozen", "Account is frozen");
                                }
                                if (userConfig != null && userConfig.getClientId() != null) {
                                    effectiveClientId = userConfig.getClientId();
                                }
                                ClientConfig clientConfig = properties.getClients().get(effectiveClientId);
                                if (clientConfig == null || !clientConfig.isEnabled()) {
                                    throw new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_client", "Client configuration not found or disabled");
                                }
                                List<String> scopes = List.copyOf(clientConfig.getAllowedModels());
                                String role = "user";
                                if (userConfig != null && userConfig.getRole() != null) {
                                    role = userConfig.getRole();
                                }
                                String accessToken = jwtService.generateAccessToken(username, effectiveClientId, scopes, role, 0);
                                String newRefreshToken = jwtService.generateRefreshToken(username, effectiveClientId);
                                return ResponseEntity.ok(new LoginResponse(accessToken, newRefreshToken, "Bearer"));
                            }));
                });
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(@Valid @RequestBody RefreshRequest request, ServerWebExchange exchange) {
        requireAuthEnabled();
        Claims claims = jwtService.parseToken(request.refreshToken());
        if (!jwtService.isRefreshToken(claims)) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_token_type", "Expected a refresh token");
        }
        return blacklistService.blacklist(request.refreshToken(), claims)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @Operation(summary = "Register a new user account")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User registered successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RegisterResponse.class))),
            @ApiResponse(responseCode = "409", description = "Username already taken",
                    content = @Content(schema = @Schema(implementation = GatewayErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing required fields",
                    content = @Content(schema = @Schema(implementation = GatewayErrorResponse.class)))
    })
    @SecurityRequirements
    @PostMapping(value = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request,
                                                           ServerWebExchange exchange) {
        requireAuthEnabled();

        RegistrationMode registrationMode;
        try {
            registrationMode = properties.getAuth().registrationMode();
        } catch (IllegalArgumentException e) {
            throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "config_error",
                    "Invalid gateway.auth.registration-mode: " + properties.getAuth().getRegistrationMode());
        }

        if (registrationMode == RegistrationMode.DISABLED) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "registration_disabled", "Public registration is disabled");
        }

        UserAccount.UserLimits initialLimits = registrationMode == RegistrationMode.RESTRICTED
                ? authSupport.toUserLimits(properties.getAuth().getRegistration())
                : UserAccount.UserLimits.highDefaults();
        java.util.Set<String> initialAllowedModels = registrationMode == RegistrationMode.RESTRICTED
                ? authSupport.copyAllowedModels(properties.getAuth().getRegistration())
                : null;

        return userAccountService.register(
                        request.username(),
                        request.password(),
                        null,
                        initialLimits,
                        initialAllowedModels,
                        request.displayName(),
                        request.email())
                .map(account -> {
                    List<String> scopes = authSupport.resolveScopes(account.username());
                    String accessToken = jwtService.generateAccessToken(account.username(), account.username(), scopes, account.role());
                    String refreshToken = jwtService.generateRefreshToken(account.username());
                    return ResponseEntity.ok(new RegisterResponse(accessToken, refreshToken, "Bearer", account.apiKey()));
                });
    }

    private void requireAuthEnabled() {
        authSupport.requireAuthEnabled();
    }

    @Schema(description = "JWT login request payload")
    public record LoginRequest(
            @Schema(description = "Username", example = "admin") @NotBlank String username,
            @Schema(description = "Password", example = "secret") @NotBlank String password
    ) {
    }

    @Schema(description = "JWT refresh request payload")
    public record RefreshRequest(
            @Schema(description = "Refresh token", example = "eyJhbGciOiJI...") @NotBlank String refreshToken
    ) {
    }

    @Schema(description = "JWT login/refresh response")
    public record LoginResponse(
            @Schema(description = "Bearer access token for authenticated API calls", example = "eyJhbGciOiJI...") String accessToken,
            @Schema(description = "Refresh token used only with /auth/refresh and /auth/logout", example = "eyJhbGciOiJI...") String refreshToken,
            @Schema(description = "Stable token type literal", example = "Bearer") String tokenType
    ) {
    }

    @Schema(description = "User registration request payload")
    public record RegisterRequest(
            @Schema(description = "Username", example = "newuser") @NotBlank String username,
            @Schema(description = "Password", example = "secure-password") @NotBlank String password,
            @Schema(description = "显示名称", example = "New User") String displayName,
            @Schema(description = "邮箱", example = "newuser@example.com") String email
    ) {
    }

    @Schema(description = "User registration response")
    public record RegisterResponse(
            @Schema(description = "Access token", example = "eyJhbGciOiJI...") String accessToken,
            @Schema(description = "Refresh token", example = "eyJhbGciOiJI...") String refreshToken,
            @Schema(description = "Token type", example = "Bearer") String tokenType,
            @Schema(description = "API key for this user", example = "gw-abc123...") String apiKey
    ) {
    }
}
