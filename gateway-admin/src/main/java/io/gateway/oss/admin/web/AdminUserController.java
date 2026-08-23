package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ApiKeyToggleRequest;
import io.gateway.oss.admin.dto.UserAllowedModelsUpdateRequest;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.security.UserAccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/admin")
public class AdminUserController extends AdminBaseController {

    private final UserAccountService userAccountService;
    private final GatewayConfigView properties;

    public AdminUserController(ClientAuthService clientAuthService,
                               UserAccountService userAccountService,
                               GatewayConfigView configView) {
        super(clientAuthService);
        this.userAccountService = userAccountService;
        this.properties = configView;
    }

    @GetMapping("/users")
    public Mono<UsersResponse> users(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        return userAccountService.listUsers()
                .map(users -> UserResponseAssembler.toUsersResponse(users, properties));
    }

    @PostMapping("/users")
    public Mono<ResponseEntity<UserView>> createUser(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody CreateUserRequest request) {
        requireAdminAccess(authorizationHeader);
        return userAccountService.register(request.username(), request.password(), request.role(), null, null, request.displayName(), request.email())
                .map(user -> UserResponseAssembler.toUserView(user, properties))
                .map(u -> ResponseEntity.status(HttpStatus.CREATED).body(u));
    }

    @PutMapping("/users/{username}")
    public Mono<ResponseEntity<UserView>> putUserRole(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username,
            @RequestBody UpdateUserRequest request) {
        requireAdminAccess(authorizationHeader);
        Mono<UserAccount> update = request.role() != null
                ? userAccountService.updateRole(username, request.role())
                : userAccountService.findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")));
        if (request.frozen() != null) {
            update = update.flatMap(account -> userAccountService.updateFrozen(username, request.frozen()));
        }
        if (request.displayName() != null || request.email() != null) {
            update = update.flatMap(account -> userAccountService.updateProfile(username, request.displayName(), request.email()));
        }
        return update
                .map(user -> UserResponseAssembler.toUserView(user, properties))
                .map(ResponseEntity::ok);
    }

