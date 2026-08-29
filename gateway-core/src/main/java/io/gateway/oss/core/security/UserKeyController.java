package io.gateway.oss.core.security;

import io.gateway.oss.core.config.GatewayProperties;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/auth/keys")
public class UserKeyController {

    private final JwtService jwtService;
    private final GatewayProperties properties;
    private final UserAccountService userAccountService;
    private final AuthSupport authSupport;

    public UserKeyController(JwtService jwtService,
                             GatewayProperties properties,
                             UserAccountService userAccountService,
                             AuthSupport authSupport) {
        this.jwtService = jwtService;
        this.properties = properties;
        this.userAccountService = userAccountService;
        this.authSupport = authSupport;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<CreateKeyResponse>> createKey(@Valid @RequestBody CreateKeyRequest request,
                                                             ServerWebExchange exchange) {
        requireAuthEnabled();
        return authSupport.parseAccessClaims(exchange)
                .flatMap(claims -> {
                    AuthSupport.TokenIdentity identity = authSupport.resolveIdentity(claims);
                    return userAccountService.ensureUserAccount(identity.username(), identity.role())
                            .flatMap(account -> userAccountService.createApiKey(account.username(), request.name(), request.allowedModels()))
                            .map(key -> ResponseEntity.ok(new CreateKeyResponse(
                                    key.keyId(),
                                    key.name(),
                                    key.apiKey(),
                                    UserAccountCodec.maskApiKey(key.apiKey()),
                                    key.enabled(),
                                    key.createdAt(),
                                    key.allowedModels()
                            )));
                });
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<KeysResponse>> listKeys(ServerWebExchange exchange) {
        requireAuthEnabled();
        return authSupport.parseAccessClaims(exchange)
                .flatMap(claims -> {
                    AuthSupport.TokenIdentity identity = authSupport.resolveIdentity(claims);
                    return userAccountService.ensureUserAccount(identity.username(), identity.role())
                            .flatMap(account -> userAccountService.listApiKeys(account.username()))
                            .map(keys -> ResponseEntity.ok(new KeysResponse(
                                    keys.stream().map(k -> new KeyItem(k.keyId(), k.name(), k.apiKeyMasked(), k.enabled(), k.createdAt(), k.lastUsedAt(), k.requestCount(), k.allowedModels())).toList()
                            )));
                });
    }

    @PatchMapping(value = "/{keyId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> patchKey(@PathVariable String keyId,
                                               @Valid @RequestBody PatchKeyRequest request,
                                               ServerWebExchange exchange) {
        requireAuthEnabled();
        return authSupport.parseAccessClaims(exchange)
                .flatMap(claims -> {
                    AuthSupport.TokenIdentity identity = authSupport.resolveIdentity(claims);
                    return userAccountService.ensureUserAccount(identity.username(), identity.role())
                            .flatMap(account -> userAccountService.updateApiKey(account.username(), keyId, request.enabled(), request.name(), request.allowedModels()))
                            .thenReturn(ResponseEntity.noContent().build());
                });
    }

    @DeleteMapping("/{keyId}")
    public Mono<ResponseEntity<Void>> deleteKey(@PathVariable String keyId, ServerWebExchange exchange) {
        requireAuthEnabled();
        return authSupport.parseAccessClaims(exchange)
                .flatMap(claims -> {
                    AuthSupport.TokenIdentity identity = authSupport.resolveIdentity(claims);
                    return userAccountService.ensureUserAccount(identity.username(), identity.role())
                            .flatMap(account -> userAccountService.deleteApiKey(account.username(), keyId))
                            .thenReturn(ResponseEntity.noContent().build());
                });
    }

    @PostMapping(value = "/{keyId}/rotate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<RotateKeyResponse>> rotateKey(@PathVariable String keyId,
                                                             ServerWebExchange exchange) {
        requireAuthEnabled();
        return authSupport.parseAccessClaims(exchange)
                .flatMap(claims -> {
                    AuthSupport.TokenIdentity identity = authSupport.resolveIdentity(claims);
                    return userAccountService.ensureUserAccount(identity.username(), identity.role())
                            .flatMap(account -> userAccountService.rotateApiKey(account.username(), keyId))
                            .map(key -> ResponseEntity.ok(new RotateKeyResponse(
                                    key.keyId(),
                                    key.name(),
                                    key.apiKey(),
                                    UserAccountCodec.maskApiKey(key.apiKey()),
                                    key.enabled(),
                                    key.createdAt(),
                                    key.allowedModels()
                            )));
                });
    }

    private void requireAuthEnabled() {
        authSupport.requireAuthEnabled();
    }

    public record CreateKeyRequest(
            String name,
            java.util.Set<String> allowedModels
    ) {
    }

    public record CreateKeyResponse(
            String keyId,
            String name,
            String apiKey,
            String apiKeyMasked,
            boolean enabled,
            long createdAt,
            java.util.Set<String> allowedModels
    ) {
    }

    public record KeysResponse(
            List<KeyItem> keys
    ) {
    }

    public record KeyItem(
            String keyId,
            String name,
            String apiKeyMasked,
            boolean enabled,
            long createdAt,
            Long lastUsedAt,
            long requestCount,
            java.util.Set<String> allowedModels
    ) {
    }

    public record PatchKeyRequest(Boolean enabled, String name, java.util.Set<String> allowedModels) {
        public PatchKeyRequest {
        }
    }

    public record RotateKeyResponse(
            String keyId,
            String name,
            String apiKey,
            String apiKeyMasked,
            boolean enabled,
            long createdAt,
            java.util.Set<String> allowedModels
    ) {
    }
}
