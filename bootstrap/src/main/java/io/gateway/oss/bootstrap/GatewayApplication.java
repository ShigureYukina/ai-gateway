package io.gateway.oss.bootstrap;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Locale;

/**
 * Standalone LLM Gateway — bootstrap entry point.
 * <p>
 * All beans are auto-configured by {@code gateway-core} and {@code gateway-admin}
 * starters via {@code META-INF/spring/*.AutoConfiguration.imports}.
 * No {@code @ComponentScan} or {@code @Import} is required.
 */
@SpringBootApplication
public class GatewayApplication {

    private static final String INIT_ENABLED_PROPERTY = AdminInitializationRunner.INIT_ENABLED_PROPERTY;
    private static final String INIT_ENABLED_ENV = "GATEWAY_BOOTSTRAP_INIT_ADMIN_ENABLED";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(GatewayApplication.class);
        boolean initAdminMode = isInitAdminMode();
        if (initAdminMode) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = application.run(args);
        if (initAdminMode) {
            int exitCode = SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }
    }

    static boolean isInitAdminMode() {
        String propertyValue = System.getProperty(INIT_ENABLED_PROPERTY);
        if (isTruthy(propertyValue)) {
            return true;
        }
        String envValue = System.getenv(INIT_ENABLED_ENV);
        return isTruthy(envValue);
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }
}