    @PutMapping("/users/{username}/limits")
    public Mono<ResponseEntity<UserView>> updateUserLimits(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username,
            @RequestBody UpdateUserLimitsRequest request) {
        requireAdminAccess(authorizationHeader);
        UserAccount.UserLimits limits = new UserAccount.UserLimits(
                request.dailyTokens(),
                request.monthlyTokens(),
                request.tokensPerMinute(),
                request.maxTokens(),
                request.dailyCost(),
                request.monthlyCost()
        );
        return userAccountService.updateLimits(username, limits)
                .map(user -> UserResponseAssembler.toUserView(user, properties))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/users/{username}")
    public Mono<ResponseEntity<Void>> deleteUser(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username) {
        requireAdminAccess(authorizationHeader);
        return userAccountService.deleteUser(username)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/users/{username}/reset-password")
    public Mono<ResponseEntity<ResetPasswordResponse>> resetUserPassword(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username) {
        requireAdminAccess(authorizationHeader);
        return userAccountService.resetPassword(username)
                .map(tempPassword -> ResponseEntity.ok(new ResetPasswordResponse(tempPassword)));
    }

    // ─── Admin API Key management ───

    @GetMapping("/users/{username}/api-keys")
    public Mono<List<UserAccountService.ApiKeyView>> listUserApiKeys(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username) {
        requireAdminAccess(authorizationHeader);
        return userAccountService.listApiKeys(username);
    }

    @PostMapping("/users/{username}/api-keys")
    public Mono<ResponseEntity<UserAccountService.ApiKeyView>> createUserApiKey(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username,
            @RequestBody CreateApiKeyRequest request) {
        requireAdminAccess(authorizationHeader);
        return userAccountService.createApiKey(username, request.name(), request.allowedModels() != null ? new HashSet<>(request.allowedModels()) : Set.of())
                .map(k -> new UserAccountService.ApiKeyView(k.keyId(), k.name(), k.apiKey(), k.enabled(), k.createdAt(), k.lastUsedAt(), k.requestCount(), k.allowedModels()))
                .map(key -> ResponseEntity.status(HttpStatus.CREATED).body(key));
    }

    @PatchMapping("/users/{username}/api-keys/{keyId}")
    public Mono<ResponseEntity<Void>> updateUserApiKey(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username,
            @PathVariable String keyId,
            @RequestBody PatchApiKeyRequest request) {
        requireAdminAccess(authorizationHeader);
        return userAccountService.updateApiKey(username, keyId, request.enabled(), request.name(), request.allowedModels() != null ? new HashSet<>(request.allowedModels()) : null)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @DeleteMapping("/users/{username}/api-keys/{keyId}")
    public Mono<ResponseEntity<Void>> deleteUserApiKey(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username,
            @PathVariable String keyId) {
        requireAdminAccess(authorizationHeader);
        return userAccountService.deleteApiKey(username, keyId)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @PutMapping("/users/{username}/api-keys/{keyId}/toggle")
    public Mono<ResponseEntity<Void>> toggleUserApiKey(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username,
            @PathVariable String keyId,
            @RequestBody ApiKeyToggleRequest body) {
        requireAdminAccess(authorizationHeader);
        boolean enabled = body.enabled() == null || body.enabled();
        return userAccountService.updateApiKeyEnabled(username, keyId, enabled)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @PostMapping("/users/{username}/api-keys/{keyId}/rotate")
    public Mono<ResponseEntity<UserAccount.ApiKeyRecord>> rotateUserApiKey(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username,
            @PathVariable String keyId) {
        requireAdminAccess(authorizationHeader);
        return userAccountService.rotateApiKey(username, keyId)
                .map(key -> ResponseEntity.status(HttpStatus.CREATED).body(key));
    }

    @PutMapping("/users/{username}/allowed-models")
    public Mono<ResponseEntity<UserView>> updateUserAllowedModels(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String username,
            @RequestBody UserAllowedModelsUpdateRequest body) {
        requireAdminAccess(authorizationHeader);
        java.util.Set<String> models = body.allowedModels() != null
                ? new java.util.HashSet<>(body.allowedModels())
                : java.util.Set.of();
        return userAccountService.updateAllowedModels(username, models)
                .map(user -> UserResponseAssembler.toUserView(user, properties))
                .map(ResponseEntity::ok);
    }

    // ─── response records ───

    public record UsersResponse(
            Instant generatedAt,
            List<UserView> users
    ) {
    }

    public record UserView(
            String username,
            String role,
            String apiKeyMasked,
            String clientId,
            String ownerUserId,
            String clientName,
            String apiKeyId,
            String displayName,
            String email,
            long createdAt,
            boolean frozen,
            Long frozenAt,
            UserAccount.UserLimits limits,
            java.util.Set<String> allowedModels
    ) {
    }

    public record UserClientBinding(
            String clientId,
            String ownerUserId,
            String clientName,
            String apiKeyId
    ) {
    }

    public record UpdateUserRequest(
            String role,
            Boolean frozen,
            String displayName,
            String email
    ) {
    }

    public record UpdateUserLimitsRequest(
            Long dailyTokens,
            Long monthlyTokens,
            Long tokensPerMinute,
            Integer maxTokens,
            java.math.BigDecimal dailyCost,
            java.math.BigDecimal monthlyCost
    ) {
    }

    public record CreateUserRequest(
            @jakarta.validation.constraints.NotBlank String username,
            @jakarta.validation.constraints.NotBlank String password,
            String role,
            String displayName,
            String email
    ) {
    }

    public record ResetPasswordResponse(
            String temporaryPassword
    ) {
    }

    public record CreateApiKeyRequest(
            String name,
            java.util.Set<String> allowedModels
    ) {
    }

    public record PatchApiKeyRequest(
            Boolean enabled,
            String name,
            java.util.Set<String> allowedModels
    ) {
    }
}
