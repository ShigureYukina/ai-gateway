package io.gateway.oss.admin.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryAggregateMetricStore implements AggregateMetricStore {

    private final ConcurrentHashMap<String, MutableAggregate> daily = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MutableAggregate> monthly = new ConcurrentHashMap<>();

    @Override
    public void record(String dimensionType,
                       String dimensionKey,
                       String displayName,
                       long requests,
                       long tokens,
                       BigDecimal costUsd,
                       Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        YearMonth month = YearMonth.from(day);
        add(daily, key(dimensionType, dimensionKey, day.toString()), dimensionType, dimensionKey, displayName, requests, tokens, costUsd, day.toString());
        add(monthly, key(dimensionType, dimensionKey, month.toString()), dimensionType, dimensionKey, displayName, requests, tokens, costUsd, month.toString());
    }

    @Override
    public List<AggregateMetric> getDaily(String dimensionType, LocalDate day) {
        return collect(daily, dimensionType, day.toString());
    }

    @Override
    public List<AggregateMetric> getMonthly(String dimensionType, YearMonth month) {
        return collect(monthly, dimensionType, month.toString());
    }

    private void add(Map<String, MutableAggregate> store,
                     String key,
                     String dimensionType,
                     String dimensionKey,
                     String displayName,
                     long requests,
                     long tokens,
                     BigDecimal costUsd,
                     String bucket) {
        store.compute(key, (ignored, existing) -> {
            MutableAggregate aggregate = existing == null
                    ? new MutableAggregate(dimensionType, dimensionKey, displayName, bucket)
                    : existing;
            aggregate.requests += requests;
            aggregate.tokens += tokens;
            aggregate.costUsd = aggregate.costUsd.add(costUsd == null ? BigDecimal.ZERO : costUsd);
            if (displayName != null && !displayName.isBlank()) {
                aggregate.displayName = displayName;
            }
            return aggregate;
        });
    }

    private List<AggregateMetric> collect(Map<String, MutableAggregate> store, String dimensionType, String bucket) {
        List<AggregateMetric> result = new ArrayList<>();
        for (MutableAggregate aggregate : store.values()) {
            if (!dimensionType.equals(aggregate.dimensionType) || !bucket.equals(aggregate.bucket)) {
                continue;
            }
            result.add(aggregate.toRecord());
        }
        result.sort(Comparator.comparing(AggregateMetric::dimensionKey));
        return result;
    }

    public void resetForTests() {
        daily.clear();
        monthly.clear();
    }

    private String key(String dimensionType, String dimensionKey, String bucket) {
        return dimensionType + ":" + dimensionKey + ":" + bucket;
    }

    private static final class MutableAggregate {
        private final String dimensionType;
        private final String dimensionKey;
        private final String bucket;
        private String displayName;
        private long requests;
        private long tokens;
        private BigDecimal costUsd = BigDecimal.ZERO;

        private MutableAggregate(String dimensionType, String dimensionKey, String displayName, String bucket) {
            this.dimensionType = dimensionType;
            this.dimensionKey = dimensionKey;
            this.displayName = displayName;
            this.bucket = bucket;
        }

        private AggregateMetric toRecord() {
            return new AggregateMetric(dimensionType, dimensionKey, displayName, requests, tokens, costUsd, bucket);
        }
    }
}
