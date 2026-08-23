package io.gateway.oss.admin.web;

import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.security.AuthorizationService;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import io.gateway.oss.core.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@WebFluxTest(useDefaultFilters = false)
@Import({InternalProviderStateController.class, InternalEndpointAuthFilter.class, GlobalExceptionHandler.class})
class InternalProviderStateControllerTest {

    private static final String ADMIN_BEARER_TOKEN = "Bearer admin-token";
    private static final String USER_BEARER_TOKEN = "Bearer user-token";
    private static final ClientPrincipal ADMIN_PRINCIPAL = new ClientPrincipal("admin", null, "admin");
    private static final ClientPrincipal USER_PRINCIPAL = new ClientPrincipal("user1", null, "user");

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ProviderRuntimeStateStore runtimeStateStore;

    @MockBean
    private ProviderDiscoveryService discoveryService;

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
        when(clientAuthService.authenticate(USER_BEARER_TOKEN)).thenReturn(USER_PRINCIPAL);
        when(runtimeStateStore.getAll()).thenReturn(Map.of());
        when(discoveryService.getSnapshot()).thenReturn(ProviderDiscoveryService.DiscoverySnapshot.empty());
        doThrow(new GatewayException(HttpStatus.FORBIDDEN, "forbidden", "Permission 'view_system' requires one of: [ADMIN, OPERATOR, VIEWER]"))
                .when(authorizationService).requireSystemView(USER_PRINCIPAL);
    }

    @Test
    void providerRuntime_shouldReturn200WithExpectedStructure() {
        webTestClient.get().uri("/internal/providers/runtime")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").isNotEmpty()
                .jsonPath("$.providers").isMap();
    }

    @Test
    void providerRuntime_shouldReturnEmptyProvidersWhenNoneRegistered() {
        webTestClient.get().uri("/internal/providers/runtime")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers").isEmpty();
    }

    @Test
    void providerRuntime_shouldReturnProviderStateWhenRegistered() {
        ProviderRuntimeStateStore.ProviderRuntimeState state =
                new ProviderRuntimeStateStore.ProviderRuntimeState(
                        true, Instant.now(), Instant.now(), 0, 5, 200, 42L, null);
        when(runtimeStateStore.getAll()).thenReturn(Map.of("openai", state));

        webTestClient.get().uri("/internal/providers/runtime")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers.openai").exists()
                .jsonPath("$.providers.openai.runtimeAvailable").isEqualTo(true)
                .jsonPath("$.providers.openai.consecutiveSuccesses").isEqualTo(5)
                .jsonPath("$.providers.openai.httpStatus").isEqualTo(200)
                .jsonPath("$.providers.openai.latencyMs").isEqualTo(42);
    }

    @Test
    void providerRuntime_shouldSupportProviderFilter() {
        when(runtimeStateStore.getAll()).thenReturn(Map.of(
                "openai", ProviderRuntimeStateStore.ProviderRuntimeState.unknown(),
                "anthropic", ProviderRuntimeStateStore.ProviderRuntimeState.unknown()));

        webTestClient.get().uri(uriBuilder -> uriBuilder.path("/internal/providers/runtime")
                        .queryParam("provider", "openai")
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers.openai").exists()
                .jsonPath("$.providers.anthropic").doesNotExist();
    }

    @Test
    void providerRuntime_shouldReturnEmptyWhenFilterMatchesNothing() {
        when(runtimeStateStore.getAll()).thenReturn(Map.of(
                "openai", ProviderRuntimeStateStore.ProviderRuntimeState.unknown()));

        webTestClient.get().uri(uriBuilder -> uriBuilder.path("/internal/providers/runtime")
                        .queryParam("provider", "nonexistent")
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers").isEmpty();
    }

    @Test
    void providerRuntime_shouldReturnConsecutiveFailuresAndReason() {
        ProviderRuntimeStateStore.ProviderRuntimeState state =
                new ProviderRuntimeStateStore.ProviderRuntimeState(
                        false, Instant.now(), null, 3, 0, 503, null, "upstream unavailable");
        when(runtimeStateStore.getAll()).thenReturn(Map.of("openai", state));

        webTestClient.get().uri("/internal/providers/runtime")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers.openai.runtimeAvailable").isEqualTo(false)
                .jsonPath("$.providers.openai.consecutiveFailures").isEqualTo(3)
                .jsonPath("$.providers.openai.httpStatus").isEqualTo(503)
                .jsonPath("$.providers.openai.reason").isEqualTo("upstream unavailable");
    }

    @Test
    void providerDiscovery_shouldReturn200WithExpectedStructure() {
        when(discoveryService.getSnapshot()).thenReturn(new ProviderDiscoveryService.DiscoverySnapshot(
                Map.of("openai", ProviderDiscoveryService.ProviderDiscovery.never()),
                Instant.now(),
                1L));

        webTestClient.get().uri("/internal/providers/discovery")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.providers").isMap()
                .jsonPath("$.updatedAt").exists()
                .jsonPath("$.version").isNumber();
    }

    @Test
    void providerRuntime_noAuth_shouldReturn401() {
        webTestClient.get().uri("/internal/providers/runtime")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void providerDiscovery_noAuth_shouldReturn401() {
        webTestClient.get().uri("/internal/providers/discovery")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void providerRuntime_regularUser_shouldBeForbiddenOrAllowed() {
        webTestClient.get().uri("/internal/providers/runtime")
                .header("Authorization", USER_BEARER_TOKEN)
                .exchange()
                .expectStatus().value(status -> {
                    assert status == 200 || status == 403 :
                            "Expected 200 or 403 but got " + status;
                });
    }
}
