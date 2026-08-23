package io.gateway.oss.core.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Registers all gateway-core beans that are not covered by the existing
 * {@code @Import} in {@link GatewayCoreAutoConfiguration}.
 * <p>
 * Uses {@code @ComponentScan} for core-only packages (routing, security, util)
 * and explicit {@code @Import} for beans in packages shared with gateway-admin
 * (config, limit, observability, web, upstream).
 */
@Configuration
@ComponentScan(basePackages = {
    "io.gateway.oss.core.routing",
    "io.gateway.oss.core.security",
    "io.gateway.oss.core.util"
})
@Import(CoreImportedBeansConfig.class)
public class CoreComponentScanConfig {
}
