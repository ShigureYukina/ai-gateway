package io.gateway.oss.admin.sync;

import io.gateway.oss.core.config.ModelsDevConfig;
import io.gateway.oss.core.contract.GatewayConfigView;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ModelsDevSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ModelsDevSyncScheduler.class);

    private final GatewayConfigView configView;
    private final ModelsDevSyncService syncService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ModelsDevSyncScheduler(GatewayConfigView configView,
                                  ModelsDevSyncService syncService) {
        this.configView = configView;
        this.syncService = syncService;
    }

    @PostConstruct
    public void initialSync() {
        ModelsDevConfig config = configView.getSync().getModelsDev();
        if (!config.isEnabled()) {
            return;
        }

        if (config.isRunOnStartup()) {
            syncService.syncOnceReactive("startup")
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }

        long intervalMs = refreshIntervalMillis();
        scheduler.scheduleWithFixedDelay(this::scheduledSync, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public long refreshIntervalMillis() {
        ModelsDevConfig config = configView.getSync().getModelsDev();
        if (config.getRefreshInterval() == null) {
            return 30 * 60 * 1000L;
        }
        return Math.max(1000L, config.getRefreshInterval().toMillis());
    }

    public void scheduledSync() {
        ModelsDevConfig config = configView.getSync().getModelsDev();
        if (!config.isEnabled()) {
            return;
        }
        syncService.syncOnceReactive("scheduled")
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
