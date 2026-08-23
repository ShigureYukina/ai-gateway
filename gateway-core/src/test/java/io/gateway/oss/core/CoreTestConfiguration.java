package io.gateway.oss.core;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Test configuration for gateway-core module.
 * <p>
 * {@code @SpringBootTest} tests in this module need a
 * {@code @SpringBootConfiguration} to bootstrap the application context.
 * Auto-configuration classes registered via {@code META-INF/spring/*.imports}
 * provide bean definitions.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class CoreTestConfiguration {
}
