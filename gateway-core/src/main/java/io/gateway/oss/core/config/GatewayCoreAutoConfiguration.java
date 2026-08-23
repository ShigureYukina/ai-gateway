package io.gateway.oss.core.config;

import io.gateway.oss.core.limit.LimitStoreConfig;
import io.gateway.oss.core.observability.ObservabilityStoreConfig;
import io.gateway.oss.core.security.SecurityBeansConfig;
import io.gateway.oss.core.upstream.UpstreamBeansConfig;
import io.gateway.oss.core.upstream.UpstreamStateStoreConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot auto-configuration entry point for gateway-core.
 * <p>
 * Discovers and registers all core infrastructure beans without requiring
 * consumers to configure {@code @ComponentScan} or {@code @Import}.
 * <p>
 * Beans in core-only packages (routing, security, util) are discovered via
 * {@link CoreComponentScanConfig @ComponentScan}. Beans in packages shared
 * with gateway-admin (config, limit, observability, web, upstream) are
 * registered via explicit {@code @Import}.
 * <p>
 * The following configuration classes are intentionally <b>not</b> registered
 * and left for consumers to opt in:
 * <ul>
 *   <li>{@link CorsConfig} / {@link NettyServerConfig} — app-layer customization</li>
 *   <li>{@link OpenApiConfig} — Swagger docs, not core to forwarding</li>
 * </ul>
 */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(GatewayProperties.class)
@Import({
    WebClientConfig.class,
    SchedulerConfig.class,
    SharedStateBeansConfig.class,
    ConfigStoreAutoConfig.class,
    LimitStoreConfig.class,
    ObservabilityStoreConfig.class,
    SecurityBeansConfig.class,
    UpstreamBeansConfig.class,
    UpstreamStateStoreConfig.class,
    CoreComponentScanConfig.class
})
public class GatewayCoreAutoConfiguration {

}
