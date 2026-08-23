package io.gateway.oss.admin.repository;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Repository 测试共享的 JPA 切片配置。
 * <p>
 * 统一使用 Flyway + H2，避免各测试类重复声明属性，提升 Spring 测试上下文复用率。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-test",
        "spring.datasource.url=jdbc:h2:mem:gateway-admin-repository-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none"
})
abstract class RepositoryDataJpaTestSupport {
}
