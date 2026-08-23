package io.gateway.oss.core.contract.security;

import io.gateway.oss.core.config.ClientConfig;

import java.util.Set;

/**
 * 认证后的客户端主体。
 *
 * @param clientId 客户端标识（JWT sub 或静态 API key）
 * @param config   对应的客户端配置（可能为 null，如动态注册用户无静态配置）
 * @param role     角色：admin 或 user（默认 user）
 * @param tenantId 租户 ID（null 表示单租户模式）
 */
public record ClientPrincipal(String clientId,
                              ClientConfig config,
                              String role,
                              String username,
                              boolean accountFrozen,
                              Set<String> keyAllowedModels,
                              UserAccount.UserLimits userLimits,
                              String tenantId) {

    public ClientPrincipal(String clientId, ClientConfig config) {
        this(clientId, config, "user", null, false, Set.of(), null, null);
    }

    public ClientPrincipal(String clientId, ClientConfig config, String role) {
        this(clientId, config, role, null, false, Set.of(), null, null);
    }

    public ClientPrincipal(String clientId, ClientConfig config, String role,
                           String username, boolean accountFrozen,
                           Set<String> keyAllowedModels,
                           UserAccount.UserLimits userLimits) {
        this(clientId, config, role, username, accountFrozen,
                keyAllowedModels, userLimits, null);
    }

    public boolean isAdmin() {
        return "admin".equals(role);
    }

    public boolean isMultiTenant() {
        return tenantId != null;
    }
}
