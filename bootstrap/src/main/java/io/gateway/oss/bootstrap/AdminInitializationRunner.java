package io.gateway.oss.bootstrap;

import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.security.UserAccountService;
import io.gateway.oss.core.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 首次管理员初始化命令入口。
 * <p>
 * 仅在 {@code gateway.bootstrap.init-admin.enabled=true} 时执行，
 * 参数通过环境变量或系统属性注入，不提供默认密码。
 * </p>
 */
@Component
public class AdminInitializationRunner implements ApplicationRunner {

    static final String INIT_ENABLED_PROPERTY = "gateway.bootstrap.init-admin.enabled";
    static final String USERNAME_PROPERTY = "gateway.bootstrap.init-admin.username";
    static final String PASSWORD_PROPERTY = "gateway.bootstrap.init-admin.password";
    static final String DISPLAY_NAME_PROPERTY = "gateway.bootstrap.init-admin.display-name";
    static final String EMAIL_PROPERTY = "gateway.bootstrap.init-admin.email";

    private static final Logger log = LoggerFactory.getLogger(AdminInitializationRunner.class);

    private final Environment environment;
    private final UserAccountService userAccountService;

    public AdminInitializationRunner(Environment environment, UserAccountService userAccountService) {
        this.environment = environment;
        this.userAccountService = userAccountService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isInitAdminMode(environment)) {
            return;
        }

        String username = requireText(USERNAME_PROPERTY);
        String password = requireText(PASSWORD_PROPERTY);
        String displayName = StringUtils.blankToNull(environment.getProperty(DISPLAY_NAME_PROPERTY));
        String email = StringUtils.blankToNull(environment.getProperty(EMAIL_PROPERTY));

        UserAccount created = userAccountService.register(username, password, "admin", null, null, displayName, email)
                .block();
        if (created == null) {
            throw new IllegalStateException("管理员初始化失败：未创建动态管理员账户");
        }

        log.info("dynamic_admin_initialized username={}", created.username());
    }

    static boolean isInitAdminMode(Environment environment) {
        return environment.getProperty(INIT_ENABLED_PROPERTY, Boolean.class, false);
    }

    private String requireText(String propertyName) {
        String value = StringUtils.blankToNull(environment.getProperty(propertyName));
        if (value == null) {
            throw new IllegalStateException("管理员初始化失败：缺少必填参数 " + propertyName
                    + "，请通过环境变量或系统属性提供");
        }
        return value;
    }
}
