package io.gateway.oss.core.config;

import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

class SystemConfigMutator {

    private final ConfigStore configStore;
    private final GatewayProperties properties;
    private final RuntimeRefreshHooks runtimeRefreshHooks;
    private final ConfigSyncPublisher syncPublisher;
    private final Set<String> pendingSystemKeys;
    private final BiFunction<String, String, Mono<Void>> snapshotVersion;
    private final ProviderConfigMutator.AuditRecorder auditRecorder;
    private final Function<Object, String> toJsonOrNull;
    private final Function<Object, String> toJson;

    SystemConfigMutator(ConfigStore configStore,
                        GatewayProperties properties,
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                        io.gateway.oss.core.contract.ConfigAuditStore auditService,
                        io.gateway.oss.core.contract.ConfigVersionStore versionService,
                        RuntimeRefreshHooks runtimeRefreshHooks,
                        ConfigSyncPublisher syncPublisher,
                        Set<String> pendingSystemKeys,
                        BiFunction<String, String, Mono<Void>> snapshotVersion,
                        ProviderConfigMutator.AuditRecorder auditRecorder,
                        Function<Object, String> toJsonOrNull,
                        Function<Object, String> toJson) {
        this.configStore = Objects.requireNonNull(configStore);
        this.properties = Objects.requireNonNull(properties);
        Objects.requireNonNull(objectMapper);
        this.runtimeRefreshHooks = Objects.requireNonNull(runtimeRefreshHooks);
        this.syncPublisher = syncPublisher;
        this.pendingSystemKeys = Objects.requireNonNull(pendingSystemKeys);
        this.snapshotVersion = Objects.requireNonNull(snapshotVersion);
        this.auditRecorder = Objects.requireNonNull(auditRecorder);
        this.toJsonOrNull = Objects.requireNonNull(toJsonOrNull);
        this.toJson = Objects.requireNonNull(toJson);
    }

    public Mono<Void> saveSystemLimit(LimitConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getLimit());
        String newValue = toJson.apply(config);
        return saveSystemConfig(DynamicConfigService.KEY_LIMIT, currentValue, newValue, () -> {
            properties.setLimit(config);
            pendingSystemKeys.remove(DynamicConfigService.KEY_LIMIT);
        });
    }

    public Mono<Void> saveSystemResilience(ResilienceConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getResilience());
        String newValue = toJson.apply(config);
        return saveSystemConfig(DynamicConfigService.KEY_RESILIENCE, currentValue, newValue, () -> {
            properties.setResilience(config);
            pendingSystemKeys.remove(DynamicConfigService.KEY_RESILIENCE);
            runtimeRefreshHooks.onResilienceConfigChanged();
        });
    }

    public Mono<Void> saveSystemPricing(PricingConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getPricing());
        String newValue = toJson.apply(config);
        return saveSystemConfig(DynamicConfigService.KEY_PRICING, currentValue, newValue, () -> {
            properties.setPricing(config);
            pendingSystemKeys.remove(DynamicConfigService.KEY_PRICING);
        });
    }

    public Mono<Void> saveSystemOperational(OperationalConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getOperational());
        String newValue = toJson.apply(config);
        return saveSystemConfig(DynamicConfigService.KEY_OPERATIONAL, currentValue, newValue, () -> {
            properties.setOperational(config);
            pendingSystemKeys.remove(DynamicConfigService.KEY_OPERATIONAL);
        });
    }

    public Mono<Void> saveSystemLoadBalancer(LoadBalancerConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getLoadBalancer());
        String newValue = toJson.apply(config);
        return saveSystemConfig(DynamicConfigService.KEY_LOAD_BALANCER, currentValue, newValue, () -> {
            properties.setLoadBalancer(config);
            pendingSystemKeys.remove(DynamicConfigService.KEY_LOAD_BALANCER);
        });
    }

    public Mono<Void> saveSystemConcurrentLimit(ConcurrentLimitConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getConcurrentLimit());
        String newValue = toJson.apply(config);
        return saveSystemConfig(DynamicConfigService.KEY_CONCURRENT_LIMIT, currentValue, newValue, () -> {
            properties.setConcurrentLimit(config);
            pendingSystemKeys.remove(DynamicConfigService.KEY_CONCURRENT_LIMIT);
        });
    }

    public Mono<Void> saveSystemTracing(TraceConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getTracing());
        String newValue = toJson.apply(config);
        return saveSystemConfig(DynamicConfigService.KEY_TRACING, currentValue, newValue, () -> {
            properties.setTracing(config);
            pendingSystemKeys.remove(DynamicConfigService.KEY_TRACING);
        });
    }

    public Mono<Void> saveSystemSync(SyncConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getSync());
        String newValue = toJson.apply(config);
        return saveSystemConfig(DynamicConfigService.KEY_SYNC, currentValue, newValue, () -> {
            properties.setSync(config);
            pendingSystemKeys.remove(DynamicConfigService.KEY_SYNC);
        });
    }

    public Mono<Void> saveSystemProviderHealth(ProviderHealthConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getProviderHealth());
        String newValue = toJson.apply(config);
        return saveSystemConfig(DynamicConfigService.KEY_PROVIDER_HEALTH, currentValue, newValue, () -> {
            properties.setProviderHealth(config);
            pendingSystemKeys.remove(DynamicConfigService.KEY_PROVIDER_HEALTH);
        });
    }

    public Mono<Void> saveSystemAuth(AuthConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getAuth());
        String newValue = toJson.apply(config);
        return saveSystemConfig(DynamicConfigService.KEY_AUTH, currentValue, newValue, () -> {
            properties.setAuth(config);
            pendingSystemKeys.remove(DynamicConfigService.KEY_AUTH);
        });
    }

    private Mono<Void> saveSystemConfig(String key, String currentValue, String newValue, Runnable applyChange) {
        return snapshotVersion.apply(key, currentValue)
                .then(configStore.save(DynamicConfigService.TYPE_SYSTEM, key, newValue))
                .then(Mono.fromRunnable(applyChange))
                .then(Mono.fromRunnable(() -> auditRecorder.record(key, "save", currentValue, newValue)))
                .then(Mono.fromRunnable(() -> {
                    if (syncPublisher != null) {
                        syncPublisher.publish(DynamicConfigService.TYPE_SYSTEM);
                    }
                }));
    }
}
