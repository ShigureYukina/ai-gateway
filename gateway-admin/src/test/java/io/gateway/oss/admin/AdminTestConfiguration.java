package io.gateway.oss.admin;

import io.gateway.oss.admin.web.WebTestCleanupSupport;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Test configuration for gateway-admin module.
 * <p>
 * {@code @SpringBootTest} tests in this module need a
 * {@code @SpringBootConfiguration} to bootstrap the application context.
 * Auto-configuration classes registered via {@code META-INF/spring/*.imports}
 * provide bean definitions.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(WebTestCleanupSupport.class)
public class AdminTestConfiguration {
}
