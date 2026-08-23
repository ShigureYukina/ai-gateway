package io.gateway.oss.admin.config.audit;

import io.gateway.oss.core.config.ConfigStore;
import io.gateway.oss.core.config.InMemoryConfigStore;
import io.gateway.oss.core.contract.ConfigVersionStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置版本管理服务。
 * <p>
 * 职责：在配置变更前保存旧版本快照，支持查询历史版本和回滚。
 * </p>
 * <p>
 * 存储使用 {@link ConfigStore}，configType = "config-versions"，
 * key = "{configType}:{configKey}:v{versionNumber}"（如 providers:openai:v1）。
 * 同时维护一个内存计数器追踪每个配置项的最新版本号。
 * </p>
 */
@Service
public class ConfigVersionService implements ConfigVersionStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigVersionService.class);
    private static final String CONFIG_TYPE = "config-versions";

    /**
     * 内存版本计数器，key = "{configType}:{configKey}"，value = 最新版本号。
     */
    private final ConcurrentHashMap<String, Integer> versionCounters = new ConcurrentHashMap<>();

    private final ConfigStore configStore;
    private final ObjectMapper objectMapper;

    @Autowired
    public ConfigVersionService(ConfigStore configStore, ObjectMapper objectMapper) {
        this.configStore = configStore;
        this.objectMapper = objectMapper;
    }

    public ConfigVersionService() {
        this(new InMemoryConfigStore(), new ObjectMapper());
    }

    /**
     * 启动时从 ConfigStore 加载所有版本 key，恢复版本计数器。
     */
    @PostConstruct
    public void init() {
        configStore.loadAll(CONFIG_TYPE)
                .map(Map::keySet)
                .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                .doOnNext(this::updateCounterFromKey)
                .doOnComplete(() -> log.info("config_versions_loaded counters={}", versionCounters))
                .doOnError(e -> log.warn("config_versions_load_failed reason={}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .blockLast();  // P0-2 fix: 确保加载完成后再接受请求，避免版本号竞态覆盖
    }

    /**
     * 在配置变更前保存当前版本为快照。异步写入 ConfigStore，失败只 warn。
     *
     * @param configType  配置类型
     * @param configKey   配置 key
     * @param currentJson 当前值 JSON（即将被覆盖的值）
     * @param operator    操作者标识
     */
    public Mono<Void> snapshotBeforeChange(String configType, String configKey, String currentJson, String operator) {
        if (currentJson == null) {
            return Mono.empty();
        }

        String counterKey = configType + ":" + configKey;
        int versionNumber = versionCounters.compute(counterKey, (k, v) -> (v == null) ? 1 : v + 1);

        String versionId = java.util.UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        ConfigVersion version = new ConfigVersion(versionId, configType, configKey, versionNumber, currentJson, createdAt, operator);

        String storeKey = buildStoreKey(configType, configKey, versionNumber);

        return serializeVersion(version)
                .flatMap(json -> configStore.save(CONFIG_TYPE, storeKey, json))
                .doOnError(e -> log.warn("config_version_persist_failed config_type={} config_key={} version={} reason={}",
                        configType, configKey, versionNumber, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /**
     * 查询某个配置项的历史版本列表。
     */
    public Mono<List<ConfigVersion>> getVersions(String configType, String configKey) {
        String counterKey = configType + ":" + configKey;
        Integer maxVersion = versionCounters.get(counterKey);
        if (maxVersion == null || maxVersion <= 0) {
            return Mono.just(List.of());
        }

        return reactor.core.publisher.Flux.range(1, maxVersion)
                .flatMap(i -> {
                    String storeKey = buildStoreKey(configType, configKey, i);
                    return configStore.load(CONFIG_TYPE, storeKey)
                            .flatMap(this::deserializeVersion)
                            .onErrorResume(e -> Mono.empty());
                })
                .sort(Comparator.comparingInt(ConfigVersion::versionNumber))
                .collectList();
    }

    /**
     * 获取某个配置项的特定版本。
     */
    public Mono<ConfigVersion> getVersion(String configType, String configKey, int versionNumber) {
        String storeKey = buildStoreKey(configType, configKey, versionNumber);
        return configStore.load(CONFIG_TYPE, storeKey)
                .flatMap(this::deserializeVersion);
    }

    /**
     * 回滚到指定版本，返回该版本的 JSON 值。
     */
    public Mono<String> rollbackTo(String configType, String configKey, int versionNumber) {
        return getVersion(configType, configKey, versionNumber)
                .map(ConfigVersion::jsonValue);
    }

    private void updateCounterFromKey(String storeKey) {
        // storeKey format: {configType}:{configKey}:v{versionNumber}
        int lastColon = storeKey.lastIndexOf(':');
        if (lastColon <= 0) {
            return;
        }
        String counterKey = storeKey.substring(0, lastColon);
        String versionStr = storeKey.substring(lastColon + 1);
        if (versionStr.startsWith("v")) {
            try {
                int versionNumber = Integer.parseInt(versionStr.substring(1));
                versionCounters.merge(counterKey, versionNumber, Math::max);
            } catch (NumberFormatException e) {
                log.warn("config_version_parse_failed key={} reason={}", storeKey, e.getMessage());
            }
        }
    }

    private String buildStoreKey(String configType, String configKey, int versionNumber) {
        return configType + ":" + configKey + ":v" + versionNumber;
    }

    private Mono<String> serializeVersion(ConfigVersion version) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(version));
    }

    private Mono<ConfigVersion> deserializeVersion(String json) {
        return Mono.fromCallable(() -> objectMapper.readValue(json, ConfigVersion.class))
                .doOnError(e -> log.warn("config_version_deserialize_failed reason={}", e.getMessage()))
                .onErrorResume(JsonProcessingException.class, e -> Mono.empty());
    }

    /**
     * Clear all in-memory version counters for test isolation.
     */
    public void resetForTests() {
        versionCounters.clear();
    }

    public record ConfigVersion(
            String versionId,
            String configType,
            String configKey,
            int versionNumber,
            String jsonValue,
            Instant createdAt,
            String operator
    ) {
    }
}
