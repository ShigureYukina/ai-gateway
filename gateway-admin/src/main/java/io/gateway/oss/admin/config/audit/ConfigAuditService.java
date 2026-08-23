package io.gateway.oss.admin.config.audit;

import io.gateway.oss.core.config.ConfigStore;
import io.gateway.oss.core.config.InMemoryConfigStore;
import io.gateway.oss.core.contract.ConfigAuditStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 配置变更审计服务。
 * <p>
 * 职责：记录每次配置变更的审计日志（新增/修改/删除），支持按条件查询。
 * </p>
 * <p>
 * 存储使用 {@link ConfigStore}，configType = "config-audit"，
 * key = "{timestampMillis}-{auditId}"（按时间自然排序）。
 * </p>
 */
@Service
public class ConfigAuditService implements ConfigAuditStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigAuditService.class);
    private static final String CONFIG_TYPE = "config-audit";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_ENTRIES = 1000;

    private final ConcurrentLinkedDeque<AuditEntry> entries = new ConcurrentLinkedDeque<>();
    private final List<Disposable> pendingWrites = new CopyOnWriteArrayList<>();
    private final ConfigStore configStore;
    private final ObjectMapper objectMapper;

    @Autowired
    public ConfigAuditService(ConfigStore configStore, ObjectMapper objectMapper) {
        this.configStore = configStore;
        this.objectMapper = objectMapper;
    }

    public ConfigAuditService() {
        this(new InMemoryConfigStore(), new ObjectMapper());
    }

    @PostConstruct
    public void init() {
        Disposable d = configStore.loadAll(CONFIG_TYPE)
                .map(Map::values)
                .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                .flatMap(this::deserializeEntry)
                .sort(Comparator.comparing(AuditEntry::timestamp, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .take(MAX_ENTRIES)
                .doOnNext(this::addToMemory)
                .doOnComplete(() -> log.info("config_audit_loaded count={}", entries.size()))
                .doOnError(e -> log.warn("config_audit_load_failed reason={}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
        pendingWrites.add(d);
    }

    @PreDestroy
    public void shutdown() {
        if (pendingWrites.isEmpty()) return;
        log.info("waiting_for_pending_writes count={}", pendingWrites.size());
        long deadline = System.currentTimeMillis() + 5000;
        for (Disposable d : pendingWrites) {
            if (!d.isDisposed() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        pendingWrites.removeIf(Disposable::isDisposed);
        if (!pendingWrites.isEmpty()) {
            log.warn("shutdown_with_pending_writes remaining={}", pendingWrites.size());
        }
    }

    /**
     * 异步记录一次配置变更审计日志。失败只 warn 不阻断主流程。
     */
    public Mono<Void> record(String configType, String configKey, String action,
                             String operator, String oldValue, String newValue) {
        String auditId = java.util.UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        AuditEntry entry = new AuditEntry(auditId, timestamp, configType, configKey, action, operator, oldValue, newValue);

        addToMemory(entry);

        String storeKey = timestamp.toEpochMilli() + "-" + auditId;
        return serializeEntry(entry)
                .flatMap(json -> configStore.save(CONFIG_TYPE, storeKey, json))
                .doOnError(e -> log.warn("config_audit_persist_failed config_type={} config_key={} reason={}",
                        configType, configKey, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /**
     * 获取最近 N 条审计条目（默认 100）。
     */
    public Mono<List<AuditEntry>> getRecent(int limit) {
        List<AuditEntry> result = entries.stream().limit(normalizeLimit(limit)).toList();
        return Mono.just(result);
    }

    /**
     * 按条件过滤查询审计条目。
     */
    public Mono<List<AuditEntry>> query(String configType, String configKey, String operator, int limit) {
        int effectiveLimit = normalizeLimit(limit);
        List<AuditEntry> result = entries.stream()
                .filter(e -> configType == null || configType.equals(e.configType()))
                .filter(e -> configKey == null || configKey.equals(e.configKey()))
                .filter(e -> operator == null || operator.equals(e.operator()))
                .limit(effectiveLimit)
                .toList();
        return Mono.just(result);
    }

    private int normalizeLimit(int limit) {
        return limit > 0 ? limit : DEFAULT_LIMIT;
    }

    private void addToMemory(AuditEntry entry) {
        entries.stream()
                .filter(e -> e.auditId().equals(entry.auditId()))
                .findFirst()
                .ifPresent(entries::remove);
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    private Mono<String> serializeEntry(AuditEntry entry) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(entry));
    }

    private Mono<AuditEntry> deserializeEntry(String json) {
        return Mono.fromCallable(() -> objectMapper.readValue(json, AuditEntry.class))
                .doOnError(e -> log.warn("config_audit_deserialize_failed reason={}", e.getMessage()))
                .onErrorResume(JsonProcessingException.class, e -> Mono.empty());
    }

    /**
     * Clear all in-memory audit entries for test isolation.
     */
    public void resetForTests() {
        entries.clear();
    }

    public record AuditEntry(
            String auditId,
            Instant timestamp,
            String configType,
            String configKey,
            String action,
            String operator,
            String oldValue,
            String newValue
    ) {
    }
}
