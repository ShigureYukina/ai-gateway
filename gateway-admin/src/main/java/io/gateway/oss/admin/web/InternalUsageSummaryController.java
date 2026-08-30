package io.gateway.oss.admin.web;

import io.gateway.oss.admin.observability.AggregateReportingService;
import io.gateway.oss.core.security.AuthorizationService;
import io.gateway.oss.core.util.DateParamParser;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@Validated
@RequestMapping("/internal")
public class InternalUsageSummaryController {

    private final AggregateReportingService aggregateReportingService;
    private final InternalUsageSummaryReadService usageSummaryReadService;
    private final AuthorizationService authorizationService;

    public InternalUsageSummaryController(AggregateReportingService aggregateReportingService,
                                          InternalUsageSummaryReadService usageSummaryReadService,
                                          AuthorizationService authorizationService) {
        this.aggregateReportingService = aggregateReportingService;
        this.usageSummaryReadService = usageSummaryReadService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/usage/summary")
    public Mono<UsageSummaryResponse> usageSummary(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "client", required = false) String client,
            @RequestParam(name = "day", required = false) String day) {
        requireSystemAccess(exchange);
        return Mono.fromCallable(() -> usageSummaryReadService.usageSummary(client, resolveDay(day))).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/cost/summary")
    public Mono<CostSummaryResponse> costSummary(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "client", required = false) String client,
            @RequestParam(name = "day", required = false) String day) {
        requireSystemAccess(exchange);
        return Mono.fromCallable(() -> usageSummaryReadService.costSummary(client, resolveDay(day))).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/reporting/providers")
    public Mono<AggregateReportingService.ReportingBucket> providerReporting(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "date", required = false) String date) {
        requireSystemAccess(exchange);
        return Mono.fromCallable(() -> aggregateReportingService.providers(period, date)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/reporting/users")
    public Mono<AggregateReportingService.ReportingBucket> userReporting(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "date", required = false) String date) {
        requireSystemAccess(exchange);
        return Mono.fromCallable(() -> aggregateReportingService.users(period, date)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/reporting/keys")
    public Mono<AggregateReportingService.ReportingBucket> keyReporting(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "date", required = false) String date) {
        requireSystemAccess(exchange);
        return Mono.fromCallable(() -> aggregateReportingService.keys(period, date)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/dashboard/overview")
    public Mono<DashboardOverviewResponse> dashboardOverview(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "day", required = false) String day) {
        requireSystemAccess(exchange);
        return Mono.fromCallable(() -> usageSummaryReadService.dashboardOverview(resolveDay(day))).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/usage/tpm")
    public TpmOverviewResponse tpmOverview(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "client", required = false) String client) {
        requireSystemAccess(exchange);
        return usageSummaryReadService.tpmOverview(client, Instant.now());
    }

    private void requireSystemAccess(ServerWebExchange exchange) {
        var principal = InternalEndpointAuthFilter.requiredPrincipal(exchange);
        authorizationService.requireSystemView(principal);
    }

    private LocalDate resolveDay(String day) {
        return DateParamParser.resolveIsoDateOrToday(day, "day");
    }

    public record UsageSummaryResponse(
            Instant generatedAt,
            String day,
            List<UsageEntry> clients
    ) {
    }

    public record UsageEntry(
            String client,
            long tokens,
            long requests
    ) {
    }

    public record CostSummaryResponse(
            Instant generatedAt,
            String day,
            List<CostEntry> clients
    ) {
    }

    public record CostEntry(
            String client,
            BigDecimal cost
    ) {
    }

    public record DashboardOverviewResponse(
            Instant generatedAt,
            String day,
            long totalRequests,
            long totalTokens,
            BigDecimal totalCost,
            double successRate,
            long activeClients,
            int registeredClients,
            long success2xx,
            long status4xx,
            long status5xx,
            List<ModelUsageEntry> topModels,
            List<ClientSpendEntry> topClients
    ) {
    }

    public record ModelUsageEntry(
            String model,
            long requests,
            long tokens,
            BigDecimal cost
    ) {
    }

    public record ClientSpendEntry(
            String client,
            long requests,
            long tokens,
            BigDecimal cost
    ) {
    }

    public record TpmOverviewResponse(
            Instant generatedAt,
            Instant windowStartedAt,
            List<TpmClientEntry> clients
    ) {
    }

    public record TpmClientEntry(
            String client,
            long usedTokens,
            long limitTokens,
            long remainingTokens,
            double utilizationPercent,
            Instant windowStartedAt
    ) {
    }
}
