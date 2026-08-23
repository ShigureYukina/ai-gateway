package io.gateway.oss.bootstrap;

import io.gateway.oss.core.config.AuthConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.security.UserAccountService;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 正式环境管理员初始化校验。
 */
@Component
public class AdminInitializationStartupValidator {

    private final GatewayProperties gatewayProperties;
    private final Environment environment;
    private final UserAccountService userAccountService;

    public AdminInitializationStartupValidator(GatewayProperties gatewayProperties,
                                               Environment environment,
                                               UserAccountService userAccountService) {
        this.gatewayProperties = gatewayProperties;
        this.environment = environment;
        this.userAccountService = userAccountService;
    }

    @PostConstruct
    public void validate() {
        AuthConfig auth = gatewayProperties.getAuth();
        if (auth == null || !auth.isEnabled()) {
            return;
        }
        if (AdminInitializationRunner.isInitAdminMode(environment)) {
            return;
        }
        if (isLocalDevOrTestProfile()) {
            return;
        }

        Boolean hasDynamicAdmin = userAccountService.hasDynamicAdmin().block();
        if (Boolean.TRUE.equals(hasDynamicAdmin)) {
            return;
        }

        throw new IllegalStateException("检测到当前为非 local/dev 环境且 gateway.auth.enabled=true，但系统中不存在动态 admin 账户。"
                + "请先执行一次性初始化命令创建首个管理员：设置 gateway.bootstrap.init-admin.enabled=true，"
                + "并提供 gateway.bootstrap.init-admin.username 与 gateway.bootstrap.init-admin.password。");
    }

    private boolean isLocalDevOrTestProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "local".equalsIgnoreCase(profile)
                        || "dev".equalsIgnoreCase(profile)
                        || "test".equalsIgnoreCase(profile));
    }
}
