package io.gateway.oss.admin.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.ProviderConfig;
import io.gateway.oss.core.config.ProviderHealthConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.core.upstream.ProviderHealthService;
import io.gateway.oss.core.upstream.ProviderHealthService.ProviderTestResult;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore.ProviderRuntimeState;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderHealthSchedulerTest {

    @Mock
    private GatewayProperties properties;

    @Mock
    private ProviderHealthService providerHealthService;

    @Mock
    private ProviderRuntimeStateStore runtimeStateStore;

    @Mock
    private ProviderDiscoveryService discoveryService;

    private ProviderHealthScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void refreshProvider_shouldUpdateRuntimeStateWhenHealthCheckSucceeds() {
        ProviderHealthConfig healthConfig = healthConfig(true, 3, 2);
        ProviderConfig providerConfig = enabledProvider("https://api.example.com", "test-key");

        when(properties.getProviderHealth()).thenReturn(healthConfig);
        when(properties.getRoutes()).thenReturn(routesForProvider("test", "model-a", "model-b"));
        when(providerHealthService.test(anyString(), anyString(), any()))
                .thenReturn(Mono.just(new ProviderTestResult("ok", 50L, 200, null)));
        when(runtimeStateStore.get("test"))
                .thenReturn(new ProviderRuntimeState(true, Instant.now(), Instant.now(), 1, 0, 500, 80L, "old-error"));

        scheduler = new ProviderHealthScheduler(properties, providerHealthService, runtimeStateStore, discoveryService);

        StepVerifier.create(scheduler.refreshProvider("test", providerConfig, "manual"))
                .verifyComplete();

        ArgumentCaptor<ProviderRuntimeState> stateCaptor = ArgumentCaptor.forClass(ProviderRuntimeState.class);
        verify(runtimeStateStore).save(org.mockito.Mockito.eq("test"), stateCaptor.capture());
        ProviderRuntimeState saved = stateCaptor.getValue();
        assertTrue(saved.runtimeAvailable());
        assertEquals(0, saved.consecutiveFailures());
        assertEquals(1, saved.consecutiveSuccesses());
        assertEquals(200, saved.httpStatus());
        assertEquals(50L, saved.latencyMs());
        assertNull(saved.reason());
        assertNotNull(saved.lastCheckedAt());
        assertNotNull(saved.lastSuccessAt());

        ArgumentCaptor<ProviderDiscoveryService.ProviderDiscovery> discoveryCaptor = ArgumentCaptor.forClass(ProviderDiscoveryService.ProviderDiscovery.class);
        verify(discoveryService).updateProvider(org.mockito.Mockito.eq("test"), discoveryCaptor.capture());
        assertEquals("ok", discoveryCaptor.getValue().status());
        assertEquals(List.of("model-a", "model-b"), discoveryCaptor.getValue().models());
    }

    @Test
    void refreshProvider_shouldIncrementConsecutiveFailuresWhenHealthCheckFails() {
        ProviderHealthConfig healthConfig = healthConfig(true, 3, 2);
        ProviderConfig providerConfig = enabledProvider("https://api.example.com", "test-key");
        Instant lastSuccessAt = Instant.now().minusSeconds(60);

        when(properties.getProviderHealth()).thenReturn(healthConfig);
        when(properties.getRoutes()).thenReturn(Map.of());
        when(providerHealthService.test(anyString(), anyString(), any()))
                .thenReturn(Mono.just(new ProviderTestResult("error", 90L, 503, "boom")));
        when(runtimeStateStore.get("test"))
                .thenReturn(new ProviderRuntimeState(true, Instant.now(), lastSuccessAt, 1, 1, 200, 20L, null));

        scheduler = new ProviderHealthScheduler(properties, providerHealthService, runtimeStateStore, discoveryService);

        StepVerifier.create(scheduler.refreshProvider("test", providerConfig, "manual"))
                .verifyComplete();

        ArgumentCaptor<ProviderRuntimeState> stateCaptor = ArgumentCaptor.forClass(ProviderRuntimeState.class);
        verify(runtimeStateStore).save(org.mockito.Mockito.eq("test"), stateCaptor.capture());
        ProviderRuntimeState saved = stateCaptor.getValue();
        assertTrue(saved.runtimeAvailable());
        assertEquals(2, saved.consecutiveFailures());
        assertEquals(0, saved.consecutiveSuccesses());
        assertEquals(lastSuccessAt, saved.lastSuccessAt());
        assertEquals(503, saved.httpStatus());
        assertEquals(90L, saved.latencyMs());
        assertEquals("boom", saved.reason());
    }

    @Test
    void refreshProvider_shouldDisableProviderAfterConsecutiveFailuresThreshold() {
        ProviderHealthConfig healthConfig = healthConfig(true, 3, 2);
        ProviderConfig providerConfig = enabledProvider("https://api.example.com", "test-key");

        when(properties.getProviderHealth()).thenReturn(healthConfig);
        when(properties.getRoutes()).thenReturn(Map.of());
        when(providerHealthService.test(anyString(), anyString(), any()))
                .thenReturn(Mono.just(new ProviderTestResult("error", 75L, 500, "HTTP 500")));
        when(runtimeStateStore.get("test"))
                .thenReturn(new ProviderRuntimeState(true, Instant.now(), Instant.now(), 2, 0, 500, 70L, "old"));

        scheduler = new ProviderHealthScheduler(properties, providerHealthService, runtimeStateStore, discoveryService);

        StepVerifier.create(scheduler.refreshProvider("test", providerConfig, "manual"))
                .verifyComplete();

        ArgumentCaptor<ProviderRuntimeState> stateCaptor = ArgumentCaptor.forClass(ProviderRuntimeState.class);
        verify(runtimeStateStore).save(org.mockito.Mockito.eq("test"), stateCaptor.capture());
        assertFalse(stateCaptor.getValue().runtimeAvailable());
        assertEquals(3, stateCaptor.getValue().consecutiveFailures());
    }

    @Test
    void refreshProvider_shouldRecoverAfterConsecutiveSuccessesThreshold() {
        ProviderHealthConfig healthConfig = healthConfig(true, 3, 2);
        ProviderConfig providerConfig = enabledProvider("https://api.example.com", "test-key");

        when(properties.getProviderHealth()).thenReturn(healthConfig);
        when(properties.getRoutes()).thenReturn(Map.of());
        when(providerHealthService.test(anyString(), anyString(), any()))
                .thenReturn(Mono.just(new ProviderTestResult("ok", 40L, 200, null)));
        when(runtimeStateStore.get("test"))
                .thenReturn(new ProviderRuntimeState(false, Instant.now(), Instant.now().minusSeconds(30), 0, 1, 503, 90L, "down"));

        scheduler = new ProviderHealthScheduler(properties, providerHealthService, runtimeStateStore, discoveryService);

        StepVerifier.create(scheduler.refreshProvider("test", providerConfig, "manual"))
                .verifyComplete();

        ArgumentCaptor<ProviderRuntimeState> stateCaptor = ArgumentCaptor.forClass(ProviderRuntimeState.class);
        verify(runtimeStateStore).save(org.mockito.Mockito.eq("test"), stateCaptor.capture());
        assertTrue(stateCaptor.getValue().runtimeAvailable());
        assertEquals(2, stateCaptor.getValue().consecutiveSuccesses());
        assertEquals(0, stateCaptor.getValue().consecutiveFailures());
    }

    @Test
    void refreshProvider_shouldSkipDisabledProviders() {
        ProviderConfig providerConfig = enabledProvider("https://api.example.com", "test-key");
        providerConfig.setEnabled(false);
        scheduler = new ProviderHealthScheduler(properties, providerHealthService, runtimeStateStore, discoveryService);

        StepVerifier.create(scheduler.refreshProvider("test", providerConfig, "manual"))
                .verifyComplete();

        verify(providerHealthService, never()).test(anyString(), anyString(), any());
        verify(runtimeStateStore, never()).save(anyString(), any());
    }

    @Test
    void refreshProvider_shouldSkipProvidersWithNoApiKey() {
        ProviderConfig providerConfig = enabledProvider("https://api.example.com", null);
        providerConfig.setKeys(java.util.Arrays.asList(" ", null));
        scheduler = new ProviderHealthScheduler(properties, providerHealthService, runtimeStateStore, discoveryService);

        StepVerifier.create(scheduler.refreshProvider("test", providerConfig, "manual"))
                .verifyComplete();

        verify(providerHealthService, never()).test(anyString(), anyString(), any());
        verify(runtimeStateStore, never()).get(anyString());
        verify(runtimeStateStore, never()).save(anyString(), any());
    }

    @Test
    void refreshAll_shouldIterateAllProviders() {
        ProviderHealthConfig healthConfig = healthConfig(true, 3, 2);
        ProviderConfig providerA = enabledProvider("https://a.example.com", "key-a");
        ProviderConfig providerB = enabledProvider("https://b.example.com", "key-b");

        when(properties.getProviderHealth()).thenReturn(healthConfig);
        when(properties.getProviders()).thenReturn(Map.of("a", providerA, "b", providerB));
        when(properties.getRoutes()).thenReturn(Map.of());
        when(providerHealthService.test(anyString(), anyString(), any()))
                .thenReturn(Mono.just(new ProviderTestResult("ok", 50L, 200, null)));
        when(runtimeStateStore.get(anyString()))
                .thenReturn(new ProviderRuntimeState(true, Instant.now(), Instant.now(), 0, 0, 200, 50L, null));

        scheduler = new ProviderHealthScheduler(properties, providerHealthService, runtimeStateStore, discoveryService);
        scheduler.refreshAll("scheduled");

        Awaitility.await().untilAsserted(() -> {
            verify(providerHealthService, times(2)).test(anyString(), anyString(), any());
            verify(runtimeStateStore, times(2)).save(anyString(), any());
        });
    }

    @Test
    void start_shouldNotScheduleWhenHealthCheckDisabled() {
        ProviderHealthConfig healthConfig = healthConfig(false, 3, 2);
        when(properties.getProviderHealth()).thenReturn(healthConfig);

        scheduler = new ProviderHealthScheduler(properties, providerHealthService, runtimeStateStore, discoveryService);
        scheduler.start();

        verify(properties).getProviderHealth();
        verify(properties, never()).getProviders();
        verify(providerHealthService, never()).test(anyString(), anyString(), any());
    }

    private static ProviderHealthConfig healthConfig(boolean enabled, int disableAfterFailures, int recoverAfterSuccesses) {
        ProviderHealthConfig config = new ProviderHealthConfig();
        config.setEnabled(enabled);
        config.setRunOnStartup(false);
        config.setRefreshInterval(Duration.ofMinutes(5));
        config.setDisableAfterConsecutiveFailures(disableAfterFailures);
        config.setRecoverAfterConsecutiveSuccesses(recoverAfterSuccesses);
        return config;
    }

    private static ProviderConfig enabledProvider(String baseUrl, String apiKey) {
        ProviderConfig providerConfig = new ProviderConfig();
        providerConfig.setBaseUrl(baseUrl);
        providerConfig.setApiKey(apiKey);
        providerConfig.setTimeout(Duration.ofSeconds(5));
        providerConfig.setEnabled(true);
        return providerConfig;
    }

    private static Map<String, RouteConfig> routesForProvider(String provider, String... routeIds) {
        java.util.LinkedHashMap<String, RouteConfig> routes = new java.util.LinkedHashMap<>();
        for (String routeId : routeIds) {
            RouteConfig routeConfig = new RouteConfig();
            routeConfig.setProvider(provider);
            routes.put(routeId, routeConfig);
        }
        return routes;
    }
}
