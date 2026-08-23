package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.ConfigAuditStore;
import io.gateway.oss.core.contract.ConfigVersionStore;
import io.gateway.oss.core.contract.ClientConfigWriter;
import io.gateway.oss.core.contract.ModelPublicationConfigWriter;
import io.gateway.oss.core.contract.ProviderConfigWriter;
import io.gateway.oss.core.contract.RouteConfigWriter;
import io.gateway.oss.core.contract.SceneConfigWriter;
import io.gateway.oss.core.contract.SystemConfigManager;
import io.gateway.oss.core.routing.ModelRouteResolver;
import io.gateway.oss.core.routing.RouteLoadBalancer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态配置管理服务。
 * <p>
 * 职责：
 * <ul>
 *   <li>启动时从 {@link ConfigStore} 加载配置，写入 {@link GatewayProperties} 内存映射</li>
 *   <li>ConfigStore 为空时保留 YAML 默认值（seed/fallback）</li>
 *   <li>提供 save / delete 方法：写 ConfigStore（持久化）+ 更新 GatewayProperties（立即生效）</li>
 * </ul>
 * </p>
 * <p>
 * 配置类型常量：{@code providers} / {@code routes} / {@code clients} / {@code system}
 * </p>
 * <p>
 * system 类型下 key 示例：{@code limit} / {@code resilience} / {@code pricing}
 * </p>
 */
