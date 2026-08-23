package io.gateway.oss.admin.dto;

import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminDashboardOverviewResponseTest {

    @Test
    void constructorShouldSetAllFieldsAndNestedRecordsShouldExposeValues() {
        Instant now = Instant.parse("2026-06-04T10:15:30Z");
        AdminDashboardOverviewResponse.SystemStatus systemStatus = new AdminDashboardOverviewResponse.SystemStatus(
                now, true, false, 100, 20, true
        );
        AdminDashboardOverviewResponse.ModelUsageEntry modelUsageEntry =
                new AdminDashboardOverviewResponse.ModelUsageEntry("gpt-4o-mini", 50L, 5000L, new BigDecimal("12.34"));
        AdminDashboardOverviewResponse.ClientSpendEntry clientSpendEntry =
                new AdminDashboardOverviewResponse.ClientSpendEntry("client-a", 30L, 3000L, new BigDecimal("8.90"));
        AdminDashboardOverviewResponse.DashboardOverview overview = new AdminDashboardOverviewResponse.DashboardOverview(
                100L,
                10000L,
                new BigDecimal("21.24"),
                0.98,
                12L,
                20,
                90L,
                7L,
                3L,
                List.of(modelUsageEntry),
                List.of(clientSpendEntry)
        );
        AdminDashboardOverviewResponse.TpmClientEntry tpmClientEntry =
                new AdminDashboardOverviewResponse.TpmClientEntry("client-a", 1000L, 5000L, 4000L, 20.0, now);
        AdminDashboardOverviewResponse.TpmOverview tpmOverview =
                new AdminDashboardOverviewResponse.TpmOverview(now, List.of(tpmClientEntry));
        ProviderRuntimeStateStore.ProviderRuntimeState providerState =
                new ProviderRuntimeStateStore.ProviderRuntimeState(true, now, now, 0, 3, 200, 150L, null);
        AdminDashboardOverviewResponse.ProviderRuntime runtime =
                new AdminDashboardOverviewResponse.ProviderRuntime(now, Map.of("openai", providerState));
        ProviderDiscoveryService.ProviderDiscovery discoveryEntry =
                new ProviderDiscoveryService.ProviderDiscovery("manual", "success", now, List.of("gpt-4o-mini"), null);
        ProviderDiscoveryService.DiscoverySnapshot discovery =
                new ProviderDiscoveryService.DiscoverySnapshot(Map.of("openai", discoveryEntry), now, 1L);

        AdminDashboardOverviewResponse response = new AdminDashboardOverviewResponse(
                now,
                "2026-06-04",
                systemStatus,
                overview,
                tpmOverview,
                runtime,
                discovery
        );

        assertEquals(now, response.generatedAt());
        assertEquals("2026-06-04", response.day());
        assertEquals(systemStatus, response.systemStatus());
        assertEquals(overview, response.overview());
        assertEquals(tpmOverview, response.tpmOverview());
        assertEquals(runtime, response.runtime());
        assertEquals(discovery, response.discovery());

        assertEquals(now, systemStatus.generatedAt());
        assertEquals(true, systemStatus.maintenanceActive());
        assertEquals(false, systemStatus.emergencyRateLimitEnabled());
        assertEquals(100, systemStatus.emergencyRateLimitMaxRequestsPerMinute());
        assertEquals(20, systemStatus.emergencyRateLimitCurrentWindowCount());
        assertEquals(true, systemStatus.hasAvailableRoute());

        assertEquals(100L, overview.totalRequests());
        assertEquals(10000L, overview.totalTokens());
        assertEquals(new BigDecimal("21.24"), overview.totalCost());
        assertEquals(0.98, overview.successRate());
        assertEquals(12L, overview.activeClients());
        assertEquals(20, overview.registeredClients());
        assertEquals(90L, overview.success2xx());
        assertEquals(7L, overview.status4xx());
        assertEquals(3L, overview.status5xx());
        assertEquals(List.of(modelUsageEntry), overview.topModels());
        assertEquals(List.of(clientSpendEntry), overview.topClients());

        assertEquals("gpt-4o-mini", modelUsageEntry.model());
        assertEquals(50L, modelUsageEntry.requests());
        assertEquals(5000L, modelUsageEntry.tokens());
        assertEquals(new BigDecimal("12.34"), modelUsageEntry.cost());

        assertEquals("client-a", clientSpendEntry.client());
        assertEquals(30L, clientSpendEntry.requests());
        assertEquals(3000L, clientSpendEntry.tokens());
        assertEquals(new BigDecimal("8.90"), clientSpendEntry.cost());

        assertEquals(now, tpmOverview.windowStartedAt());
        assertEquals(List.of(tpmClientEntry), tpmOverview.clients());

        assertEquals("client-a", tpmClientEntry.client());
        assertEquals(1000L, tpmClientEntry.usedTokens());
        assertEquals(5000L, tpmClientEntry.limitTokens());
        assertEquals(4000L, tpmClientEntry.remainingTokens());
        assertEquals(20.0, tpmClientEntry.utilizationPercent());
        assertEquals(now, tpmClientEntry.windowStartedAt());

        assertEquals(now, runtime.generatedAt());
        assertEquals(Map.of("openai", providerState), runtime.providers());
    }
}
