package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.ClientConfigView;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.web.OperationalGateService;
import io.gateway.oss.admin.dto.AdminDashboardOverviewResponse;
import io.gateway.oss.admin.limit.ClientTpmStore;
import io.gateway.oss.admin.observability.AggregateMetricStore;
import io.gateway.oss.admin.observability.AggregateReportingService;
import io.gateway.oss.admin.quota.ClientCostStore;
import io.gateway.oss.admin.quota.ClientUsageStore;
import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminDashboardOverviewService {

    private final OperationalGateService operationalGateService;
    private final GatewayConfigView configView;
    private final ProviderRuntimeStateStore providerRuntimeStateStore;
    private final ClientUsageStore usageStore;
    private final ClientCostStore costStore;
    private final AggregateReportingService aggregateReportingService;
    private final ClientTpmStore clientTpmStore;
    private final ProviderDiscoveryService providerDiscoveryService;

    public AdminDashboardOverviewService(OperationalGateService operationalGateService,
                                         GatewayConfigView configView,
                                         ProviderRuntimeStateStore providerRuntimeStateStore,
                                         ClientUsageStore usageStore,
                                         ClientCostStore costStore,
                                         AggregateReportingService aggregateReportingService,
                                         ClientTpmStore clientTpmStore,
                                         ProviderDiscoveryService providerDiscoveryService) {
        this.operationalGateService = operationalGateService;
        this.configView = configView;
        this.providerRuntimeStateStore = providerRuntimeStateStore;
        this.usageStore = usageStore;
        this.costStore = costStore;
        this.aggregateReportingService = aggregateReportingService;
        this.clientTpmStore = clientTpmStore;
        this.providerDiscoveryService = providerDiscoveryService;
    }

    public AdminDashboardOverviewResponse buildOverview(LocalDate day) {
        Instant dayInstant = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant now = Instant.now();
        List<String> clientIds = resolveClientIds();

        // Batch fetch all client metrics — 3 queries total (was: 3*C queries per loop, ×2 loops)
        Map<String, Long> clientRequests = usageStore.batchDailyRequestCount(clientIds, dayInstant);
        Map<String, Long> clientTokens = usageStore.batchDailyUsage(clientIds, dayInstant);
        Map<String, BigDecimal> clientCosts = costStore.batchDailyCost(clientIds, dayInstant);

        long totalRequests = 0L;
        long totalTokens = 0L;
        BigDecimal totalCost = BigDecimal.ZERO;
        long activeClients = 0L;
        for (String clientId : clientIds) {
            long requests = clientRequests.getOrDefault(clientId, 0L);
            totalRequests += requests;
            totalTokens += clientTokens.getOrDefault(clientId, 0L);
            totalCost = totalCost.add(clientCosts.getOrDefault(clientId, BigDecimal.ZERO));
            if (requests > 0) {
                activeClients++;
            }
        }

        Map<String, AggregateMetricSnapshot> statusAggregates = indexByKey(aggregateReportingService.statuses("day", day.toString()).items());
        long success2xx = statusAggregates.getOrDefault("2xx", AggregateMetricSnapshot.ZERO).requests();
        long status4xx = statusAggregates.getOrDefault("4xx", AggregateMetricSnapshot.ZERO).requests();
        long status5xx = statusAggregates.getOrDefault("5xx", AggregateMetricSnapshot.ZERO).requests();
        // successRate 分子和分母使用同一数据源（AggregateMetricStore），避免与 ClientUsageStore 口径偏差
        long totalRequestsFromAgg = success2xx + status4xx + status5xx;
        double successRate = totalRequestsFromAgg > 0 ? (success2xx * 100.0d) / totalRequestsFromAgg : 0.0d;

        List<AdminDashboardOverviewResponse.ModelUsageEntry> topModels = aggregateReportingService.models("day", day.toString()).items().stream()
                .map(entry -> new AdminDashboardOverviewResponse.ModelUsageEntry(entry.dimensionKey(), entry.requests(), entry.tokens(), entry.costUsd()))
                .sorted(Comparator.comparingLong(AdminDashboardOverviewResponse.ModelUsageEntry::requests).reversed().thenComparing(AdminDashboardOverviewResponse.ModelUsageEntry::model))
                .limit(10)
                .toList();

        List<AdminDashboardOverviewResponse.ClientSpendEntry> topClients = clientIds.stream()
                .map(clientId -> new AdminDashboardOverviewResponse.ClientSpendEntry(
                        clientId,
                        clientRequests.getOrDefault(clientId, 0L),
                        clientTokens.getOrDefault(clientId, 0L),
                        clientCosts.getOrDefault(clientId, BigDecimal.ZERO)))
                .filter(entry -> entry.requests() > 0 || entry.tokens() > 0 || entry.cost().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(AdminDashboardOverviewResponse.ClientSpendEntry::cost).reversed().thenComparing(AdminDashboardOverviewResponse.ClientSpendEntry::client))
                .limit(5)
                .toList();

        // Batch fetch TPM data — 1 query (was: C queries)
        Map<String, Long> tpmUsage = clientTpmStore.batchCurrentMinuteUsage(clientIds, now);
        List<AdminDashboardOverviewResponse.TpmClientEntry> tpmClients = clientIds.stream()
                .map(clientId -> toTpmClientEntry(clientId, tpmUsage.getOrDefault(clientId, 0L)))
                .filter(entry -> entry != null)
                .sorted(Comparator.comparingLong(AdminDashboardOverviewResponse.TpmClientEntry::usedTokens).reversed().thenComparing(AdminDashboardOverviewResponse.TpmClientEntry::client))
                .toList();

        var gateState = operationalGateService.snapshot();
        return new AdminDashboardOverviewResponse(
                now,
                day.toString(),
                new AdminDashboardOverviewResponse.SystemStatus(
                        now,
                        gateState.maintenanceMode(),
                        gateState.emergencyRateLimitEnabled(),
                        gateState.emergencyMaxRequestsPerMinute(),
                        gateState.emergencyWindowCount(),
                        hasAnyAvailableRoute()
                ),
                new AdminDashboardOverviewResponse.DashboardOverview(
                        totalRequests,
                        totalTokens,
                        totalCost,
                        successRate,
                        activeClients,
                        configView.getClients().size(),
                        success2xx,
                        status4xx,
                        status5xx,
                        topModels,
                        topClients
                ),
                new AdminDashboardOverviewResponse.TpmOverview(truncateToMinute(now), tpmClients),
                new AdminDashboardOverviewResponse.ProviderRuntime(now, providerRuntimeStateStore.getAll()),
                providerDiscoveryService.getSnapshot()
        );
    }

    private boolean hasAnyAvailableRoute() {
        Map<String, ? extends RouteConfigView> routes = configView.getRoutes();
        if (routes == null || routes.isEmpty()) {
            return false;
        }
        for (var route : routes.entrySet()) {
            if (!route.getValue().isEnabled()) continue;
            var providerName = route.getValue().getProvider();
            if (providerName == null) continue;
            var state = providerRuntimeStateStore.get(providerName);
            if (state.runtimeAvailable()) {
                return true;
            }
        }
        return false;
    }

    private List<String> resolveClientIds() {
        Map<String, ? extends ClientConfigView> clients = configView.getClients();
        if (clients == null || clients.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(clients.keySet());
    }

    private AdminDashboardOverviewResponse.TpmClientEntry toTpmClientEntry(String clientId, long used) {
        ClientConfigView clientConfig = configView.getClients().get(clientId);
        if (clientConfig == null || clientConfig.getLimits() == null || clientConfig.getLimits().getTokensPerMinute() == null) {
            return null;
        }
        long limit = clientConfig.getLimits().getTokensPerMinute();
        long remaining = Math.max(0L, limit - used);
        double utilization = limit > 0 ? (used * 100.0d) / limit : 0.0d;
        return new AdminDashboardOverviewResponse.TpmClientEntry(clientId, used, limit, remaining, utilization, truncateToMinute(Instant.now()));
    }

    private Instant truncateToMinute(Instant instant) {
        return instant.atZone(ZoneOffset.UTC)
                .withSecond(0)
                .withNano(0)
                .toInstant();
    }

    private Map<String, AggregateMetricSnapshot> indexByKey(List<AggregateMetricStore.AggregateMetric> items) {
        Map<String, AggregateMetricSnapshot> index = new LinkedHashMap<>();
        for (AggregateMetricStore.AggregateMetric item : items) {
            index.put(item.dimensionKey(), new AggregateMetricSnapshot(item.requests(), item.tokens(), item.costUsd()));
        }
        return index;
    }

    private record AggregateMetricSnapshot(long requests, long tokens, BigDecimal cost) {
        private static final AggregateMetricSnapshot ZERO = new AggregateMetricSnapshot(0L, 0L, BigDecimal.ZERO);
    }
}
