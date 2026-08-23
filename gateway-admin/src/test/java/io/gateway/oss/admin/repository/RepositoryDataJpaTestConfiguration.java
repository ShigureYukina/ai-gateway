package io.gateway.oss.admin.repository;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * repository 测试专用启动配置。
 * <p>
 * 放在 repository 包下，供 {@code @DataJpaTest} 就近发现，
 * 避免回退到模块级 {@code AdminTestConfiguration} 并装配非 JPA 切片所需 Bean。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = "io.gateway.oss.admin.entity")
class RepositoryDataJpaTestConfiguration {
}
