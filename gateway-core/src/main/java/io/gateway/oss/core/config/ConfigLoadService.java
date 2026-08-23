package io.gateway.oss.core.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置加载服务。
 * <p>
 * 职责：从 {@link ConfigStore} 加载配置，写入 {@link GatewayProperties} 内存映射。
 * ConfigStore 为空时保留 YAML 默认值。
 * </p>
 */
@Service
public class ConfigLoadService {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoadService.class);

    private final ConfigStore configStore;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public ConfigLoadService(ConfigStore configStore, GatewayProperties properties, ObjectMapper objectMapper) {
        this.configStore = configStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 加载全部配置类型（providers / routes / scenes / clients / system）。
     */
    public Mono<Void> loadAll() {
        return Mono.when(
                loadProviders(),
                loadRoutes(),
                loadScenes(),
                loadClients(),
                loadSystemConfig()
        );
    }

    /**
     * 按配置类型重新加载。
     *
     * @param configType 配置类型常量，参考 {@link DynamicConfigService#TYPE_PROVIDERS} 等
     */
    public Mono<Void> reload(String configType) {
        return switch (configType) {
            case DynamicConfigService.TYPE_PROVIDERS -> loadProviders();
            case DynamicConfigService.TYPE_ROUTES -> loadRoutes();
            case DynamicConfigService.TYPE_SCENES -> loadScenes();
            case DynamicConfigService.TYPE_CLIENTS -> loadClients();
            case DynamicConfigService.TYPE_SYSTEM -> {
                log.info("ConfigLoadService: reloading system config from store");
                yield loadSystemConfig();
            }
            default -> {
                log.warn("ConfigLoadService: unknown config type '{}'", configType);
                yield Mono.empty();
            }
        };
    }

    // ─── private load methods ───

    private Mono<Void> loadProviders() {
        return configStore.loadAll(DynamicConfigService.TYPE_PROVIDERS)
                .doOnNext(stored -> {
                    if (stored == null || stored.isEmpty()) {
                        // store 为空时保持 YAML/Spring 默认值，不清除（避免初始启动时丢失配置）
                        return;
                    }
                    Map<String, ProviderConfig> loadedProviders = new LinkedHashMap<>();
                    for (Map.Entry<String, String> entry : stored.entrySet()) {
                        try {
                            ProviderConfig config = objectMapper.readValue(entry.getValue(), ProviderConfig.class);
                            loadedProviders.put(entry.getKey(), config);
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to load provider config '{}': {}", entry.getKey(), e.getMessage());
                        }
                    }
                    properties.setProviders(loadedProviders);
                })
                .then();
    }

    private Mono<Void> loadRoutes() {
        return configStore.loadAll(DynamicConfigService.TYPE_ROUTES)
                .doOnNext(stored -> {
                    if (stored == null || stored.isEmpty()) {
                        // store 为空时保持 YAML/Spring 默认值
                        return;
                    }
                    Map<String, RouteConfig> loadedRoutes = new LinkedHashMap<>();
                    for (Map.Entry<String, String> entry : stored.entrySet()) {
                        try {
                            RouteConfig config = objectMapper.readValue(entry.getValue(), RouteConfig.class);
                            loadedRoutes.put(entry.getKey(), config);
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to load route config '{}': {}", entry.getKey(), e.getMessage());
                        }
                    }
                    properties.setRoutes(loadedRoutes);
                })
                .then();
    }

    private Mono<Void> loadScenes() {
        return configStore.loadAll(DynamicConfigService.TYPE_SCENES)
                .doOnNext(stored -> {
                    if (stored == null || stored.isEmpty()) {
                        // store 为空时保持 YAML/Spring 默认值
                        return;
                    }
                    Map<String, SceneConfig> loadedScenes = new LinkedHashMap<>();
                    for (Map.Entry<String, String> entry : stored.entrySet()) {
                        try {
                            SceneConfig config = objectMapper.readValue(entry.getValue(), SceneConfig.class);
                            loadedScenes.put(entry.getKey(), config);
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to load scene config '{}': {}", entry.getKey(), e.getMessage());
                        }
                    }
                    properties.setScenes(loadedScenes);
                })
                .then();
    }

    private Mono<Void> loadClients() {
        return configStore.loadAll(DynamicConfigService.TYPE_CLIENTS)
                .doOnNext(stored -> {
                    if (stored == null || stored.isEmpty()) {
                        // store 为空时保持 YAML/Spring 默认值
                        return;
                    }
                    Map<String, ClientConfig> loadedClients = new LinkedHashMap<>();
                    for (Map.Entry<String, String> entry : stored.entrySet()) {
                        try {
                            ClientConfig config = objectMapper.readValue(entry.getValue(), ClientConfig.class);
                            loadedClients.put(entry.getKey(), config);
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to load client config '{}': {}", entry.getKey(), e.getMessage());
                        }
                    }
                    properties.setClients(loadedClients);
                })
                .then();
    }

    private Mono<Void> loadSystemConfig() {
        return configStore.loadAll(DynamicConfigService.TYPE_SYSTEM)
                .doOnNext(stored -> {
                    // 采用权威替换：先重置为 YAML/Spring 初始默认值（不丢失启动时的属性配置），
                    // 再按持久层快照全量覆盖，避免已删除/未持久化的配置项残留。
                    // 注意：不能使用 new LimitConfig() 等无状态默认，否则会丢失 Spring
                    // 通过 @ConfigurationProperties 注入的 YAML/测试属性（如 auth.enabled=true）。
                    // 正确的做法是：为每个 key 重新设置为从初始 Properties 副本解析的值。
                    if (stored == null || stored.isEmpty()) {
                        return;
                    }
                    // 仅覆盖 store 中存在的 key，不存在时保持 Spring 已注入的初始值
                    applySystemOverride(stored, DynamicConfigService.KEY_LIMIT, LimitConfig.class, properties::setLimit);
                    applySystemOverride(stored, DynamicConfigService.KEY_RESILIENCE, ResilienceConfig.class, properties::setResilience);
                    applySystemOverride(stored, DynamicConfigService.KEY_PRICING, PricingConfig.class, properties::setPricing);
                    applySystemOverride(stored, DynamicConfigService.KEY_OPERATIONAL, OperationalConfig.class, properties::setOperational);
                    applySystemOverride(stored, DynamicConfigService.KEY_LOAD_BALANCER, LoadBalancerConfig.class, properties::setLoadBalancer);
                    applySystemOverride(stored, DynamicConfigService.KEY_CONCURRENT_LIMIT, ConcurrentLimitConfig.class, properties::setConcurrentLimit);
                    applySystemOverride(stored, DynamicConfigService.KEY_TRACING, TraceConfig.class, properties::setTracing);
                    applySystemOverride(stored, DynamicConfigService.KEY_SYNC, SyncConfig.class, properties::setSync);
                    applySystemOverride(stored, DynamicConfigService.KEY_PROVIDER_HEALTH, ProviderHealthConfig.class, properties::setProviderHealth);
                    applySystemOverride(stored, DynamicConfigService.KEY_AUTH, AuthConfig.class, properties::setAuth);
                })
                .then();
    }

    /**
     * 从 store 快照中按 key 读取 JSON，存在时反序列化并设置到 properties；不存在时保持 properties 已有值不变。
     */
    private <T> void applySystemOverride(Map<String, String> stored, String key, Class<T> type, java.util.function.Consumer<T> setter) {
        String json = stored.get(key);
        if (json == null) {
            return;
        }
        try {
            setter.accept(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            log.warn("Failed to load system config key '{}': {}", key, e.getMessage());
        }
    }
}
