package io.gateway.oss.admin.quota;

import org.slf4j.Logger;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
     * for daily, one for monthly) inside a single transaction, with timing and
     * error handling.
     *
     * <p>合并是必须的：同一批次内多条记录若命中同一
     * {@code (namespace, client_id, period_key)} 冲突目标，PostgreSQL 会以
     * "ON CONFLICT DO UPDATE command cannot affect row a second time"（21000）
     * 拒绝整条语句。</p>
     *
     * <p>daily 与 monthly 两条 batch 在同一事务内执行：任一失败整体回滚，
     * 已合并的记录重新入队等待下一轮重试，避免静默丢账（审查 D3），也避免
     * "daily 已写入、monthly 失败"的半提交状态在重试时被重复计入。</p>
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
     * @return the number of records successfully flushed（失败已回灌时返回 0，
     *         调用方据此同步 pendingSize）
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

        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    executeBatch(connection, dailySql, dailyArgs);
                    executeBatch(connection, monthlySql, monthlyArgs);
                    connection.commit();
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(previousAutoCommit);
                }
                return null;
            });

            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms > 10) {
                log.info("{}_buffer_flush records={} rows={} durationMs={}", logPrefix, sz, merged.size(), ms);
            }
            return sz;
        } catch (Exception e) {
            // 事务已回滚：合并后的记录整批回灌，下一轮 flush 重试（审查 D3）
            for (T rec : merged.values()) {
                queue.offer(rec);
            }
            log.warn("{}_buffer_flush_failed records={} requeued=true", logPrefix, sz, e);
            return 0;
        }
    }

    private static void executeBatch(Connection connection, String sql, List<Object[]> args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] arg : args) {
                for (int i = 0; i < arg.length; i++) {
                    statement.setObject(i + 1, arg[i]);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
