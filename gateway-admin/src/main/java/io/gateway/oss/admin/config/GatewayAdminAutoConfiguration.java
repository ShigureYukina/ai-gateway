package io.gateway.oss.admin.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot auto-configuration entry point for gateway-admin.
 * <p>
 * Beans in admin-only packages (config.audit, quota, repository, sync,
 * web.alerts, webhook) are discovered via {@code @ComponentScan} in
 * {@link AdminComponentScanConfig}. Beans in packages shared with
 * gateway-core (limit, observability, web, upstream) are registered
 * via explicit {@code @Import}.
 * <p>
 * This class also enables scheduling for background tasks such as
 * provider health checks and model catalog synchronization.
 */
@AutoConfiguration
@EnableScheduling
@EnableJpaRepositories(basePackages = "io.gateway.oss.admin.repository")
@EntityScan(basePackages = "io.gateway.oss.admin.entity")
@Import(AdminComponentScanConfig.class)
public class GatewayAdminAutoConfiguration {

}
