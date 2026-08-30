package io.gateway.oss.admin.upstream;

import io.gateway.oss.core.config.ProviderHealthConfig;
import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.ProviderConfigView;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import io.gateway.oss.core.upstream.ProviderHealthService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ProviderHealthScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthScheduler.class);

    private final GatewayConfigView configView;
    private final ProviderHealthService providerHealthService;
    private final ProviderRuntimeStateStore runtimeStateStore;
    private final ProviderDiscoveryService discoveryService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ProviderHealthScheduler(GatewayConfigView configView,
                                   ProviderHealthService providerHealthService,
                                   ProviderRuntimeStateStore runtimeStateStore,
                                   ProviderDiscoveryService discoveryService) {
        this.configView = configView;
        this.providerHealthService = providerHealthService;
        this.runtimeStateStore = runtimeStateStore;
        this.discoveryService = discoveryService;
    }

    @PostConstruct
    public void start() {
        ProviderHealthConfig config = configView.getProviderHealth();
        if (!config.isEnabled()) {
            return;
        }
        if (config.isRunOnStartup()) {
            refreshAll("startup");
        }
        long intervalMs = Math.max(1000L, config.getRefreshInterval().toMillis());
        scheduler.scheduleWithFixedDelay(() -> refreshAll("scheduled"), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void refreshAll(String trigger) {
        Map<String, ? extends ProviderConfigView> providers = configView.getProviders();
        for (Map.Entry<String, ? extends ProviderConfigView> entry : providers.entrySet()) {
            refreshProvider(entry.getKey(), entry.getValue(), trigger)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnError(error -> log.warn("provider_health_check_failed trigger={} provider={} cause={}", trigger, entry.getKey(), error.toString()))
                    .onErrorResume(error -> Mono.empty())
                    .subscribe();
        }
    }

    public Mono<Void> refreshProvider(String providerName, ProviderConfigView providerConfig, String trigger) {
        if (providerConfig == null || !providerConfig.isEnabled()) {
            return Mono.empty();
        }
        String key = firstApiKey(providerConfig);
        if (key == null) {
            return Mono.empty();
        }
        ProviderRuntimeStateStore.ProviderRuntimeState previous = runtimeStateStore.get(providerName);
        return providerHealthService
                .test(providerConfig.getBaseUrl(), key, providerConfig.getTimeout())
                .flatMap(result -> {
                    Instant now = Instant.now();
                    boolean success = "ok".equalsIgnoreCase(result.status());
                    int consecutiveFailures = success ? 0 : previous.consecutiveFailures() + 1;
                    int consecutiveSuccesses = success ? previous.consecutiveSuccesses() + 1 : 0;

                    ProviderHealthConfig config = configView.getProviderHealth();
                    boolean runtimeAvailable = previous.runtimeAvailable();
                    if (!success && consecutiveFailures >= config.getDisableAfterConsecutiveFailures()) {
                        runtimeAvailable = false;
                    }
                    if (success && consecutiveSuccesses >= config.getRecoverAfterConsecutiveSuccesses()) {
                        runtimeAvailable = true;
                    }

                    boolean finalRuntimeAvailable = runtimeAvailable;
                    // 状态写回（Redis/JDBC 阻塞调用）移到 boundedElastic，避免占用
                    // 探活响应线程（审查 C4）
                    return Mono.fromRunnable(() -> {
                        runtimeStateStore.save(providerName, new ProviderRuntimeStateStore.ProviderRuntimeState(
                                finalRuntimeAvailable,
                                now,
                                success ? now : previous.lastSuccessAt(),
                                consecutiveFailures,
                                consecutiveSuccesses,
                                result.httpStatus(),
                                result.latencyMs(),
                                success ? null : result.error()
                        ));

                        discoveryService.updateProvider(providerName, new ProviderDiscoveryService.ProviderDiscovery(
                                "provider-health-check",
                                success ? "ok" : "error",
                                now,
                                modelsFor(providerName),
                                success ? null : result.error()
                        ));

                        if (previous.runtimeAvailable() != finalRuntimeAvailable || !success) {
                            log.info("provider_health_checked trigger={} provider={} status={} runtimeAvailable={} latencyMs={}",
                                    trigger, providerName, result.status(), finalRuntimeAvailable, result.latencyMs());
                        } else if (log.isDebugEnabled()) {
                            log.debug("provider_health_checked trigger={} provider={} status={} runtimeAvailable={} latencyMs={}",
                                    trigger, providerName, result.status(), finalRuntimeAvailable, result.latencyMs());
                        }
                    }).subscribeOn(Schedulers.boundedElastic());
                })
                .then();
    }

    private List<String> modelsFor(String providerName) {
        List<String> models = new ArrayList<>();
        configView.getRoutes().forEach((routeId, route) -> {
            if (providerName.equals(route.getProvider())) {
                models.add(routeId);
            }
        });
        return List.copyOf(models);
    }

    private String firstApiKey(ProviderConfigView providerConfig) {
        if (providerConfig.getKeys() != null) {
            for (String key : providerConfig.getKeys()) {
                if (key != null && !key.isBlank()) {
                    return key;
                }
            }
        }
        if (providerConfig.getApiKey() == null || providerConfig.getApiKey().isBlank()) {
            return null;
        }
        return providerConfig.getApiKey();
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
