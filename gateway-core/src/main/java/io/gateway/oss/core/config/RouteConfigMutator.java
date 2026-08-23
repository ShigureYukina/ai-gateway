package io.gateway.oss.core.config;

import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

class RouteConfigMutator {

    private final ConfigStore configStore;
    private final GatewayProperties properties;
    private final RuntimeRefreshHooks runtimeRefreshHooks;
    private final ConfigSyncPublisher syncPublisher;
    private final BiFunction<String, String, Mono<Void>> snapshotVersion;
    private final ProviderConfigMutator.AuditRecorder auditRecorder;
    private final Function<Object, String> toJsonOrNull;
    private final Function<Object, String> toJson;

    RouteConfigMutator(ConfigStore configStore,
                       GatewayProperties properties,
                       com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                       io.gateway.oss.core.contract.ConfigAuditStore auditService,
                       io.gateway.oss.core.contract.ConfigVersionStore versionService,
                       RuntimeRefreshHooks runtimeRefreshHooks,
                       ConfigSyncPublisher syncPublisher,
                       BiFunction<String, String, Mono<Void>> snapshotVersion,
                       ProviderConfigMutator.AuditRecorder auditRecorder,
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

    public Mono<Void> save(String id, RouteConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getRoutes().get(id));
        String newValue = toJson.apply(config);

        return snapshotVersion.apply(id, currentValue)
                .then(configStore.save(DynamicConfigService.TYPE_ROUTES, id, newValue))
                .then(Mono.fromRunnable(() -> {
                    Map<String, RouteConfig> copy = new LinkedHashMap<>(properties.getRoutes());
                    copy.put(id, config);
                    properties.setRoutes(copy);
                    runtimeRefreshHooks.onRoutingConfigChanged();
                }))
                .then(Mono.fromRunnable(() -> auditRecorder.record(id, "save", currentValue, newValue)))
                .then(Mono.fromRunnable(() -> {
                    if (syncPublisher != null) {
                        syncPublisher.publish(DynamicConfigService.TYPE_ROUTES);
                    }
                }));
    }

    public Mono<Void> delete(String id) {
        String currentValue = toJsonOrNull.apply(properties.getRoutes().get(id));

        return snapshotVersion.apply(id, currentValue)
                .then(configStore.delete(DynamicConfigService.TYPE_ROUTES, id))
                .then(Mono.fromRunnable(() -> {
                    Map<String, RouteConfig> copy = new LinkedHashMap<>(properties.getRoutes());
                    copy.remove(id);
                    properties.setRoutes(copy);
                    runtimeRefreshHooks.onRouteDeleted();
                }))
                .then(Mono.fromRunnable(() -> auditRecorder.record(id, "delete", currentValue, null)))
                .then(Mono.fromRunnable(() -> {
                    if (syncPublisher != null) {
                        syncPublisher.publish(DynamicConfigService.TYPE_ROUTES);
                    }
                }));
    }
}
