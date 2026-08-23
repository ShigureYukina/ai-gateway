package io.gateway.oss.admin.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public interface AggregateMetricStore {

    void record(String dimensionType,
                String dimensionKey,
                String displayName,
                long requests,
                long tokens,
                BigDecimal costUsd,
                Instant now);

    default void recordAll(List<DimensionRecord> records, Instant now) {
        for (var r : records) {
            record(r.dimensionType(), r.dimensionKey(), r.displayName(),
                    r.requests(), r.tokens(), r.costUsd(), now);
        }
    }

    List<AggregateMetric> getDaily(String dimensionType, LocalDate day);

    List<AggregateMetric> getMonthly(String dimensionType, YearMonth month);

    record AggregateMetric(
            String dimensionType,
            String dimensionKey,
            String displayName,
            long requests,
            long tokens,
            BigDecimal costUsd,
            String bucket
    ) {
    }

    record DimensionRecord(
            String dimensionType,
            String dimensionKey,
            String displayName,
            long requests,
            long tokens,
            BigDecimal costUsd
    ) {
    }
}
