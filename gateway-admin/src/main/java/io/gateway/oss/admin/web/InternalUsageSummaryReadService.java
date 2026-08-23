package io.gateway.oss.admin.web;

import io.gateway.oss.admin.limit.ClientTpmStore;
import io.gateway.oss.admin.observability.AggregateMetricStore;
import io.gateway.oss.admin.observability.AggregateReportingService;
import io.gateway.oss.admin.quota.ClientCostStore;
import io.gateway.oss.admin.quota.ClientUsageStore;
import io.gateway.oss.core.contract.ClientConfigView;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * internal usage 汇总只读协作者，负责聚合与响应组装。
 */
public class InternalUsageSummaryReadService {

    private final ClientUsageStore usageStore;
    private final ClientCostStore costStore;
    private final AggregateReportingService aggregateReportingService;
    private final ClientTpmStore clientTpmStore;
    private final GatewayConfigView configView;

    public InternalUsageSummaryReadService(ClientUsageStore usageStore,
                                           ClientCostStore costStore,
                                           AggregateReportingService aggregateReportingService,
                                           ClientTpmStore clientTpmStore,
                                           GatewayConfigView configView) {
        this.usageStore = usageStore;
        this.costStore = costStore;
        this.aggregateReportingService = aggregateReportingService;
        this.clientTpmStore = clientTpmStore;
        this.configView = configView;
    }

    public InternalUsageSummaryController.UsageSummaryResponse usageSummary(String client, LocalDate day) {
        Instant dayInstant = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<String> clientIds = resolveClientIds(client);
        Map<String, Long> usage = usageStore.batchDailyUsage(clientIds, dayInstant);
        Map<String, Long> requestCounts = usageStore.batchDailyRequestCount(clientIds, dayInstant);
        List<InternalUsageSummaryController.UsageEntry> entries = clientIds.stream()
                .map(clientId -> new InternalUsageSummaryController.UsageEntry(
                        clientId,
                        usage.getOrDefault(clientId, 0L),
                        requestCounts.getOrDefault(clientId, 0L)))
                .toList();
        return new InternalUsageSummaryController.UsageSummaryResponse(Instant.now(), day.toString(), entries);
    }

    public InternalUsageSummaryController.CostSummaryResponse costSummary(String client, LocalDate day) {
        Instant dayInstant = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<String> clientIds = resolveClientIds(client);
        Map<String, BigDecimal> costs = costStore.batchDailyCost(clientIds, dayInstant);
        List<InternalUsageSummaryController.CostEntry> entries = clientIds.stream()
                .map(clientId -> new InternalUsageSummaryController.CostEntry(
                        clientId,
                        costs.getOrDefault(clientId, BigDecimal.ZERO)))
                .toList();
        return new InternalUsageSummaryController.CostSummaryResponse(Instant.now(), day.toString(), entries);
    }

    public InternalUsageSummaryController.DashboardOverviewResponse dashboardOverview(LocalDate day) {
        Instant dayInstant = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<String> clientIds = resolveClientIds(null);

        // Batch fetch all client metrics — 3 queries (was: 3*C queries)
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
        // successRate 分子和分母继续基于 AggregateMetricStore 聚合结果，避免与 usage store 口径偏差。
        long totalRequestsFromAgg = success2xx + status4xx + status5xx;
        double successRate = totalRequestsFromAgg > 0 ? (success2xx * 100.0d) / totalRequestsFromAgg : 0.0d;

        List<InternalUsageSummaryController.ModelUsageEntry> topModels = aggregateReportingService.models("day", day.toString()).items().stream()
                .map(entry -> new InternalUsageSummaryController.ModelUsageEntry(entry.dimensionKey(), entry.requests(), entry.tokens(), entry.costUsd()))
                .sorted(Comparator.comparingLong(InternalUsageSummaryController.ModelUsageEntry::requests)
                        .reversed()
                        .thenComparing(InternalUsageSummaryController.ModelUsageEntry::model))
                .limit(10)
                .toList();

        List<String> topClientIds = clientIds.stream()
                .filter(clientId -> {
                    long r = clientRequests.getOrDefault(clientId, 0L);
                    long t = clientTokens.getOrDefault(clientId, 0L);
                    BigDecimal c = clientCosts.getOrDefault(clientId, BigDecimal.ZERO);
                    return r > 0 || t > 0 || c.compareTo(BigDecimal.ZERO) > 0;
                })
                .sorted(Comparator.<String, BigDecimal>comparing(cid -> clientCosts.getOrDefault(cid, BigDecimal.ZERO))
                        .reversed()
                        .thenComparing(cid -> cid))
                .limit(5)
                .toList();
        List<InternalUsageSummaryController.ClientSpendEntry> topClients = topClientIds.stream()
                .map(clientId -> new InternalUsageSummaryController.ClientSpendEntry(
                        clientId,
                        clientRequests.getOrDefault(clientId, 0L),
                        clientTokens.getOrDefault(clientId, 0L),
                        clientCosts.getOrDefault(clientId, BigDecimal.ZERO)))
                .toList();

        return new InternalUsageSummaryController.DashboardOverviewResponse(
                Instant.now(),
                day.toString(),
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
        );
    }

    public InternalUsageSummaryController.TpmOverviewResponse tpmOverview(String client, Instant now) {
        List<String> clientIds = resolveClientIds(client);
        Map<String, Long> tpmUsage = clientTpmStore.batchCurrentMinuteUsage(clientIds, now);
        List<InternalUsageSummaryController.TpmClientEntry> clients = clientIds.stream()
                .map(clientId -> toTpmClientEntry(clientId, tpmUsage.getOrDefault(clientId, 0L), now))
                .filter(entry -> entry != null)
                .sorted(Comparator.comparingLong(InternalUsageSummaryController.TpmClientEntry::usedTokens)
                        .reversed()
                        .thenComparing(InternalUsageSummaryController.TpmClientEntry::client))
                .toList();
        return new InternalUsageSummaryController.TpmOverviewResponse(Instant.now(), truncateToMinute(now), clients);
    }

    private List<String> resolveClientIds(String client) {
        String trimmed = StringUtils.blankToNull(client);
        if (trimmed != null) {
            return List.of(trimmed);
        }
        Map<String, ? extends ClientConfigView> clients = configView.getClients();
        if (clients == null || clients.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(clients.keySet());
    }

    private InternalUsageSummaryController.TpmClientEntry toTpmClientEntry(String clientId, long used, Instant now) {
        ClientConfigView clientConfig = configView.getClients().get(clientId);
        if (clientConfig == null || clientConfig.getLimits() == null || clientConfig.getLimits().getTokensPerMinute() == null) {
            return null;
        }
        long limit = clientConfig.getLimits().getTokensPerMinute();
        long remaining = Math.max(0L, limit - used);
        double utilization = limit > 0 ? (used * 100.0d) / limit : 0.0d;
        return new InternalUsageSummaryController.TpmClientEntry(clientId, used, limit, remaining, utilization, truncateToMinute(now));
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
