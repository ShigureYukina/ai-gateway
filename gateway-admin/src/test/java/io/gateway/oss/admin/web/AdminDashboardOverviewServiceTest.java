package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.OperationalConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.admin.limit.InMemoryClientTpmStore;
import io.gateway.oss.admin.observability.AggregateReportingService;
import io.gateway.oss.admin.observability.InMemoryAggregateMetricStore;
import io.gateway.oss.admin.quota.InMemoryClientCostStore;
import io.gateway.oss.admin.quota.InMemoryClientUsageStore;
import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.core.upstream.InMemoryProviderRuntimeStateStore;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import io.gateway.oss.core.web.OperationalGateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminDashboardOverviewServiceTest {

    @Test
    void shouldAggregateOverviewStatusTpmRuntimeAndDiscovery() {
        GatewayProperties properties = new GatewayProperties();
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.getLimits().setTokensPerMinute(1000L);
        properties.setClients(Map.of("demo-client-key", clientConfig));

        RouteConfig route = new RouteConfig();
        route.setProvider("openai");
        route.setEnabled(true);
        properties.setRoutes(Map.of("openai-primary", route));

        OperationalConfig operationalConfig = new OperationalConfig();
        operationalConfig.setMaintenanceMode(false);
        OperationalConfig.EmergencyRateLimit emergencyRateLimit = new OperationalConfig.EmergencyRateLimit();
        emergencyRateLimit.setEnabled(true);
        emergencyRateLimit.setMaxRequestsPerMinute(50);
        operationalConfig.setEmergencyRateLimit(emergencyRateLimit);
        properties.setOperational(operationalConfig);

        InMemoryClientUsageStore usageStore = new InMemoryClientUsageStore();
        InMemoryClientCostStore costStore = new InMemoryClientCostStore();
        InMemoryClientTpmStore tpmStore = new InMemoryClientTpmStore();
        InMemoryAggregateMetricStore aggregateStore = new InMemoryAggregateMetricStore();
        AggregateReportingService reportingService = new AggregateReportingService(aggregateStore);
        InMemoryProviderRuntimeStateStore runtimeStateStore = new InMemoryProviderRuntimeStateStore();
        ProviderDiscoveryService discoveryService = new ProviderDiscoveryService();
        OperationalGateService operationalGateService = new OperationalGateService(properties);

        LocalDate day = LocalDate.of(2026, 5, 25);
        Instant instant = day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        usageStore.addDailyUsage("demo-client-key", 64000L, instant);
        usageStore.addDailyRequestCount("demo-client-key", instant);
        usageStore.addDailyRequestCount("demo-client-key", instant);
        costStore.addDailyCost("demo-client-key", new BigDecimal("12.34"), instant);
        aggregateStore.record("status", "2xx", "2xx", 1, 32000, new BigDecimal("6.17"), instant);
        aggregateStore.record("status", "4xx", "4xx", 1, 0, BigDecimal.ZERO, instant);
        aggregateStore.record("model", "gpt-4o-mini", "gpt-4o-mini", 2, 64000, new BigDecimal("12.34"), instant);
        tpmStore.reserve("demo-client-key", 320L, 1000L, Instant.now());

        runtimeStateStore.save("openai", new ProviderRuntimeStateStore.ProviderRuntimeState(
                true, instant, instant, 0, 8, 200, 120L, null
        ));
        discoveryService.updateProvider("openai", new ProviderDiscoveryService.ProviderDiscovery(
                "runtime", "success", instant, java.util.List.of("gpt-4o-mini"), null
        ));

        AdminDashboardOverviewService service = new AdminDashboardOverviewService(
                operationalGateService,
                properties,
                runtimeStateStore,
                usageStore,
                costStore,
                reportingService,
                tpmStore,
                discoveryService
        );

        var response = service.buildOverview(day);

        assertEquals(day.toString(), response.day());
        assertEquals(2L, response.overview().totalRequests());
        assertEquals(64000L, response.overview().totalTokens());
        assertEquals(new BigDecimal("12.340000"), response.overview().totalCost().setScale(6));
        assertEquals(1L, response.overview().success2xx());
        assertEquals(1L, response.overview().status4xx());
        assertEquals(0L, response.overview().status5xx());
        assertEquals(1, response.overview().topModels().size());
        assertEquals("gpt-4o-mini", response.overview().topModels().getFirst().model());
        assertEquals(1, response.tpmOverview().clients().size());
        assertTrue(response.tpmOverview().clients().getFirst().usedTokens() >= 0L);
        assertTrue(response.systemStatus().hasAvailableRoute());
        assertFalse(response.systemStatus().maintenanceActive());
        assertTrue(response.systemStatus().emergencyRateLimitEnabled());
        assertEquals(50, response.systemStatus().emergencyRateLimitMaxRequestsPerMinute());
        assertTrue(response.runtime().providers().containsKey("openai"));
        assertTrue(response.discovery().providers().containsKey("openai"));
    }
}
