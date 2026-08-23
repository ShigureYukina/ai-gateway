package io.gateway.oss.admin.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Registers all gateway-admin beans that are discovered by
 * {@link GatewayAdminAutoConfiguration}.
 * <p>
 * Uses {@code @ComponentScan} for admin-only packages (config.audit, quota,
 * repository, sync, web.alerts, webhook) and explicit {@code @Import} for
 * beans in packages shared with gateway-core (limit, observability, web,
 * upstream).
 */
@Configuration
@ComponentScan(basePackages = {
    "io.gateway.oss.admin.config.audit",
    "io.gateway.oss.admin.pricing",
    "io.gateway.oss.admin.quota",
    "io.gateway.oss.admin.repository",
    "io.gateway.oss.admin.sync",
    "io.gateway.oss.admin.web.alerts",
    "io.gateway.oss.admin.webhook"
})
@Import(AdminImportedBeansConfig.class)
public class AdminComponentScanConfig {
}
