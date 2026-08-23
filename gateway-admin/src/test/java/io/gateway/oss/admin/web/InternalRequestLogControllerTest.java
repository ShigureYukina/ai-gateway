package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.RequestLogService.RequestLogEntry;
import io.gateway.oss.core.observability.TraceRecord;
import io.gateway.oss.core.observability.TraceStore;
import io.gateway.oss.core.security.AuthorizationService;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@WebFluxTest(useDefaultFilters = false)
@Import({InternalRequestLogController.class, RequestLogQueryService.class, InternalEndpointAuthFilter.class, GlobalExceptionHandler.class})
class InternalRequestLogControllerTest {

    private static final String ADMIN_BEARER_TOKEN = "Bearer admin-token";
    private static final String VIEWER_BEARER_TOKEN = "Bearer viewer-token";
    private static final String USER_BEARER_TOKEN = "Bearer user-token";
    private static final ClientPrincipal ADMIN_PRINCIPAL = new ClientPrincipal("admin", null, "admin");
    private static final ClientPrincipal VIEWER_PRINCIPAL = new ClientPrincipal("viewer", null, "viewer");
    private static final ClientPrincipal USER_PRINCIPAL = new ClientPrincipal("user1", null, "user");

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private RequestLogService requestLogService;

    @MockBean
    private TraceStore traceStore;

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
        when(clientAuthService.authenticate(VIEWER_BEARER_TOKEN)).thenReturn(VIEWER_PRINCIPAL);
        when(clientAuthService.authenticate(USER_BEARER_TOKEN)).thenReturn(USER_PRINCIPAL);

        when(authorizationService.isAdminOrOperator(ADMIN_PRINCIPAL)).thenReturn(true);
        when(authorizationService.isAdminOrOperator(VIEWER_PRINCIPAL)).thenReturn(false);
        when(authorizationService.isAdminOrOperator(USER_PRINCIPAL)).thenReturn(false);

        doThrow(new GatewayException(HttpStatus.FORBIDDEN, "forbidden",
                "Permission 'view_system' requires one of: [ADMIN, OPERATOR, VIEWER]"))
                .when(authorizationService).requireSystemView(USER_PRINCIPAL);

