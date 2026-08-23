package io.gateway.oss.core.config;

import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

class ClientConfigMutator {

    private final ConfigStore configStore;
    private final GatewayProperties properties;
    private final ConfigSyncPublisher syncPublisher;
    private final BiFunction<String, String, Mono<Void>> snapshotVersion;
    private final ProviderConfigMutator.AuditRecorder auditRecorder;
    private final Function<Object, String> toJsonOrNull;
    private final Function<Object, String> toJson;

    ClientConfigMutator(ConfigStore configStore,
                        GatewayProperties properties,
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                        io.gateway.oss.core.contract.ConfigAuditStore auditService,
                        io.gateway.oss.core.contract.ConfigVersionStore versionService,
                        ConfigSyncPublisher syncPublisher,
                        BiFunction<String, String, Mono<Void>> snapshotVersion,
                        ProviderConfigMutator.AuditRecorder auditRecorder,
                        Function<Object, String> toJsonOrNull,
                        Function<Object, String> toJson) {
        this.configStore = Objects.requireNonNull(configStore);
        this.properties = Objects.requireNonNull(properties);
        Objects.requireNonNull(objectMapper);
        this.syncPublisher = syncPublisher;
        this.snapshotVersion = Objects.requireNonNull(snapshotVersion);
        this.auditRecorder = Objects.requireNonNull(auditRecorder);
        this.toJsonOrNull = Objects.requireNonNull(toJsonOrNull);
        this.toJson = Objects.requireNonNull(toJson);
    }

    public Mono<Void> save(String key, ClientConfig config) {
        String currentValue = toJsonOrNull.apply(properties.getClients().get(key));
        String newValue = toJson.apply(config);

        return snapshotVersion.apply(key, currentValue)
                .then(configStore.save(DynamicConfigService.TYPE_CLIENTS, key, newValue))
                .then(Mono.fromRunnable(() -> {
                    Map<String, ClientConfig> copy = new LinkedHashMap<>(properties.getClients());
                    copy.put(key, config);
                    properties.setClients(copy);
                }))
                .then(Mono.fromRunnable(() -> auditRecorder.record(key, "save", currentValue, newValue)))
                .then(Mono.fromRunnable(() -> {
                    if (syncPublisher != null) {
                        syncPublisher.publish(DynamicConfigService.TYPE_CLIENTS);
                    }
                }));
    }

    public Mono<Void> delete(String key) {
        String currentValue = toJsonOrNull.apply(properties.getClients().get(key));

        return snapshotVersion.apply(key, currentValue)
                .then(configStore.delete(DynamicConfigService.TYPE_CLIENTS, key))
                .then(Mono.fromRunnable(() -> {
                    Map<String, ClientConfig> copy = new LinkedHashMap<>(properties.getClients());
                    copy.remove(key);
                    properties.setClients(copy);
                }))
                .then(Mono.fromRunnable(() -> auditRecorder.record(key, "delete", currentValue, null)))
                .then(Mono.fromRunnable(() -> {
                    if (syncPublisher != null) {
                        syncPublisher.publish(DynamicConfigService.TYPE_CLIENTS);
                    }
                }));
    }
}
