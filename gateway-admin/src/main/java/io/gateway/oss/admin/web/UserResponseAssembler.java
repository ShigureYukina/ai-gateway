package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.UserConfig;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.security.UserAccountCodec;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 负责组装用户管理相关响应，避免 Controller 承担 DTO 映射细节。
 */
public final class UserResponseAssembler {

    private UserResponseAssembler() {
    }

    public static AdminUserController.UsersResponse toUsersResponse(List<UserAccount> storedUsers,
                                                                    GatewayConfigView configView) {
        List<UserAccount> users = mergeConfiguredUsers(storedUsers, configView);
        return new AdminUserController.UsersResponse(
                Instant.now(),
                users.stream()
                        .map(user -> toUserView(user, configView))
                        .toList()
        );
    }

    public static AdminUserController.UserView toUserView(UserAccount userAccount,
                                                           GatewayConfigView configView) {
        AdminUserController.UserClientBinding binding = toUserClientBinding(userAccount, configView);
        return new AdminUserController.UserView(
                userAccount.username(),
                userAccount.role(),
                UserAccountCodec.maskApiKey(userAccount.apiKey()),
                binding.clientId(),
                binding.ownerUserId(),
                binding.clientName(),
                binding.apiKeyId(),
                userAccount.displayName(),
                userAccount.email(),
                userAccount.createdAt(),
                userAccount.frozen(),
                userAccount.frozenAt(),
                userAccount.limits(),
                userAccount.allowedModels()
        );
    }

    public static AdminUserController.UserClientBinding toUserClientBinding(UserAccount userAccount,
                                                                             GatewayConfigView configView) {
        UserAccount.ApiKeyRecord matched = userAccount.apiKeys() == null
                ? null
                : userAccount.apiKeys().stream()
                .filter(key -> Objects.equals(key.apiKey(), userAccount.apiKey()))
                .findFirst()
                .orElse(null);
        String apiKeyId = matched != null ? matched.keyId() : null;
        UserConfig configuredUser = configView.getAuth().getUsers().get(userAccount.username());
        String clientKey = resolveClientKey(userAccount.username(), configuredUser, configView);
        String maskedClientId = clientKey != null ? AdminBaseController.maskClientKey(clientKey) : null;
        String clientName = clientKey;
        return new AdminUserController.UserClientBinding(maskedClientId, userAccount.username(), clientName, apiKeyId);
    }

    public static List<UserAccount> mergeConfiguredUsers(List<UserAccount> storedUsers,
                                                         GatewayConfigView configView) {
        Map<String, UserAccount> merged = new LinkedHashMap<>();
        storedUsers.forEach(user -> merged.put(user.username(), user));
        configView.getAuth().getUsers().forEach((username, config) ->
                merged.computeIfAbsent(username, ignored -> configuredUserAccount(username, config)));
        return List.copyOf(merged.values());
    }

    public static UserAccount configuredUserAccount(String username, UserConfig config) {
        String role = normalize(config.getRole());
        return new UserAccount(
                username,
                "***",
                role != null ? role : "user",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                0L,
                0,
                false,
                null
        );
    }

    public static String resolveClientKey(String username,
                                          UserConfig configuredUser,
                                          GatewayConfigView configView) {
        if (configuredUser != null) {
            String configuredClientId = normalize(configuredUser.getClientId());
            if (configuredClientId != null && configView.getClients().containsKey(configuredClientId)) {
                return configuredClientId;
            }
        }
        return configView.getClients().containsKey(username) ? username : null;
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