        when(requestLogService.getRecent(1000)).thenReturn(List.of());
        when(requestLogService.getByRequestId(any())).thenReturn(Mono.empty());
    }

    @Test
    void shouldReturnEmptyRecentRequestsWhenNoTraffic() {
        webTestClient.get()
                .uri("/internal/requests/recent")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.requests").isArray()
                .jsonPath("$.requests.length()").isEqualTo(0);
    }

    @Test
    void shouldReturn404ForUnknownRequestId() {
        when(traceStore.getByRequestId("unknown-id")).thenReturn(null);

        webTestClient.get()
                .uri("/internal/requests/unknown-id")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldFilterByStatus() {
        Instant now = Instant.now();
        when(requestLogService.getRecent(1000)).thenReturn(List.of(
                entry("r-200", 200, now.minusSeconds(3)),
                entry("r-500", 500, now.minusSeconds(2)),
                entry("r-201", 200, now.minusSeconds(1))
        ));

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/requests/recent")
                        .queryParam("status", 200)
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests.length()").isEqualTo(2)
                .jsonPath("$.requests[0].status").isEqualTo(200)
                .jsonPath("$.requests[1].status").isEqualTo(200);
    }

    @Test
    void shouldRejectLimitAboveMaximum() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/requests/recent")
                        .queryParam("limit", 501)
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void shouldRejectNegativeOffset() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/requests/recent")
                        .queryParam("offset", -1)
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void shouldFilterByTimeRange() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");
        when(requestLogService.getRecent(1000)).thenReturn(List.of(
                entry("old", 200, base.minusSeconds(10)),
                entry("in", 200, base.plusSeconds(10)),
                entry("new", 200, base.plusSeconds(40))
        ));

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/requests/recent")
                        .queryParam("from", base.toString())
                        .queryParam("to", base.plusSeconds(30).toString())
                        .build())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests.length()").isEqualTo(1)
                .jsonPath("$.requests[0].requestId").isEqualTo("in");
    }

    @Test
    void shouldReturnEntryByRequestId() {
        RequestLogEntry persisted = entry("persisted-id", 200, Instant.parse("2026-01-01T00:00:00Z"));
        when(traceStore.getByRequestId("persisted-id")).thenReturn(null);
        when(requestLogService.getByRequestId("persisted-id")).thenReturn(Mono.just(persisted));

        webTestClient.get()
                .uri("/internal/requests/{id}", persisted.requestId())
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.request.requestId").isEqualTo("persisted-id")
                .jsonPath("$.request.status").isEqualTo(200);
    }

    @Test
    void shouldReturnExpandedTraceDetailsForRequest() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        RequestLogEntry request = new RequestLogEntry(
                "trace-id",
                "cli***aa",
                "client-secret-key",
                "gpt-test",
                "provider-a",
                "route-a",
                "scene-a",
                200,
                123,
                timestamp,
                "non-stream",
                10L,
                7L,
                3L,
                0.001,
                null
        );
        TraceRecord trace = new TraceRecord(
                "trace-id",
                "cli***aa",
                "gpt-test",
                "provider-a",
                "route-a",
                "scene-a",
                200,
                "non-stream",
                123L,
                null,
                "{\"messages\":[{\"content\":\"hello\"}]}",
                "{\"id\":\"resp-1\"}",
                timestamp
        );
        when(traceStore.getByRequestId("trace-id")).thenReturn(trace);
        when(requestLogService.getByRequestId("trace-id")).thenReturn(Mono.just(request));

        webTestClient.get()
                .uri("/internal/requests/trace-id")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.request.requestId").isEqualTo("trace-id")
                .jsonPath("$.request.status").isEqualTo(200)
                .jsonPath("$.request.clientId").isEqualTo("cli***aa")
                .jsonPath("$.request.clientKey").doesNotExist()
                .jsonPath("$.trace.requestId").isEqualTo("trace-id")
                .jsonPath("$.trace.clientId").isEqualTo("cli***aa")
                .jsonPath("$.trace.model").isEqualTo("gpt-test")
                .jsonPath("$.trace.provider").isEqualTo("provider-a")
                .jsonPath("$.trace.routeId").isEqualTo("route-a")
                .jsonPath("$.trace.scene").isEqualTo("scene-a")
                .jsonPath("$.trace.status").isEqualTo(200)
                .jsonPath("$.trace.streamMode").isEqualTo("non-stream")
                .jsonPath("$.trace.latencyMs").isEqualTo(123)
                .jsonPath("$.trace.requestBody").isEqualTo("{\"messages\":[{\"content\":\"hello\"}]}")
                .jsonPath("$.trace.responseBody").isEqualTo("{\"id\":\"resp-1\"}");
    }

    @Test
    void shouldLookupFailedRequestByRequestIdWithTraceErrorDetailsForDiagnosis() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:30Z");
        RequestLogEntry request = new RequestLogEntry(
                "failed-req-id",
                "cli***aa",
                "client-a",
                "gpt-test",
                "provider-a",
                "route-a",
                "scene-a",
                502,
                321,
                timestamp,
                "streaming",
                null,
                null,
                null,
                null,
                "upstream_error"
        );
        TraceRecord trace = new TraceRecord(
                "failed-req-id",
                "cli***aa",
                "gpt-test",
                "provider-a",
                "route-a",
                "scene-a",
                502,
                "streaming",
                321L,
                "upstream stream interrupted",
                "{\"messages\":[{\"content\":\"hello\"}]}",
                null,
                timestamp
        );
        when(traceStore.getByRequestId("failed-req-id")).thenReturn(trace);
        when(requestLogService.getByRequestId("failed-req-id")).thenReturn(Mono.just(request));

        webTestClient.get()
                .uri("/internal/requests/failed-req-id")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.request.requestId").isEqualTo("failed-req-id")
                .jsonPath("$.request.clientId").isEqualTo("cli***aa")
                .jsonPath("$.request.clientKey").doesNotExist()
                .jsonPath("$.request.status").isEqualTo(502)
                .jsonPath("$.request.provider").isEqualTo("provider-a")
                .jsonPath("$.request.routeId").isEqualTo("route-a")
                .jsonPath("$.request.errorMessage").isEqualTo("upstream_error")
                .jsonPath("$.trace.requestId").isEqualTo("failed-req-id")
                .jsonPath("$.trace.status").isEqualTo(502)
                .jsonPath("$.trace.provider").isEqualTo("provider-a")
                .jsonPath("$.trace.routeId").isEqualTo("route-a")
                .jsonPath("$.trace.errorMessage").isEqualTo("upstream stream interrupted");
    }

    @Test
    void shouldHideTraceBodiesForViewerButKeepMetadata() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        when(requestLogService.getByRequestId("viewer-trace-id")).thenReturn(Mono.just(entry("viewer-trace-id", 200, timestamp)));
        when(traceStore.getByRequestId("viewer-trace-id")).thenReturn(new TraceRecord(
                "viewer-trace-id",
                "cli***aa",
                "gpt-test",
                "provider-a",
                "route-a",
                "scene-a",
                200,
                "non-stream",
                123L,
                null,
                "{\"messages\":[{\"content\":\"[REDACTED]\"}]}",
                "{\"id\":\"resp-1\",\"output\":\"[REDACTED]\"}",
                timestamp
        ));

        webTestClient.get()
                .uri("/internal/requests/viewer-trace-id")
                .header("Authorization", VIEWER_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.trace.requestId").isEqualTo("viewer-trace-id")
                .jsonPath("$.trace.provider").isEqualTo("provider-a")
                .jsonPath("$.trace.routeId").isEqualTo("route-a")
                .jsonPath("$.trace.requestBody").isEmpty()
                .jsonPath("$.trace.responseBody").isEmpty();
    }

    @Test
    void shouldRejectCallerWithoutSystemViewPermission() {
        when(traceStore.getByRequestId("forbidden-trace-id")).thenReturn(null);

        webTestClient.get()
                .uri("/internal/requests/forbidden-trace-id")
                .header("Authorization", USER_BEARER_TOKEN)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void shouldShowPersistedRedactedTraceBodiesForAdmin() {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        when(requestLogService.getByRequestId("admin-trace-id")).thenReturn(Mono.just(entry("admin-trace-id", 200, timestamp)));
        when(traceStore.getByRequestId("admin-trace-id")).thenReturn(new TraceRecord(
                "admin-trace-id",
                "cli***aa",
                "gpt-test",
                "provider-a",
                "route-a",
                "scene-a",
                200,
                "non-stream",
                123L,
                null,
                "{\"messages\":[{\"content\":\"[REDACTED]\"}]}",
                "{\"id\":\"resp-1\",\"output\":\"[REDACTED]\"}",
                timestamp
        ));

        webTestClient.get()
                .uri("/internal/requests/admin-trace-id")
                .header("Authorization", ADMIN_BEARER_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.trace.requestBody").isEqualTo("{\"messages\":[{\"content\":\"[REDACTED]\"}]}")
                .jsonPath("$.trace.responseBody").isEqualTo("{\"id\":\"resp-1\",\"output\":\"[REDACTED]\"}");
    }

    private RequestLogEntry entry(String requestId, int status, Instant timestamp) {
        return new RequestLogEntry(
                requestId,
                "client-a",
                "client-a",
                "gpt-test",
                "provider-a",
                "route-a",
                "scene-a",
                status,
                123,
                timestamp,
                "non-stream",
                10L,
                7L,
                3L,
                0.001,
                null
        );
    }
}
