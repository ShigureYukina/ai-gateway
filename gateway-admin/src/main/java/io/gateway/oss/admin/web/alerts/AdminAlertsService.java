package io.gateway.oss.admin.web.alerts;

import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.security.UserAccountService;
import io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminAlertsService {

    private final GatewayConfigView configView;
    private final UserAccountService userAccountService;
    private final Resilience4jCircuitBreakerService resilience4jCircuitBreakerService;

    public AdminAlertsService(GatewayConfigView configView,
                              UserAccountService userAccountService,
                              Resilience4jCircuitBreakerService resilience4jCircuitBreakerService) {
        this.configView = configView;
        this.userAccountService = userAccountService;
        this.resilience4jCircuitBreakerService = resilience4jCircuitBreakerService;
    }

    public Mono<AlertsView> getAlerts() {
        Instant now = Instant.now();
        return userAccountService.listUsers().map(users -> {
            List<AlertView> active = new ArrayList<>();
            for (Map.Entry<String, ? extends RouteConfigView> entry : configView.getRoutes().entrySet()) {
                if (!entry.getValue().isEnabled()) {
                    active.add(new AlertView(
                            "route-disabled:" + entry.getKey(),
                            "route_disabled",
                            "warning",
                            "active",
                            "Route is disabled",
                            entry.getKey(),
                            null,
                            Map.of("routeId", entry.getKey())
                    ));
                }
            }
            for (UserAccount user : users) {
                if (user.frozen()) {
                    active.add(new AlertView(
                            "account-frozen:" + user.username(),
                            "account_frozen",
                            "warning",
                            "active",
                            "Account is frozen",
                            user.username(),
                            user.frozenAt() == null ? null : Instant.ofEpochMilli(user.frozenAt()),
                            Map.of("username", user.username())
                    ));
                }
            }
            for (String routeId : resilience4jCircuitBreakerService.getOpenCircuitRouteIds()) {
                active.add(new AlertView(
                        "circuit-open:" + routeId,
                        "circuit_open",
                        "critical",
                        "active",
                        "Circuit breaker is open",
                        routeId,
                        null,
                        Map.of("routeId", routeId)
                ));
            }
            active.sort(Comparator.comparing(AlertView::detectedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(AlertView::id));
            return new AlertsView(now, active, active.stream().limit(20).toList());
        });
    }

    public record AlertsView(Instant generatedAt, List<AlertView> active, List<AlertView> recent) {
    }

    public record AlertView(
            String id,
            String type,
            String severity,
            String status,
            String message,
            String source,
            Instant detectedAt,
            Map<String, Object> metadata
    ) {
        public AlertView {
            metadata = metadata == null ? new LinkedHashMap<>() : metadata;
        }
    }
}
