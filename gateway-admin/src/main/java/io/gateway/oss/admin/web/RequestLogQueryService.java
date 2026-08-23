package io.gateway.oss.admin.web;

import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.RequestLogService.RequestLogEntry;
import io.gateway.oss.core.util.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RequestLogQueryService {

    private final RequestLogService requestLogService;

    RequestLogQueryService(RequestLogService requestLogService) {
        this.requestLogService = requestLogService;
    }

    public RequestLogRecentResult recent(int offset,
                                         int limit,
                                         String model,
                                         String client,
                                         Integer status,
                                         Instant from,
                                         Instant to) {
        List<RequestLogEntry> entries;
        if (StringUtils.blankToNull(model) != null) {
            entries = requestLogService.getByModel(model.trim(), 1000);
        } else if (StringUtils.blankToNull(client) != null) {
            entries = requestLogService.getByClient(client.trim(), 1000);
        } else {
            entries = requestLogService.getRecent(1000);
        }
        List<RequestLogEntry> filtered = entries.stream()
                .filter(entry -> status == null || entry.status() == status)
                .filter(entry -> from == null || (entry.timestamp() != null && !entry.timestamp().isBefore(from)))
                .filter(entry -> to == null || (entry.timestamp() != null && entry.timestamp().isBefore(to)))
                .toList();
        List<RequestLogEntryView> paged = filtered.stream()
                .skip(offset)
                .limit(limit)
                .map(this::toView)
                .toList();
        return new RequestLogRecentResult(filtered.size(), offset, paged);
    }

    public ModelCostSummaryResult costByModel(String day) {
        LocalDate resolvedDay = resolveDay(day);
        List<RequestLogEntry> all = requestLogService.getRecent(1000);
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (RequestLogEntry entry : all) {
            if (entry.timestamp() == null || !resolvedDay.equals(entry.timestamp().atZone(ZoneOffset.UTC).toLocalDate())) {
                continue;
            }
            String model = entry.model() == null ? "unknown" : entry.model();
            Aggregate aggregate = aggregates.computeIfAbsent(model, ignored -> new Aggregate());
            aggregate.requests++;
            if (entry.usageTokens() != null) {
                aggregate.totalTokens += entry.usageTokens();
            }
            if (entry.costUsd() != null) {
                aggregate.totalCostUsd += entry.costUsd();
            }
        }
        List<ModelCostEntry> models = new ArrayList<>();
        for (Map.Entry<String, Aggregate> entry : aggregates.entrySet()) {
            Aggregate aggregate = entry.getValue();
            models.add(new ModelCostEntry(entry.getKey(), aggregate.requests, aggregate.totalCostUsd, aggregate.totalTokens));
        }
        models.sort(Comparator.comparing(ModelCostEntry::model));
        return new ModelCostSummaryResult(resolvedDay.toString(), models);
    }

    public ClientCostSummaryResult costByClient(String client, String fromStr, String toStr) {
        String resolvedClient = StringUtils.blankToNull(client);
        if (resolvedClient == null) {
            return new ClientCostSummaryResult(null, null, List.of());
        }
        Instant from = parseDateParam(fromStr);
        Instant to = parseDateParam(toStr);
        List<RequestLogEntry> entries = requestLogService.getByClientFiltered(resolvedClient, from, to, 5000);
        Map<String, ClientModelAggregate> aggregates = new LinkedHashMap<>();
        for (RequestLogEntry entry : entries) {
            String model = entry.model() == null ? "unknown" : entry.model();
            ClientModelAggregate aggregate = aggregates.computeIfAbsent(model, ignored -> new ClientModelAggregate());
            aggregate.requests++;
            if (entry.usageTokens() != null) {
                aggregate.totalTokens += entry.usageTokens();
            }
            if (entry.promptTokens() != null) {
                aggregate.promptTokens += entry.promptTokens();
            }
            if (entry.completionTokens() != null) {
                aggregate.completionTokens += entry.completionTokens();
            }
            if (entry.costUsd() != null) {
                aggregate.totalCostUsd += entry.costUsd();
            }
        }
        List<ClientModelEntry> models = new ArrayList<>();
        for (Map.Entry<String, ClientModelAggregate> entry : aggregates.entrySet()) {
            ClientModelAggregate aggregate = entry.getValue();
            models.add(new ClientModelEntry(entry.getKey(), aggregate.requests, aggregate.totalCostUsd,
                    aggregate.totalTokens, aggregate.promptTokens, aggregate.completionTokens));
        }
        models.sort(Comparator.comparing(ClientModelEntry::model));
        return new ClientCostSummaryResult(from, to, models);
    }

    public RequestLogEntryView toView(RequestLogEntry entry) {
        return new RequestLogEntryView(
                entry.requestId(),
                entry.clientId(),
                entry.model(),
                entry.provider(),
                entry.routeId(),
                entry.scene(),
                entry.status(),
                entry.latencyMs(),
                entry.timestamp(),
                entry.streamMode(),
                entry.usageTokens(),
                entry.promptTokens(),
                entry.completionTokens(),
                entry.costUsd(),
                entry.errorMessage()
        );
    }

    private LocalDate resolveDay(String day) {
        String trimmed = StringUtils.blankToNull(day);
        if (trimmed == null) {
            return LocalDate.now(ZoneOffset.UTC);
        }
        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format: " + trimmed, e);
        }
    }

    private Instant parseDateParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format: " + value, e);
        }
    }

    private static final class Aggregate {
        long requests;
        double totalCostUsd;
        long totalTokens;
    }

    private static final class ClientModelAggregate {
        long requests;
        double totalCostUsd;
        long totalTokens;
        long promptTokens;
        long completionTokens;
    }

    public record RequestLogEntryView(
            String requestId,
            String clientId,
            String model,
            String provider,
            String routeId,
            String scene,
            int status,
            long latencyMs,
            Instant timestamp,
            String streamMode,
            Long usageTokens,
            Long promptTokens,
            Long completionTokens,
            Double costUsd,
            String errorMessage
    ) {}

    public record RequestLogRecentResult(int total, int offset, List<RequestLogEntryView> requests) {}

    public record ModelCostSummaryResult(String day, List<ModelCostEntry> models) {}

    public record ModelCostEntry(String model, long requests, Double totalCostUsd, long totalTokens) {}

    public record ClientCostSummaryResult(Instant from, Instant to, List<ClientModelEntry> models) {}

    public record ClientModelEntry(String model,
                                   long requests,
                                   Double totalCostUsd,
                                   long totalTokens,
                                   long promptTokens,
                                   long completionTokens) {}
}
