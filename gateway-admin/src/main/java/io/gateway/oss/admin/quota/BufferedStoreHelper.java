package io.gateway.oss.admin.quota;

import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Shared helper for buffered stores to eliminate copy-paste
 * duplication between {@link BufferedClientUsageStore} and
 * {@link BufferedClientCostStore}.
 */
final class BufferedStoreHelper {

    private BufferedStoreHelper() {
    }

    /**
     * Drains all records from the queue and flushes them to Postgres in two
     * batch-update calls (one for daily, one for monthly), with timing and
     * error handling.
     *
     * @param queue              the pending record queue (drained atomically)
     * @param jdbc               the JdbcTemplate to execute batch updates
     * @param dailySql           batch upsert SQL for daily period
     * @param monthlySql         batch upsert SQL for monthly period
     * @param dailyArgBuilder    extracts daily batch parameters from a record
     * @param monthlyArgBuilder  extracts monthly batch parameters from a record
     * @param log                the store's logger
     * @param logPrefix          short label for log messages (e.g. "usage", "cost")
     * @param <T>                record type
     */
    static <T> void flushBatch(ConcurrentLinkedQueue<T> queue,
                               JdbcTemplate jdbc,
                               String dailySql,
                               String monthlySql,
                               Function<T, Object[]> dailyArgBuilder,
                               Function<T, Object[]> monthlyArgBuilder,
                               Logger log,
                               String logPrefix) {
        List<T> batch = new ArrayList<>();
        T r;
        while ((r = queue.poll()) != null) {
            batch.add(r);
        }
        if (batch.isEmpty()) return;

        int sz = batch.size();
        long start = System.nanoTime();
        try {
            List<Object[]> dailyArgs = new ArrayList<>(sz);
            List<Object[]> monthlyArgs = new ArrayList<>(sz);
            for (T rec : batch) {
                dailyArgs.add(dailyArgBuilder.apply(rec));
                monthlyArgs.add(monthlyArgBuilder.apply(rec));
            }
            jdbc.batchUpdate(dailySql, dailyArgs);
            jdbc.batchUpdate(monthlySql, monthlyArgs);

            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms > 10) {
                log.info("{}_buffer_flush records={} durationMs={}", logPrefix, sz, ms);
            }
        } catch (Exception e) {
            log.warn("{}_buffer_flush_failed records={}", logPrefix, sz, e);
        }
    }
}
