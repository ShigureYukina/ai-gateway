package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.AuthConfig;
import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.ConcurrentLimitConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.LimitConfig;
import io.gateway.oss.core.config.OperationalConfig;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.ProviderConfig;
import io.gateway.oss.core.config.ProviderHealthConfig;
import io.gateway.oss.core.config.ResilienceConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.config.SceneConfig;
import io.gateway.oss.core.config.SyncConfig;
import io.gateway.oss.core.config.TraceConfig;
import io.gateway.oss.admin.config.audit.ConfigAuditService;
import io.gateway.oss.admin.config.audit.ConfigVersionService;
import io.gateway.oss.core.config.ConfigStore;
import io.gateway.oss.core.config.InMemoryConfigStore;
import io.gateway.oss.admin.limit.ClientTpmStore;
import io.gateway.oss.core.limit.ClientRateLimiter;
import io.gateway.oss.admin.limit.InMemoryClientTpmStore;
import io.gateway.oss.admin.observability.AggregateMetricStore;
import io.gateway.oss.admin.observability.AggregateReportingService;
import io.gateway.oss.admin.observability.InMemoryAggregateMetricStore;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.TraceStore;
import io.gateway.oss.admin.quota.ClientCostStore;
import io.gateway.oss.admin.quota.ClientUsageStore;
import io.gateway.oss.admin.quota.InMemoryClientCostStore;
import io.gateway.oss.admin.quota.InMemoryClientUsageStore;
import io.gateway.oss.core.security.UserAccountService;
import io.gateway.oss.admin.sync.ModelMetadataService;
import io.gateway.oss.admin.sync.PricingSyncService;
import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import io.gateway.oss.core.upstream.InMemoryProviderRuntimeStateStore;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reusable test cleanup for standalone web layer tests that don't extend
 * {@link io.gateway.oss.admin.integration.IntegrationTestBase}.
 *
 * Captures a baseline snapshot of {@link GatewayProperties} on first run,
 * then restores all mutable in-memory state before each test method.
 */
@Component
public class WebTestCleanupSupport {

    @Autowired
    private GatewayProperties gatewayProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfigStore configStore;

    @Autowired(required = false)
    private InMemoryConfigStore inMemoryConfigStore;

    @Autowired(required = false)
    private ClientUsageStore clientUsageStore;

    @Autowired(required = false)
    private ClientCostStore clientCostStore;

    @Autowired(required = false)
    private RequestLogService requestLogService;

    @Autowired(required = false)
    private TraceStore traceStore;

    @Autowired(required = false)
    private UserAccountService userAccountService;

    @Autowired(required = false)
    private ClientRateLimiter clientRateLimiter;

    @Autowired(required = false)
    private ClientTpmStore clientTpmStore;

    @Autowired(required = false)
    private ProviderModelCatalogService catalogService;

    @Autowired(required = false)
    private PricingSyncService pricingSyncService;

    @Autowired(required = false)
    private ModelMetadataService metadataService;

    @Autowired(required = false)
    private ConfigAuditService configAuditService;

    @Autowired(required = false)
    private ConfigVersionService configVersionService;

    @Autowired(required = false)
    private ProviderRuntimeStateStore providerRuntimeStateStore;

    @Autowired(required = false)
    private ProviderDiscoveryService providerDiscoveryService;

    @Autowired(required = false)
    private AggregateMetricStore aggregateMetricStore;

    @Autowired(required = false)
    private AggregateReportingService aggregateReportingService;

    private BaselineState baseline;

    public void resetState() {
        if (baseline == null) {
            baseline = BaselineState.capture(gatewayProperties, objectMapper);
        }
        restoreGatewayProperties();
        resetStores();
        resetSnapshots();
        resetCaches();
    }

