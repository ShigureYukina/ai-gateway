package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.TraceRecord;
import io.gateway.oss.core.observability.TraceStore;
import io.gateway.oss.core.security.AuthorizationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@RestController
@Validated
@RequestMapping("/internal")
public class InternalRequestLogController {

    private final RequestLogQueryService requestLogQueryService;
    private final RequestLogService requestLogService;
    private final TraceStore traceStore;
    private final AuthorizationService authorizationService;

    public InternalRequestLogController(RequestLogQueryService requestLogQueryService,
                                        RequestLogService requestLogService,
                                        TraceStore traceStore,
                                        AuthorizationService authorizationService) {
        this.requestLogQueryService = requestLogQueryService;
        this.requestLogService = requestLogService;
        this.traceStore = traceStore;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/requests/recent")
    public RecentRequestsResponse recent(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(500) int limit,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "client", required = false) String client,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        requireSystemAccess(exchange);
        RequestLogQueryService.RequestLogRecentResult result =
                requestLogQueryService.recent(offset, limit, model, client, status, from, to);
        return new RecentRequestsResponse(Instant.now(), result.total(), result.offset(), result.requests());
    }

    @GetMapping("/requests/{requestId}")
    public Mono<ResponseEntity<?>> byRequestId(@PathVariable String requestId,
                                               ServerWebExchange exchange,
                                               @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        var principal = requireSystemAccess(exchange);
        TraceRecord trace = traceStore.getByRequestId(requestId);
        if (trace != null && !authorizationService.isAdminOrOperator(principal)) {
            trace = redactTraceBodies(trace);
        }
        TraceRecord finalTrace = trace;
        return requestLogService.getByRequestId(requestId)
                .<ResponseEntity<?>>map(entry -> ResponseEntity.ok(
                        new RequestDetailResponse(Instant.now(), requestLogQueryService.toView(entry), finalTrace)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/cost/by-model")
    public ModelCostSummaryResponse costByModel(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "day", required = false) String day) {
        requireSystemAccess(exchange);
        RequestLogQueryService.ModelCostSummaryResult result = requestLogQueryService.costByModel(day);
        return new ModelCostSummaryResponse(Instant.now(), result.day(), result.models());
    }

    @GetMapping("/cost/client")
    public ClientCostSummaryResponse costByClient(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "client", required = false) String client,
            @RequestParam(name = "from", required = false) String fromStr,
            @RequestParam(name = "to", required = false) String toStr) {
        requireSystemAccess(exchange);
        RequestLogQueryService.ClientCostSummaryResult result =
                requestLogQueryService.costByClient(client, fromStr, toStr);
        return new ClientCostSummaryResponse(Instant.now(), result.from(), result.to(), result.models());
    }

    private ClientPrincipal requireSystemAccess(ServerWebExchange exchange) {
        ClientPrincipal principal = InternalEndpointAuthFilter.requiredPrincipal(exchange);
        authorizationService.requireSystemView(principal);
        return principal;
    }

    private TraceRecord redactTraceBodies(TraceRecord trace) {
        return new TraceRecord(
                trace.requestId(), trace.clientId(), trace.model(), trace.provider(),
                trace.routeId(), trace.scene(), trace.status(), trace.streamMode(),
                trace.latencyMs(), trace.errorMessage(),
                null, null,
                trace.timestamp()
        );
    }

    public record RecentRequestsResponse(Instant generatedAt,
                                         int total,
                                         int offset,
                                         List<RequestLogQueryService.RequestLogEntryView> requests) {
    }

    public record RequestDetailResponse(Instant generatedAt,
                                        RequestLogQueryService.RequestLogEntryView request,
                                        TraceRecord trace) {
    }

    public record ModelCostSummaryResponse(Instant generatedAt,
                                           String day,
                                           List<RequestLogQueryService.ModelCostEntry> models) {
    }

    public record ClientCostSummaryResponse(Instant generatedAt,
                                            Instant from,
                                            Instant to,
                                            List<RequestLogQueryService.ClientModelEntry> models) {
    }
}
