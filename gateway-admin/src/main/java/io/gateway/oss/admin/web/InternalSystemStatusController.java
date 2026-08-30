package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.security.AuthorizationService;
import io.gateway.oss.core.web.OperationalGateService;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@Validated
public class InternalSystemStatusController {

    private final OperationalGateService operationalGateService;
    private final GatewayConfigView configView;
    private final ProviderRuntimeStateStore providerRuntimeStateStore;
    private final AuthorizationService authorizationService;

    public InternalSystemStatusController(OperationalGateService operationalGateService,
                                          GatewayConfigView configView,
                                          ProviderRuntimeStateStore providerRuntimeStateStore,
                                          AuthorizationService authorizationService) {
        this.operationalGateService = operationalGateService;
        this.configView = configView;
        this.providerRuntimeStateStore = providerRuntimeStateStore;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/internal/system/status")
    public Mono<SystemStatusResponse> systemStatus(ServerWebExchange exchange,
                                                   @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireSystemAccess(exchange);
        return Mono.fromCallable(() -> {
        var gateState = operationalGateService.snapshot();
        boolean hasAvailableRoute = hasAnyAvailableRoute();
        return new SystemStatusResponse(
                Instant.now(),
                new MaintenanceView(gateState.maintenanceMode()),
                new EmergencyRateLimitView(
                        gateState.emergencyRateLimitEnabled(),
                        gateState.emergencyMaxRequestsPerMinute(),
                        gateState.emergencyWindowCount()
                ),
                new GlobalCircuitView(hasAvailableRoute)
        );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean hasAnyAvailableRoute() {
        Map<String, ? extends RouteConfigView> routes = configView.getRoutes();
        if (routes == null || routes.isEmpty()) {
            return false;
        }
        for (var route : routes.entrySet()) {
            if (!route.getValue().isEnabled()) continue;
            var providerName = route.getValue().getProvider();
            if (providerName == null) continue;
            var state = providerRuntimeStateStore.get(providerName);
            if (state.runtimeAvailable()) {
                return true;
            }
        }
        return false;
    }

    private ClientPrincipal requireSystemAccess(ServerWebExchange exchange) {
        ClientPrincipal principal = InternalEndpointAuthFilter.requiredPrincipal(exchange);
        authorizationService.requireSystemView(principal);
        return principal;
    }

    // ─── response records ───

    public record SystemStatusResponse(
            Instant generatedAt,
            MaintenanceView maintenance,
            EmergencyRateLimitView emergencyRateLimit,
            GlobalCircuitView globalCircuit
    ) {}

    public record MaintenanceView(boolean active) {}

    public record EmergencyRateLimitView(boolean enabled, int maxRequestsPerMinute, int currentWindowCount) {}

    public record GlobalCircuitView(boolean hasAvailableRoute) {}
}
