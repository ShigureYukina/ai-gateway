package io.gateway.oss.admin.sync;

import io.gateway.oss.core.config.ModelsDevConfig;
import io.gateway.oss.core.contract.GatewayConfigView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class ModelsDevSyncService {

    private static final Logger log = LoggerFactory.getLogger(ModelsDevSyncService.class);

    private final GatewayConfigView configView;
    private final ModelsDevClient modelsDevClient;
    private final ProviderModelCatalogService catalogService;
    private final PricingSyncService pricingSyncService;
    private final ProviderModelPersistenceService persistenceService;
    private final ModelMetadataService metadataService;
    private final AtomicReference<Mono<Boolean>> inFlightSync = new AtomicReference<>();

    public ModelsDevSyncService(GatewayConfigView configView,
                                ModelsDevClient modelsDevClient,
                                ProviderModelCatalogService catalogService,
                                PricingSyncService pricingSyncService,
                                ProviderModelPersistenceService persistenceService,
                                ModelMetadataService metadataService) {
        this.configView = configView;
        this.modelsDevClient = modelsDevClient;
        this.catalogService = catalogService;
        this.pricingSyncService = pricingSyncService;
        this.persistenceService = persistenceService;
        this.metadataService = metadataService;
    }

    public boolean syncOnce(String trigger) {
        return Boolean.TRUE.equals(syncOnceReactive(trigger, false).block());
    }

    public boolean syncOnce(String trigger, boolean overrideEnabled) {
        return Boolean.TRUE.equals(syncOnceReactive(trigger, overrideEnabled).block());
    }

    public Mono<Boolean> syncOnceReactive(String trigger) {
        return syncOnceReactive(trigger, false);
    }

    public Mono<Boolean> syncOnceReactive(String trigger, boolean overrideEnabled) {
        ModelsDevConfig config = configView.getSync().getModelsDev();
        if (!overrideEnabled && !config.isEnabled()) {
            return Mono.just(false);
        }

        Mono<Boolean> current = inFlightSync.get();
        if (current != null) {
            log.debug("models_dev_sync_join trigger={} reason=in_flight", trigger);
            return current;
        }

        AtomicReference<Mono<Boolean>> candidateRef = new AtomicReference<>();
        Mono<Boolean> candidate = executeSync(trigger, config)
                .cache()
                .doFinally(signalType -> inFlightSync.compareAndSet(candidateRef.get(), null));
        candidateRef.set(candidate);

        if (!inFlightSync.compareAndSet(null, candidate)) {
            Mono<Boolean> joined = inFlightSync.get();
            if (joined != null) {
                log.debug("models_dev_sync_join trigger={} reason=race_lost", trigger);
                return joined;
            }
            return syncOnceReactive(trigger, overrideEnabled);
        }

        return candidate;
    }

    private Mono<Boolean> executeSync(String trigger, ModelsDevConfig config) {
        return modelsDevClient.fetchSnapshot()
                .flatMap(snapshot -> {
                    return persistSnapshot(trigger, snapshot)
                            .then(Mono.fromRunnable(() -> {
                                catalogService.replaceSnapshot(snapshot.providerModels(), snapshot.fetchedAt());
                                pricingSyncService.replaceSnapshot(snapshot.modelPrices(), snapshot.modelPricings(), snapshot.fetchedAt());
                                if (metadataService != null) {
                                    metadataService.replaceSnapshot(snapshot.modelMetadata(), snapshot.fetchedAt());
                                }
                            }))
                            .thenReturn(snapshot);
                })
                .map(snapshot -> {
                    if ("startup".equalsIgnoreCase(trigger) || "manual".equalsIgnoreCase(trigger)) {
                        log.info("models_dev_sync_success trigger={} providers={} prices={} at={}",
                                trigger,
                                snapshot.providerModels().size(),
                                snapshot.modelPrices().size(),
                                snapshot.fetchedAt());
                    } else {
                        log.debug("models_dev_sync_success trigger={} providers={} prices={} at={}",
                                trigger,
                                snapshot.providerModels().size(),
                                snapshot.modelPrices().size(),
                                snapshot.fetchedAt());
                    }
                    return true;
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.info("models_dev_sync_skipped trigger={} reason=empty_snapshot", trigger);
                    return false;
                }))
                .onErrorResume(Throwable.class, ex -> {
                    log.warn("models_dev_sync_failed trigger={} endpoint={} cause={}",
                            trigger,
                            config.getEndpoint(),
                            ex.toString());
                    return Mono.just(false);
                });
    }

    private Mono<Void> persistSnapshot(String trigger, ModelsDevClient.ModelsDevSnapshot snapshot) {
        if (persistenceService == null) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> persistenceService.persistFromSnapshot(snapshot))
                .doOnError(RuntimeException.class, ex -> log.warn("models_dev_persist_failed trigger={} reason={}", trigger, ex.toString()))
                .then();
    }
}
