package io.gateway.oss.admin.dto;

import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AdminDashboardOverviewResponse(
        Instant generatedAt,
        String day,
        SystemStatus systemStatus,
        DashboardOverview overview,
        TpmOverview tpmOverview,
        ProviderRuntime runtime,
        ProviderDiscoveryService.DiscoverySnapshot discovery
) {

    public record SystemStatus(
            Instant generatedAt,
            boolean maintenanceActive,
            boolean emergencyRateLimitEnabled,
            int emergencyRateLimitMaxRequestsPerMinute,
            int emergencyRateLimitCurrentWindowCount,
            boolean hasAvailableRoute
    ) {
    }

    public record DashboardOverview(
            long totalRequests,
            long totalTokens,
            BigDecimal totalCost,
            double successRate,
            long activeClients,
            int registeredClients,
            long success2xx,
            long status4xx,
            long status5xx,
            List<ModelUsageEntry> topModels,
            List<ClientSpendEntry> topClients
    ) {
    }

    public record ModelUsageEntry(
            String model,
            long requests,
            long tokens,
            BigDecimal cost
    ) {
    }

    public record ClientSpendEntry(
            String client,
            long requests,
            long tokens,
            BigDecimal cost
    ) {
    }

    public record TpmOverview(
            Instant windowStartedAt,
            List<TpmClientEntry> clients
    ) {
    }

    public record TpmClientEntry(
            String client,
            long usedTokens,
            long limitTokens,
            long remainingTokens,
            double utilizationPercent,
            Instant windowStartedAt
    ) {
    }

    public record ProviderRuntime(
            Instant generatedAt,
            Map<String, ProviderRuntimeStateStore.ProviderRuntimeState> providers
    ) {
    }
}
