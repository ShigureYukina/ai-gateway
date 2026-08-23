package io.gateway.oss.core.config;

import io.gateway.oss.core.util.RedisStoreUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PostgresConfigStore implements ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresConfigStore.class);

    private final JdbcTemplate jdbc;
    private final String prefix;
    private final Scheduler scheduler;

    public PostgresConfigStore(JdbcTemplate jdbc,
                               GatewayProperties properties,
                               Scheduler scheduler) {
        this.jdbc = jdbc;
        this.prefix = RedisStoreUtils.safePrefix(properties.getSharedState().getKeyPrefix());
        this.scheduler = scheduler;
    }

    @Override
    public Mono<Void> save(String configType, String key, String jsonValue) {
        long start = System.nanoTime();
        return Mono.fromRunnable(() -> {
            jdbc.update(
                "INSERT INTO config_kv (namespace, config_type, key, value_json) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (namespace, config_type, key) DO UPDATE SET value_json = EXCLUDED.value_json",
                prefix, configType, key, jsonValue
            );
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms > 2) {
                log.info("pg_write_latency point=configStoreSave configType={} durationMs={}", configType, ms);
            }
        }).subscribeOn(scheduler).then();
    }

    @Override
    public Mono<String> load(String configType, String key) {
        return Mono.fromCallable(() -> {
            List<String> results = jdbc.queryForList(
                "SELECT value_json FROM config_kv WHERE namespace = ? AND config_type = ? AND key = ?",
                String.class, prefix, configType, key
            );
            return results.isEmpty() ? null : results.get(0);
        }).subscribeOn(scheduler);
    }

    @Override
    public Mono<Void> delete(String configType, String key) {
        return Mono.fromRunnable(() -> jdbc.update(
            "DELETE FROM config_kv WHERE namespace = ? AND config_type = ? AND key = ?",
            prefix, configType, key
        )).subscribeOn(scheduler).then();
    }

    @Override
    public Mono<Map<String, String>> loadAll(String configType) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT key, value_json FROM config_kv WHERE namespace = ? AND config_type = ?",
                prefix, configType
            );
            if (rows.isEmpty()) return Collections.<String, String>emptyMap();
            Map<String, String> result = new HashMap<>(rows.size());
            for (Map<String, Object> row : rows) {
                result.put((String) row.get("key"), (String) row.get("value_json"));
            }
            return result;
        }).subscribeOn(scheduler);
    }

    @Override
    public Mono<Boolean> saveIfAbsentOrReplaceExpired(String configType, String key, String jsonValue, Duration ttl) {
        return Mono.fromCallable(() -> {
            long nowMillis = System.currentTimeMillis();
            List<Integer> rows = jdbc.query(
                    "INSERT INTO config_kv (namespace, config_type, key, value_json) VALUES (?, ?, ?, ?) " +
                            "ON CONFLICT (namespace, config_type, key) DO UPDATE SET value_json = EXCLUDED.value_json " +
                            "WHERE CAST(COALESCE(config_kv.value_json::jsonb->>'expiresAt', '0') AS BIGINT) <= ? " +
                            "RETURNING 1",
                    (rs, rowNum) -> rs.getInt(1),
                    prefix, configType, key, jsonValue, nowMillis
            );
            return !rows.isEmpty();
        }).subscribeOn(scheduler);
    }
}