    private void restoreGatewayProperties() {
        gatewayProperties.setProviders(new LinkedHashMap<>(baseline.providers));
        gatewayProperties.setRoutes(new LinkedHashMap<>(baseline.routes));
        gatewayProperties.setScenes(new LinkedHashMap<>(baseline.scenes));
        gatewayProperties.setClients(new LinkedHashMap<>(baseline.clients));
        gatewayProperties.setLimit(cloneConfig(baseline.limit, LimitConfig.class));
        gatewayProperties.setConcurrentLimit(cloneConfig(baseline.concurrentLimit, ConcurrentLimitConfig.class));
        gatewayProperties.setTracing(cloneConfig(baseline.tracing, TraceConfig.class));
        gatewayProperties.setResilience(cloneConfig(baseline.resilience, ResilienceConfig.class));
        gatewayProperties.setPricing(cloneConfig(baseline.pricing, PricingConfig.class));
        gatewayProperties.setSync(cloneConfig(baseline.sync, SyncConfig.class));
        gatewayProperties.setProviderHealth(cloneConfig(baseline.providerHealth, ProviderHealthConfig.class));
        gatewayProperties.setAuth(cloneConfig(baseline.auth, AuthConfig.class));
        gatewayProperties.setOperational(cloneConfig(baseline.operational, OperationalConfig.class));
    }

    private void resetStores() {
        if (inMemoryConfigStore != null) {
            inMemoryConfigStore.clear();
        }
        if (clientUsageStore instanceof InMemoryClientUsageStore usage) {
            usage.resetForTests();
        }
        if (clientCostStore instanceof InMemoryClientCostStore cost) {
            cost.resetForTests();
        }
    }

    private void resetSnapshots() {
        if (catalogService != null) {
            catalogService.resetForTests();
        }
        if (pricingSyncService != null) {
            pricingSyncService.resetForTests();
        }
        if (metadataService != null) {
            metadataService.resetForTests();
        }
    }

    private void resetCaches() {
        if (clientRateLimiter != null) {
            clientRateLimiter.reset();
        }
        if (clientTpmStore instanceof InMemoryClientTpmStore tpmStore) {
            tpmStore.resetForTests();
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
        if (configAuditService != null) {
            configAuditService.resetForTests();
        }
        if (configVersionService != null) {
            configVersionService.resetForTests();
        }
        if (providerRuntimeStateStore instanceof InMemoryProviderRuntimeStateStore runtime) {
            runtime.resetForTests();
        }
        if (providerDiscoveryService != null) {
            providerDiscoveryService.resetForTests();
        }
        if (aggregateMetricStore instanceof InMemoryAggregateMetricStore metrics) {
            metrics.resetForTests();
        }
        if (aggregateReportingService != null) {
            aggregateReportingService.resetForTests();
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
            ConcurrentLimitConfig concurrentLimit,
            TraceConfig tracing,
            ResilienceConfig resilience,
            PricingConfig pricing,
            SyncConfig sync,
            ProviderHealthConfig providerHealth,
            AuthConfig auth,
            OperationalConfig operational
    ) {
        static BaselineState capture(GatewayProperties props, ObjectMapper om) {
            return new BaselineState(
                    copyMap(props.getProviders(), ProviderConfig.class, om),
                    copyMap(props.getRoutes(), RouteConfig.class, om),
                    copyMap(props.getScenes(), SceneConfig.class, om),
                    copyMap(props.getClients(), ClientConfig.class, om),
                    om.convertValue(props.getLimit(), LimitConfig.class),
                    om.convertValue(props.getConcurrentLimit(), ConcurrentLimitConfig.class),
                    om.convertValue(props.getTracing(), TraceConfig.class),
                    om.convertValue(props.getResilience(), ResilienceConfig.class),
                    om.convertValue(props.getPricing(), PricingConfig.class),
                    om.convertValue(props.getSync(), SyncConfig.class),
                    om.convertValue(props.getProviderHealth(), ProviderHealthConfig.class),
                    om.convertValue(props.getAuth(), AuthConfig.class),
                    om.convertValue(props.getOperational(), OperationalConfig.class)
            );
        }

        private static <T> Map<String, T> copyMap(Map<String, T> source, Class<T> type, ObjectMapper om) {
            Map<String, T> copy = new LinkedHashMap<>();
            source.forEach((k, v) -> copy.put(k, om.convertValue(v, type)));
            return copy;
        }
    }
}
