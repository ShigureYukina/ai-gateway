package io.gateway.oss.admin.integration;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.LimitConfig;
import io.gateway.oss.core.config.OperationalConfig;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.ProviderConfig;
import io.gateway.oss.core.config.ResilienceConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.config.SceneConfig;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.TraceStore;
import io.gateway.oss.core.limit.ClientRateLimiter;
import io.gateway.oss.admin.quota.ClientQuotaService;
import io.gateway.oss.core.routing.ModelRouteResolver;
import io.gateway.oss.core.security.UserAccountService;
import io.gateway.oss.admin.sync.PricingSyncService;
import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashMap;
import java.util.Map;

@Testcontainers(disabledWithoutDocker = true)
abstract class RedisIntegrationTestSupport {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:14");
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("llm_gateway")
            .withUsername("llm_user")
            .withPassword("llm_password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    protected GatewayProperties gatewayProperties;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    protected JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    protected ProviderModelCatalogService providerModelCatalogService;

    @Autowired(required = false)
    protected PricingSyncService pricingSyncService;

    @Autowired(required = false)
    protected ProviderDiscoveryService providerDiscoveryService;

    @Autowired(required = false)
    protected RequestLogService requestLogService;

    @Autowired(required = false)
    protected TraceStore traceStore;

    @Autowired(required = false)
    protected UserAccountService userAccountService;

    @Autowired(required = false)
    protected ClientRateLimiter clientRateLimiter;

    @Autowired(required = false)
    protected ModelRouteResolver modelRouteResolver;

    @Autowired(required = false)
    protected ClientQuotaService clientQuotaService;

    private BaselineState baselineState;

    @BeforeEach
    void resetRedisBackedState() {
        if (baselineState == null) {
            baselineState = BaselineState.capture(gatewayProperties, objectMapper);
        }
        truncatePgTables();
        flushRedis();
        restoreGatewayProperties();
        resetSnapshots();
        clearServiceCaches();
    }

    @AfterEach
    void cleanupAfterTest() {
        restoreGatewayProperties();
        flushRedis();
    }

    protected void truncatePgTables() {
        if (jdbcTemplate == null) return;
        jdbcTemplate.execute(
                "TRUNCATE TABLE request_trace, admin_action_audit, webhook_delivery_log, webhook_endpoint, " +
                "public_model_mapping, public_model, employee_key, employee_group_member, " +
                "employee_group, employee, provider_registry RESTART IDENTITY CASCADE");
    }

    protected void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    private void restoreGatewayProperties() {
        gatewayProperties.setProviders(new LinkedHashMap<>(baselineState.providers));
        gatewayProperties.setRoutes(new LinkedHashMap<>(baselineState.routes));
        gatewayProperties.setScenes(new LinkedHashMap<>(baselineState.scenes));
        gatewayProperties.setClients(new LinkedHashMap<>(baselineState.clients));
        gatewayProperties.setLimit(cloneConfig(baselineState.limit, LimitConfig.class));
        gatewayProperties.setResilience(cloneConfig(baselineState.resilience, ResilienceConfig.class));
        gatewayProperties.setPricing(cloneConfig(baselineState.pricing, PricingConfig.class));
        gatewayProperties.setOperational(cloneConfig(baselineState.operational, OperationalConfig.class));
    }

    private void resetSnapshots() {
        if (providerModelCatalogService != null) {
            providerModelCatalogService.replaceSnapshot(Map.of(), null);
        }
        if (pricingSyncService != null) {
            pricingSyncService.replaceSnapshot(Map.of(), null);
        }
        if (providerDiscoveryService != null) {
            providerDiscoveryService.resetForTests();
        }
    }

    private void clearServiceCaches() {
        if (clientRateLimiter != null) {
            clientRateLimiter.reset();
        }
        if (modelRouteResolver != null) {
            modelRouteResolver.resetCache();
        }
        if (clientQuotaService != null) {
            clientQuotaService.resetCache();
        }
        if (requestLogService != null) {
            requestLogService.resetForTests();
        }
        if (traceStore != null) {
            traceStore.resetForTests();
        }
        if (userAccountService != null) {
            userAccountService.resetForTests();
        }
    }

    private <T> T cloneConfig(T value, Class<T> type) {
        return objectMapper.convertValue(value, type);
    }

    private record BaselineState(
            Map<String, ProviderConfig> providers,
            Map<String, RouteConfig> routes,
            Map<String, SceneConfig> scenes,
            Map<String, ClientConfig> clients,
            LimitConfig limit,
            ResilienceConfig resilience,
            PricingConfig pricing,
            OperationalConfig operational
    ) {
        static BaselineState capture(GatewayProperties properties, ObjectMapper objectMapper) {
            return new BaselineState(
                    copyMap(properties.getProviders(), ProviderConfig.class, objectMapper),
                    copyMap(properties.getRoutes(), RouteConfig.class, objectMapper),
                    copyMap(properties.getScenes(), SceneConfig.class, objectMapper),
                    copyMap(properties.getClients(), ClientConfig.class, objectMapper),
                    objectMapper.convertValue(properties.getLimit(), LimitConfig.class),
                    objectMapper.convertValue(properties.getResilience(), ResilienceConfig.class),
                    objectMapper.convertValue(properties.getPricing(), PricingConfig.class),
                    objectMapper.convertValue(properties.getOperational(), OperationalConfig.class)
            );
        }

        private static <T> Map<String, T> copyMap(Map<String, T> source, Class<T> type, ObjectMapper objectMapper) {
            Map<String, T> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> copy.put(key, objectMapper.convertValue(value, type)));
            return copy;
        }
    }
}
