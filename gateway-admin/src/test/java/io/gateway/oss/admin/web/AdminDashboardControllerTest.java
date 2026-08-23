package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.when;

@WebFluxTest(useDefaultFilters = false)
@Import({AdminDashboardController.class, GlobalExceptionHandler.class})
class AdminDashboardControllerTest {

    private static final String ADMIN_BEARER_TOKEN = "Bearer admin-token";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ClientAuthService clientAuthService;

    @MockBean
    private AdminDashboardOverviewService adminDashboardOverviewService;

    @MockBean
    private GatewayMetricsRecorder gatewayMetricsRecorder;

    @MockBean
    private WebTestCleanupSupport webTestCleanupSupport;

    @BeforeEach
    void setUpAuth() {
        when(clientAuthService.authenticate(ADMIN_BEARER_TOKEN))
                .thenReturn(new ClientPrincipal("admin", null, "admin"));
    }

    @Test
    void shouldRejectInvalidDayParameter() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/admin/dashboard/overview")
                        .queryParam("day", "2026-99-99")
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("Invalid day date, expected YYYY-MM-DD");
    }

    @Test
    void shouldRejectMalformedDayParameter() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/admin/dashboard/overview")
                        .queryParam("day", "invalid")
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isBadRequest();
    }
}
