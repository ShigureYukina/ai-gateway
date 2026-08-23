package io.gateway.oss.core.config;

import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

class ProviderConfigMutator {

    private final ConfigStore configStore;
    private final GatewayProperties properties;
    private final RuntimeRefreshHooks runtimeRefreshHooks;
    private final ConfigSyncPublisher syncPublisher;
    private final BiFunction<String, String, Mono<Void>> snapshotVersion;
    private final AuditRecorder auditRecorder;
    private final Function<Object, String> toJsonOrNull;
    private final Function<Object, String> toJson;

    ProviderConfigMutator(ConfigStore configStore,
                          GatewayProperties properties,
                          com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                          io.gateway.oss.core.contract.ConfigAuditStore auditService,
                          io.gateway.oss.core.contract.ConfigVersionStore versionService,
                          RuntimeRefreshHooks runtimeRefreshHooks,
                          ConfigSyncPublisher syncPublisher,
                          BiFunction<String, String, Mono<Void>> snapshotVersion,
                          AuditRecorder auditRecorder,
                          Function<Object, String> toJsonOrNull,
                          Function<Object, String> toJson) {
        this.configStore = Objects.requireNonNull(configStore);
        this.properties = Objects.requireNonNull(properties);
        Objects.requireNonNull(objectMapper);
        this.runtimeRefreshHooks = Objects.requireNonNull(runtimeRefreshHooks);
        this.syncPublisher = syncPublisher;
        this.snapshotVersion = Objects.requireNonNull(snapshotVersion);
        this.auditRecorder = Objects.requireNonNull(auditRecorder);
        this.toJsonOrNull = Objects.requireNonNull(toJsonOrNull);
        this.toJson = Objects.requireNonNull(toJson);
    }

    public Mono<Void> save(String name, ProviderConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getProviders().get(name));
        String newValue = toJson.apply(config);

        return snapshotVersion.apply(name, currentValue)
                .then(configStore.save(DynamicConfigService.TYPE_PROVIDERS, name, newValue))
                .then(Mono.fromRunnable(() -> {
                    Map<String, ProviderConfig> copy = new LinkedHashMap<>(properties.getProviders());
                    copy.put(name, config);
                    properties.setProviders(copy);
                    runtimeRefreshHooks.onRoutingConfigChanged();
                }))
                .then(Mono.fromRunnable(() -> auditRecorder.record(name, "save", currentValue, newValue)))
                .then(Mono.fromRunnable(() -> {
                    if (syncPublisher != null) {
                        syncPublisher.publish(DynamicConfigService.TYPE_PROVIDERS);
                    }
                }));
    }

    public Mono<Void> delete(String name) {
        String currentValue = toJsonOrNull.apply(properties.getProviders().get(name));

        return snapshotVersion.apply(name, currentValue)
                .then(configStore.delete(DynamicConfigService.TYPE_PROVIDERS, name))
                .then(Mono.fromRunnable(() -> {
                    Map<String, ProviderConfig> copy = new LinkedHashMap<>(properties.getProviders());
                    copy.remove(name);
                    properties.setProviders(copy);
                    runtimeRefreshHooks.onRoutingConfigChanged();
                }))
                .then(Mono.fromRunnable(() -> auditRecorder.record(name, "delete", currentValue, null)))
                .then(Mono.fromRunnable(() -> {
                    if (syncPublisher != null) {
                        syncPublisher.publish(DynamicConfigService.TYPE_PROVIDERS);
                    }
                }));
    }

    @FunctionalInterface
    interface AuditRecorder {
        void record(String key, String action, String oldValue, String newValue);
    }
}
