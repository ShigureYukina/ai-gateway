package io.gateway.oss.admin.sync;

import io.gateway.oss.core.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelsDevSyncSchedulerTest {

    @Test
    void shouldRunInitialSyncWhenEnabledAndStartupOn() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setEnabled(true);
        properties.getSync().getModelsDev().setRunOnStartup(true);
        ModelsDevSyncService syncService = mock(ModelsDevSyncService.class);
        when(syncService.syncOnceReactive("startup")).thenReturn(Mono.just(true));

        ModelsDevSyncScheduler scheduler = new ModelsDevSyncScheduler(properties, syncService);
        scheduler.initialSync();

        verify(syncService, times(1)).syncOnceReactive("startup");
    }

    @Test
    void shouldNotRunInitialSyncWhenDisabled() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setEnabled(false);
        ModelsDevSyncService syncService = mock(ModelsDevSyncService.class);

        ModelsDevSyncScheduler scheduler = new ModelsDevSyncScheduler(properties, syncService);
        scheduler.initialSync();

        verify(syncService, never()).syncOnceReactive("startup");
    }

    @Test
    void shouldRunScheduledSyncWhenEnabled() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setEnabled(true);
        ModelsDevSyncService syncService = mock(ModelsDevSyncService.class);
        when(syncService.syncOnceReactive("scheduled")).thenReturn(Mono.just(true));

        ModelsDevSyncScheduler scheduler = new ModelsDevSyncScheduler(properties, syncService);
        scheduler.scheduledSync();

        verify(syncService, times(1)).syncOnceReactive("scheduled");
    }
}
