package io.gateway.oss.core.security;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.observability.RequestLogService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth/usage")
public class UserUsageController {

    private final JwtService jwtService;
    private final GatewayProperties properties;
    private final UserAccountService userAccountService;
    private final RequestLogService requestLogService;
    private final AuthSupport authSupport;

    public UserUsageController(JwtService jwtService,
                               GatewayProperties properties,
                               UserAccountService userAccountService,
                               RequestLogService requestLogService,
                               AuthSupport authSupport) {
        this.jwtService = jwtService;
        this.properties = properties;
        this.userAccountService = userAccountService;
        this.requestLogService = requestLogService;
        this.authSupport = authSupport;
    }

    @Operation(summary = "Get recent request logs for the current user")
    @GetMapping(value = "/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<UsageRecentResponse>> usageRecent(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "status", required = false) Integer status,
            ServerWebExchange exchange) {
        requireAuthEnabled();
        return authSupport.parseAccessClaims(exchange)
                .map(claims -> {
                    AuthSupport.TokenIdentity identity = authSupport.resolveIdentity(claims);
                    int clampedLimit = Math.max(1, Math.min(200, limit));
                    List<RequestLogService.RequestLogEntry> entries = requestLogService.getByClient(identity.clientId(), clampedLimit);
                    entries = entries.stream()
                            .filter(e -> model == null || model.isBlank() || model.equals(e.model()))
                            .filter(e -> status == null || e.status() == status)
                            .toList();
                    return ResponseEntity.ok(new UsageRecentResponse(
                            Instant.now(),
                            entries.stream().map(this::toUsageView).toList()));
                });
    }

    private UsageRequestEntry toUsageView(RequestLogService.RequestLogEntry entry) {
        return new UsageRequestEntry(
                entry.requestId(),
                entry.clientId(),
                entry.model(),
                entry.provider(),
                entry.routeId(),
                entry.scene(),
                entry.status(),
                entry.latencyMs(),
                entry.timestamp(),
                entry.streamMode(),
                entry.usageTokens(),
                entry.promptTokens(),
                entry.completionTokens(),
                entry.costUsd(),
                entry.errorMessage()
        );
    }

    @Operation(summary = "Get model cost distribution for the current user")
    @GetMapping(value = "/costs", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ModelCostDistributionResponse>> modelCostDistribution(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            ServerWebExchange exchange) {
        requireAuthEnabled();
        return authSupport.parseAccessClaims(exchange)
                .map(claims -> {
                    AuthSupport.TokenIdentity identity = authSupport.resolveIdentity(claims);

                    LocalDate fromDate = parseDateParam(from, "from");
                    LocalDate toDate = parseDateParam(to, "to");
                    Instant fromInstant = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
                    Instant toInstant = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

                    List<RequestLogService.RequestLogEntry> entries = requestLogService.getByClientFiltered(
                            identity.clientId(), fromInstant, toInstant, 10000);

                    Map<String, ModelCostEntry> modelMap = new LinkedHashMap<>();
                    for (RequestLogService.RequestLogEntry e : entries) {
                        ModelCostEntry existing = modelMap.computeIfAbsent(e.model(), k -> new ModelCostEntry(k, 0, 0L, 0L, 0L, 0.0));
                        modelMap.put(e.model(), new ModelCostEntry(
                                existing.model(),
                                existing.requests() + 1,
                                existing.totalTokens() + (e.usageTokens() != null ? e.usageTokens() : 0L),
                                existing.promptTokens() + (e.promptTokens() != null ? e.promptTokens() : 0L),
                                existing.completionTokens() + (e.completionTokens() != null ? e.completionTokens() : 0L),
                                existing.totalCostUsd() + (e.costUsd() != null ? e.costUsd() : 0.0)
                        ));
                    }

                    return ResponseEntity.ok(new ModelCostDistributionResponse(
                            identity.clientId(),
                            fromDate.toString(),
                            toDate.toString(),
                            new ArrayList<>(modelMap.values())
                    ));
                });
    }

    private LocalDate parseDateParam(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return LocalDate.now(ZoneOffset.UTC);
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request",
                    "Invalid " + paramName + " date, expected YYYY-MM-DD");
        }
    }

    private void requireAuthEnabled() {
        authSupport.requireAuthEnabled();
    }

    public record UsageRecentResponse(
            Instant generatedAt,
            List<UsageRequestEntry> requests
    ) {
    }

    public record UsageRequestEntry(
            String requestId,
            String clientId,
            String model,
            String provider,
            String routeId,
            String scene,
            int status,
            long latencyMs,
            Instant timestamp,
            String streamMode,
            Long usageTokens,
            Long promptTokens,
            Long completionTokens,
            Double costUsd,
            String errorMessage
    ) {
    }

    public record ModelCostDistributionResponse(
            String client,
            String from,
            String to,
            List<ModelCostEntry> models
    ) {
    }

    public record ModelCostEntry(
            String model,
            int requests,
            long totalTokens,
            long promptTokens,
            long completionTokens,
            double totalCostUsd
    ) {
    }
}