public class DynamicConfigService implements SystemConfigManager, ProviderConfigWriter, RouteConfigWriter,
        SceneConfigWriter, ClientConfigWriter, ModelPublicationConfigWriter {

    private static final Logger log = LoggerFactory.getLogger(DynamicConfigService.class);

    public static final String TYPE_PROVIDERS = "providers";
    public static final String TYPE_ROUTES = "routes";
    public static final String TYPE_SCENES = "scenes";
    public static final String TYPE_CLIENTS = "clients";
    public static final String TYPE_SYSTEM = "system";

    public static final String KEY_LIMIT = "limit";
    public static final String KEY_RESILIENCE = "resilience";
    public static final String KEY_PRICING = "pricing";
    public static final String KEY_OPERATIONAL = "operational";
    public static final String KEY_LOAD_BALANCER = "load-balancer";
    public static final String KEY_CONCURRENT_LIMIT = "concurrent-limit";
    public static final String KEY_TRACING = "tracing";
    public static final String KEY_SYNC = "sync";
    public static final String KEY_PROVIDER_HEALTH = "provider-health";
    public static final String KEY_AUTH = "auth";

    private final ConfigStore configStore;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final ConfigAuditStore auditService;
    private final ConfigVersionStore versionService;
    private final ConfigLoadService configLoadService;
    private final RuntimeRefreshHooks runtimeRefreshHooks;
    private final ConfigSyncPublisher syncPublisher;
    private final ProviderConfigMutator providerMutator;
    private final RouteConfigMutator routeMutator;
    private final SceneConfigMutator sceneMutator;
    private final ClientConfigMutator clientMutator;
    private final SystemConfigMutator systemMutator;

    /**
     * System config keys that have been persisted but not yet applied (pending restart).
     * Only system-level configs (limit/resilience/pricing/operational) require restart.
     * Client-level configs remain hot-updatable.
     */
    private final Set<String> pendingSystemKeys = ConcurrentHashMap.newKeySet();

    private static final String OPERATOR_ADMIN = "admin";

    public DynamicConfigService(ConfigStore configStore, GatewayProperties properties, ObjectMapper objectMapper) {
        this(configStore, properties, objectMapper, null, null,
                new ConfigLoadService(configStore, properties, objectMapper),
                RuntimeRefreshHooks.noop(), null);
    }

    public DynamicConfigService(ConfigStore configStore, GatewayProperties properties, ObjectMapper objectMapper,
                                ConfigAuditStore auditService, ConfigVersionStore versionService,
                                ConfigLoadService configLoadService,
                                ObjectProvider<RouteLoadBalancer> routeLoadBalancerProvider,
                                ObjectProvider<io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService> resilienceServiceProvider,
                                ObjectProvider<ModelRouteResolver> modelRouteResolverProvider,
                                ObjectProvider<ConfigSyncPublisher> syncPublisherProvider) {
        this(configStore, properties, objectMapper, auditService, versionService, configLoadService,
                RuntimeRefreshHooks.of(
                        routeLoadBalancerProvider.getIfAvailable(),
                        resilienceServiceProvider.getIfAvailable(),
                        modelRouteResolverProvider.getIfAvailable()),
                syncPublisherProvider.getIfAvailable());
    }

    DynamicConfigService(ConfigStore configStore, GatewayProperties properties, ObjectMapper objectMapper,
                         ConfigAuditStore auditService, ConfigVersionStore versionService,
                         ConfigLoadService configLoadService, RuntimeRefreshHooks runtimeRefreshHooks,
                         ConfigSyncPublisher syncPublisher) {
        this.configStore = Objects.requireNonNull(configStore);
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.auditService = auditService;
        this.versionService = versionService;
        this.configLoadService = Objects.requireNonNull(configLoadService);
        this.runtimeRefreshHooks = Objects.requireNonNull(runtimeRefreshHooks);
        this.syncPublisher = syncPublisher;
        this.providerMutator = new ProviderConfigMutator(
                this.configStore,
                this.properties,
                this.objectMapper,
                this.auditService,
                this.versionService,
                this.runtimeRefreshHooks,
                this.syncPublisher,
                (key, currentValue) -> snapshotVersion(TYPE_PROVIDERS, key, currentValue),
                (key, action, oldValue, newValue) -> auditRecord(TYPE_PROVIDERS, key, action, oldValue, newValue),
                this::toJsonOrNull,
                this::toJson);
        this.routeMutator = new RouteConfigMutator(
                this.configStore,
                this.properties,
                this.objectMapper,
                this.auditService,
                this.versionService,
                this.runtimeRefreshHooks,
                this.syncPublisher,
                (key, currentValue) -> snapshotVersion(TYPE_ROUTES, key, currentValue),
                (key, action, oldValue, newValue) -> auditRecord(TYPE_ROUTES, key, action, oldValue, newValue),
                this::toJsonOrNull,
                this::toJson);
        this.sceneMutator = new SceneConfigMutator(
                this.configStore,
                this.properties,
                this.objectMapper,
                this.auditService,
                this.versionService,
                this.runtimeRefreshHooks,
                this.syncPublisher,
                (key, currentValue) -> snapshotVersion(TYPE_SCENES, key, currentValue),
                (key, action, oldValue, newValue) -> auditRecord(TYPE_SCENES, key, action, oldValue, newValue),
                this::toJsonOrNull,
                this::toJson);
        this.clientMutator = new ClientConfigMutator(
                this.configStore,
                this.properties,
                this.objectMapper,
                this.auditService,
                this.versionService,
                this.syncPublisher,
                (key, currentValue) -> snapshotVersion(TYPE_CLIENTS, key, currentValue),
                (key, action, oldValue, newValue) -> auditRecord(TYPE_CLIENTS, key, action, oldValue, newValue),
                this::toJsonOrNull,
                this::toJson);
        this.systemMutator = new SystemConfigMutator(
                this.configStore,
                this.properties,
                this.objectMapper,
                this.auditService,
                this.versionService,
                this.runtimeRefreshHooks,
                this.syncPublisher,
                this.pendingSystemKeys,
                (key, currentValue) -> snapshotVersion(TYPE_SYSTEM, key, currentValue),
                (key, action, oldValue, newValue) -> auditRecord(TYPE_SYSTEM, key, action, oldValue, newValue),
                this::toJsonOrNull,
                this::toJson);
    }

    /**
     * 应用启动后从 ConfigStore 加载配置。
     * <p>
     * ConfigStore 为空时保留 YAML 默认值；加载失败时降级为 YAML 默认值，不阻止启动。
     * </p>
     */
    @PostConstruct
    public void init() {
        try {
            configLoadService.loadAll()
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();
            log.info("DynamicConfigService: configuration loaded from ConfigStore");
        } catch (Exception e) {
            log.warn("DynamicConfigService: failed to load from ConfigStore, falling back to YAML defaults", e);
        }
    }

    // ─── providers ───

    /**
     * 保存 provider 配置：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     */
    public Mono<Void> saveProvider(String name, ProviderConfig config) {
        return providerMutator.save(name, config);
    }

    /**
     * 删除 provider 配置：快照旧版本 → 持久化删除 + 内存移除 → 审计记录。
     */
    public Mono<Void> deleteProvider(String name) {
        return providerMutator.delete(name);
    }

    // ─── routes ───

    /**
     * 保存 route 配置：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     */
    public Mono<Void> saveRoute(String id, RouteConfig config) {
        return routeMutator.save(id, config);
    }

    /**
     * 删除 route 配置：快照旧版本 → 持久化删除 + 内存移除 → 审计记录。
     */
    public Mono<Void> deleteRoute(String id) {
        return routeMutator.delete(id);
    }

    // ─── scenes ───

    /**
     * 保存 scene 配置：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     */
    public Mono<Void> saveScene(String id, SceneConfig config) {
        return sceneMutator.save(id, config);
    }

    /**
     * 删除 scene 配置：快照旧版本 → 持久化删除 + 内存移除 → 审计记录。
     */
    public Mono<Void> deleteScene(String id) {
        return sceneMutator.delete(id);
    }

    // ─── clients ───

    /**
     * 保存 client 配置：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     */
    public Mono<Void> saveClient(String key, ClientConfig config) {
        return clientMutator.save(key, config);
    }

    /**
     * 删除 client 配置：快照旧版本 → 持久化删除 + 内存移除 → 审计记录。
     */
    public Mono<Void> deleteClient(String key) {
        return clientMutator.delete(key);
    }

    // ─── system: limit ───

    /**
     * 保存限流配置：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     * <p>系统级配置修改已改为运行时生效，不再需要重启。</p>
     */
    public Mono<Void> saveSystemLimit(LimitConfig config) {
        return systemMutator.saveSystemLimit(config);
    }

    // ─── system: resilience ───

    /**
     * 保存韧性配置：快照旧版本 → 持久化 + 立即更新内存 + 刷新 Resilience4j 对象 → 审计记录。
     * <p>系统级配置修改已改为运行时生效，不再需要重启。</p>
     */
    public Mono<Void> saveSystemResilience(ResilienceConfig config) {
        return systemMutator.saveSystemResilience(config);
    }

    // ─── system: pricing ───

    /**
     * 保存定价配置：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     * <p>系统级配置修改已改为运行时生效，不再需要重启。</p>
     */
    public Mono<Void> saveSystemPricing(PricingConfig config) {
        return systemMutator.saveSystemPricing(config);
    }

    // ─── system: operational ───

    /**
     * 保存运维配置（maintenance mode / emergency rate limit）。
     * <p>系统级配置修改已改为运行时生效，不再需要重启。</p>
     */
    public Mono<Void> saveSystemOperational(OperationalConfig config) {
        return systemMutator.saveSystemOperational(config);
    }

    // ─── system: load-balancer ───

    /**
     * 保存负载均衡配置：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     * <p>修改 loadBalancer.enabled 后，{@link
     * io.gateway.oss.core.routing.RouteLoadBalancer} 在下次调用 {@code select()}
     * 时会读取最新值。</p>
     */
    public Mono<Void> saveSystemLoadBalancer(LoadBalancerConfig config) {
        return systemMutator.saveSystemLoadBalancer(config);
    }

    // ─── system: concurrent-limit ───

    /**
     * 保存并发限制配置：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     */
    public Mono<Void> saveSystemConcurrentLimit(ConcurrentLimitConfig config) {
        return systemMutator.saveSystemConcurrentLimit(config);
    }

    // ─── system: tracing ───

    /**
     * 保存追踪配置：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     */
    public Mono<Void> saveSystemTracing(TraceConfig config) {
        return systemMutator.saveSystemTracing(config);
    }

    // ─── system: sync ───

    /**
     * 保存同步配置（modelsDev）：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     */
    public Mono<Void> saveSystemSync(SyncConfig config) {
        return systemMutator.saveSystemSync(config);
    }

    // ─── system: provider-health ───

    /**
     * 保存 Provider 探活配置：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     */
    public Mono<Void> saveSystemProviderHealth(ProviderHealthConfig config) {
        return systemMutator.saveSystemProviderHealth(config);
    }

    // ─── system: auth ───

    /**
     * 保存认证配置（JWT/注册模式）：快照旧版本 → 持久化 + 立即更新内存 → 审计记录。
     */
    public Mono<Void> saveSystemAuth(AuthConfig config) {
        return systemMutator.saveSystemAuth(config);
    }

    /**
     * 检查指定 provider 是否被某条 route 引用。
     *
     * @return 引用了该 provider 的 route ID 列表，空表示无引用
     */
    public List<String> getRouteReferences(String providerName) {
        return properties.getRoutes().entrySet().stream()
                .filter(e -> e.getValue().getProvider() != null && e.getValue().getProvider().equals(providerName))
                .map(Map.Entry::getKey)
                .toList();
    }

    public Set<String> getPendingSystemKeys() {
        return Collections.unmodifiableSet(pendingSystemKeys);
    }

    public Mono<Void> importConfig(Map<String, Object> body) {
        return Mono.fromRunnable(() -> {
            Map<String, Object> providers = objectMapper.convertValue(body.getOrDefault("providers", Map.of()), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (providers != null) {
                for (var entry : providers.entrySet()) {
                    ProviderConfig cfg = objectMapper.convertValue(entry.getValue(), ProviderConfig.class);
                    properties.getProviders().put(entry.getKey(), cfg);
                }
            }
            Map<String, Object> routes = objectMapper.convertValue(body.getOrDefault("routes", Map.of()), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (routes != null) {
                for (var entry : routes.entrySet()) {
                    RouteConfig cfg = objectMapper.convertValue(entry.getValue(), RouteConfig.class);
                    properties.getRoutes().put(entry.getKey(), cfg);
                }
            }
            Map<String, Object> scenes = objectMapper.convertValue(body.getOrDefault("scenes", Map.of()), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (scenes != null) {
                for (var entry : scenes.entrySet()) {
                    SceneConfig cfg = objectMapper.convertValue(entry.getValue(), SceneConfig.class);
                    properties.getScenes().put(entry.getKey(), cfg);
                }
            }
            Map<String, Object> clients = objectMapper.convertValue(body.getOrDefault("clients", Map.of()), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (clients != null) {
                for (var entry : clients.entrySet()) {
                    ClientConfig cfg = objectMapper.convertValue(entry.getValue(), ClientConfig.class);
                    properties.getClients().put(entry.getKey(), cfg);
                }
            }
        }).then(Mono.defer(() -> {
            return configStore.save(TYPE_PROVIDERS, "_all", toJson(properties.getProviders()))
                    .then(configStore.save(TYPE_ROUTES, "_all", toJson(properties.getRoutes())))
                    .then(configStore.save(TYPE_SCENES, "_all", toJson(properties.getScenes())))
                    .then(configStore.save(TYPE_CLIENTS, "_all", toJson(properties.getClients())));
        }));
    }

    public Mono<Void> applyImportedConfig(Map<String, ProviderConfig> providers,
                                          Map<String, RouteConfig> routes,
                                          Map<String, SceneConfig> scenes,
                                          Map<String, ClientConfig> clients,
                                          LimitConfig limit,
                                          ResilienceConfig resilience,
                                          PricingConfig pricing,
                                          OperationalConfig operational) {
        Map<String, ProviderConfig> providerState = new LinkedHashMap<>(properties.getProviders());
        providerState.putAll(providers);
        Map<String, RouteConfig> routeState = new LinkedHashMap<>(properties.getRoutes());
        routeState.putAll(routes);
        Map<String, SceneConfig> sceneState = new LinkedHashMap<>(properties.getScenes());
        sceneState.putAll(scenes);
        Map<String, ClientConfig> clientState = new LinkedHashMap<>(properties.getClients());
        clientState.putAll(clients);

        List<ConfigMutation> mutations = new ArrayList<>();
        providers.forEach((key, value) -> mutations.add(ConfigMutation.save(TYPE_PROVIDERS, key,
                toJsonOrNull(properties.getProviders().get(key)), toJson(value))));
        routes.forEach((key, value) -> mutations.add(ConfigMutation.save(TYPE_ROUTES, key,
                toJsonOrNull(properties.getRoutes().get(key)), toJson(value))));
        scenes.forEach((key, value) -> mutations.add(ConfigMutation.save(TYPE_SCENES, key,
                toJsonOrNull(properties.getScenes().get(key)), toJson(value))));
        clients.forEach((key, value) -> mutations.add(ConfigMutation.save(TYPE_CLIENTS, key,
                toJsonOrNull(properties.getClients().get(key)), toJson(value))));
        if (limit != null) {
            mutations.add(ConfigMutation.save(TYPE_SYSTEM, KEY_LIMIT, toJsonOrNull(properties.getLimit()), toJson(limit)));
        }
        if (resilience != null) {
            mutations.add(ConfigMutation.save(TYPE_SYSTEM, KEY_RESILIENCE, toJsonOrNull(properties.getResilience()), toJson(resilience)));
        }
        if (pricing != null) {
            mutations.add(ConfigMutation.save(TYPE_SYSTEM, KEY_PRICING, toJsonOrNull(properties.getPricing()), toJson(pricing)));
        }
        if (operational != null) {
            mutations.add(ConfigMutation.save(TYPE_SYSTEM, KEY_OPERATIONAL, toJsonOrNull(properties.getOperational()), toJson(operational)));
        }

        Mono<Void> snapshot = Flux.fromIterable(mutations)
                .flatMap(mutation -> snapshotVersion(mutation.configType(), mutation.configKey(), mutation.previousJson()))
                .then();

        Mono<Void> persist = Flux.fromIterable(mutations)
                .concatMap(mutation -> configStore.save(mutation.configType(), mutation.configKey(), mutation.newJson()))
                .then();

        Mono<Void> applyFlow = snapshot
                .then(persist)
                .then(Mono.fromRunnable(() -> {
                    properties.setProviders(providerState);
                    properties.setRoutes(routeState);
                    properties.setScenes(sceneState);
                    properties.setClients(clientState);
                    runtimeRefreshHooks.onRoutingConfigChanged();
                    if (limit != null) {
                        pendingSystemKeys.add(KEY_LIMIT);
                    }
                    if (resilience != null) {
                        pendingSystemKeys.add(KEY_RESILIENCE);
                    }
                    if (pricing != null) {
                        pendingSystemKeys.add(KEY_PRICING);
                    }
                    if (operational != null) {
                        pendingSystemKeys.add(KEY_OPERATIONAL);
                    }
                }))
                .doOnSuccess(v -> {
                    for (ConfigMutation mutation : mutations) {
                        auditRecord(mutation.configType(), mutation.configKey(), "save", mutation.previousJson(), mutation.newJson());
                    }
                    publishTouchedTypes(mutations);
                })
                .then();

        return applyFlow.onErrorResume(error -> rollbackAndRethrow(mutations, error));
    }

    // ─── audit / version helpers ───

    /**
     * 快照当前版本到版本服务。versionService 为 null 时跳过。
     */
    private Mono<Void> snapshotVersion(String configType, String configKey, String currentValue) {
        if (versionService == null || currentValue == null) {
            return Mono.empty();
        }
        return versionService.snapshotBeforeChange(configType, configKey, currentValue, OPERATOR_ADMIN)
                .doOnError(e -> log.warn("snapshot_before_change_failed config_type={} config_key={} reason={}",
                        configType, configKey, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * 异步记录审计日志。auditService 为 null 时跳过。失败只 warn 不阻断。
     */
    private void auditRecord(String configType, String configKey, String action, String oldValue, String newValue) {
        if (auditService == null) {
            return;
        }
        auditService.record(configType, configKey, action, OPERATOR_ADMIN, oldValue, newValue)
                .subscribe(v -> {}, e -> log.warn("audit_record_dropped config_type={} config_key={} reason={}",
                        configType, configKey, e.getMessage()));
    }

    /**
     * 安全地将对象序列化为 JSON 字符串，null 输入返回 null。
     */
    private String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        return toJson(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize config value", e);
        }
    }

    private Mono<Void> rollbackImportedConfig(List<ConfigMutation> mutations) {
        List<ConfigMutation> reverse = new ArrayList<>(mutations);
        Collections.reverse(reverse);
        return Flux.fromIterable(reverse)
                .concatMap(mutation -> {
                    if (mutation.previousJson() == null) {
                        return configStore.delete(mutation.configType(), mutation.configKey());
                    }
                    return configStore.save(mutation.configType(), mutation.configKey(), mutation.previousJson());
                })
                .onErrorResume(rollbackError -> {
                    log.warn("config_import_rollback_failed reason={}", rollbackError.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> rollbackAndRethrow(List<ConfigMutation> mutations, Throwable error) {
        return rollbackImportedConfig(mutations)
                .then(Mono.defer(() -> Mono.<Void>error(asRuntimeException(error))));
    }

    private RuntimeException asRuntimeException(Throwable error) {
        if (error instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(error);
    }

    private void publishTouchedTypes(List<ConfigMutation> mutations) {
        if (syncPublisher == null) {
            return;
        }
        mutations.stream()
                .map(ConfigMutation::configType)
                .distinct()
                .forEach(syncPublisher::publish);
    }

    private record ConfigMutation(String configType, String configKey, String previousJson, String newJson) {
        private static ConfigMutation save(String configType, String configKey, String previousJson, String newJson) {
            return new ConfigMutation(configType, configKey, previousJson, newJson);
        }
    }
}
