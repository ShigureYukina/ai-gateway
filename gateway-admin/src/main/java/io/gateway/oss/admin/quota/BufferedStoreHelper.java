package io.gateway.oss.admin.quota;

import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BinaryOperator;
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
     * Drains all records from the queue, merges records that map to the same
     * Postgres conflict key, and flushes them in two batch-update calls (one
     * for daily, one for monthly), with timing and error handling.
     *
     * <p>合并是必须的：同一批次内多条记录若命中同一
     * {@code (namespace, client_id, period_key)} 冲突目标，PostgreSQL 会以
     * "ON CONFLICT DO UPDATE command cannot affect row a second time"（21000）
     * 拒绝整条语句，整批记录全部丢失。</p>
     *
     * @param queue              the pending record queue (drained atomically)
     * @param jdbc               the JdbcTemplate to execute batch updates
     * @param dailySql           batch upsert SQL for daily period
     * @param monthlySql         batch upsert SQL for monthly period
     * @param dailyArgBuilder    extracts daily batch parameters from a merged record
     * @param monthlyArgBuilder  extracts monthly batch parameters from a merged record
     * @param mergeKeyFn         maps a record to its conflict-key identity（同键记录被合并）
     * @param mergeFn            merges two records sharing the same conflict key
     * @param log                the store's logger
     * @param logPrefix          short label for log messages (e.g. "usage", "cost")
     * @param <T>                record type
     * @return the number of records drained from the queue（供调用方同步 pendingSize）
     */
    static <T> int flushBatch(ConcurrentLinkedQueue<T> queue,
                              JdbcTemplate jdbc,
                              String dailySql,
                              String monthlySql,
                              Function<T, Object[]> dailyArgBuilder,
                              Function<T, Object[]> monthlyArgBuilder,
                              Function<T, String> mergeKeyFn,
                              BinaryOperator<T> mergeFn,
                              Logger log,
                              String logPrefix) {
        List<T> batch = new ArrayList<>();
        T r;
        while ((r = queue.poll()) != null) {
            batch.add(r);
        }
        if (batch.isEmpty()) return 0;

        int sz = batch.size();
        long start = System.nanoTime();
        try {
            // 按冲突键聚合，保持首次出现顺序；语句内每个冲突目标只出现一次
            Map<String, T> merged = new LinkedHashMap<>(sz * 2);
            for (T rec : batch) {
                merged.merge(mergeKeyFn.apply(rec), rec, mergeFn);
            }

            List<Object[]> dailyArgs = new ArrayList<>(merged.size());
            List<Object[]> monthlyArgs = new ArrayList<>(merged.size());
            for (T rec : merged.values()) {
                dailyArgs.add(dailyArgBuilder.apply(rec));
                monthlyArgs.add(monthlyArgBuilder.apply(rec));
            }
            jdbc.batchUpdate(dailySql, dailyArgs);
            jdbc.batchUpdate(monthlySql, monthlyArgs);

            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms > 10) {
                log.info("{}_buffer_flush records={} rows={} durationMs={}", logPrefix, sz, merged.size(), ms);
            }
        } catch (Exception e) {
            log.warn("{}_buffer_flush_failed records={}", logPrefix, sz, e);
        }
        return sz;
    }
}
