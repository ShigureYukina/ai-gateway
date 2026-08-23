package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.security.AuthorizationService;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import io.gateway.oss.core.web.GlobalExceptionHandler;
import io.gateway.oss.core.web.OperationalGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@WebFluxTest(useDefaultFilters = false)
@Import({InternalSystemStatusController.class, InternalEndpointAuthFilter.class, GlobalExceptionHandler.class})
class InternalSystemStatusControllerTest {

    private static final String ADMIN_BEARER_TOKEN = "Bearer admin-token";
    private static final ClientPrincipal ADMIN_PRINCIPAL = new ClientPrincipal("admin", null, "admin");
    private static final ProviderRuntimeStateStore.ProviderRuntimeState AVAILABLE_PROVIDER =
            new ProviderRuntimeStateStore.ProviderRuntimeState(true, Instant.now(), Instant.now(), 0, 1, 200, 15L, null);

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private OperationalGateService operationalGateService;

    @MockBean
    private GatewayConfigView configView;

    @MockBean
    private ProviderRuntimeStateStore providerRuntimeStateStore;

    @MockBean
    private AuthorizationService authorizationService;

    @MockBean
    private ClientAuthService clientAuthService;

    @MockBean
    private GatewayMetricsRecorder gatewayMetricsRecorder;

    @MockBean
    private WebTestCleanupSupport webTestCleanupSupport;

    @BeforeEach
    void setUp() {
        when(clientAuthService.authenticate(ADMIN_BEARER_TOKEN)).thenReturn(ADMIN_PRINCIPAL);
        when(operationalGateService.snapshot()).thenReturn(new OperationalGateService.OperationalGateState(false, false, 0, 0, 0));
        doReturn(Map.of("openai-primary", enabledRoute("openai"))).when(configView).getRoutes();
        when(providerRuntimeStateStore.get("openai")).thenReturn(AVAILABLE_PROVIDER);
    }

    @Test
    void systemStatus_shouldReturn200WithExpectedStructure() {
        webTestClient.get().uri("/internal/system/status")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").isNotEmpty()
                .jsonPath("$.maintenance").exists()
                .jsonPath("$.maintenance.active").isBoolean()
                .jsonPath("$.emergencyRateLimit").exists()
                .jsonPath("$.emergencyRateLimit.enabled").isBoolean()
                .jsonPath("$.emergencyRateLimit.maxRequestsPerMinute").isNumber()
                .jsonPath("$.emergencyRateLimit.currentWindowCount").isNumber()
                .jsonPath("$.globalCircuit").exists()
                .jsonPath("$.globalCircuit.hasAvailableRoute").isBoolean();
    }

    @Test
    void systemStatus_maintenanceModeActive_shouldShowTrue() {
        when(operationalGateService.snapshot()).thenReturn(new OperationalGateService.OperationalGateState(true, false, 0, 0, 0));

        webTestClient.get().uri("/internal/system/status")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.maintenance.active").isEqualTo(true);
    }

    @Test
    void systemStatus_emergencyRateLimitEnabled_shouldShowConfig() {
        when(operationalGateService.snapshot()).thenReturn(new OperationalGateService.OperationalGateState(false, true, 50, 0, 0));

        webTestClient.get().uri("/internal/system/status")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.emergencyRateLimit.enabled").isEqualTo(true)
                .jsonPath("$.emergencyRateLimit.maxRequestsPerMinute").isEqualTo(50);
    }

    @Test
    void systemStatus_globalCircuit_shouldReflectRouteAvailability() {
        webTestClient.get().uri("/internal/system/status")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.globalCircuit.hasAvailableRoute").isEqualTo(true);
    }

    @Test
    void systemStatus_noRoutes_shouldShowFalse() {
        doReturn(Map.of()).when(configView).getRoutes();

        webTestClient.get().uri("/internal/system/status")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.globalCircuit.hasAvailableRoute").isEqualTo(false);
    }

    @Test
    void systemStatus_noAuth_shouldReturn401() {
        webTestClient.get().uri("/internal/system/status")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private RouteConfigView enabledRoute(String providerName) {
        RouteConfigView route = mock(RouteConfigView.class);
        when(route.isEnabled()).thenReturn(true);
        when(route.getProvider()).thenReturn(providerName);
        return route;
    }
}
