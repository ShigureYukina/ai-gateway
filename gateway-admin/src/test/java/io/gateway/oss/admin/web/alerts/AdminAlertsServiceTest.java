package io.gateway.oss.admin.web.alerts;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.security.UserAccountService;
import io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAlertsServiceTest {

    @Mock
    private GatewayProperties properties;

    @Mock
    private UserAccountService userAccountService;

    @Mock
    private Resilience4jCircuitBreakerService resilience4jCircuitBreakerService;

    @InjectMocks
    private AdminAlertsService adminAlertsService;

    @Test
    void shouldReturnAlertsWhenRoutesAreDisabled() {
        RouteConfig enabledRoute = new RouteConfig();
        enabledRoute.setEnabled(true);
        RouteConfig disabledRoute = new RouteConfig();
        disabledRoute.setEnabled(false);

        when(properties.getRoutes()).thenReturn(Map.of(
                "enabled-route", enabledRoute,
                "disabled-route", disabledRoute
        ));
        when(userAccountService.listUsers()).thenReturn(Mono.just(List.of()));
        when(resilience4jCircuitBreakerService.getOpenCircuitRouteIds()).thenReturn(List.of());

        AdminAlertsService.AlertsView view = adminAlertsService.getAlerts().block();

        assertThat(view).isNotNull();
        assertThat(view.active()).hasSize(1);
        assertThat(view.active().getFirst().id()).isEqualTo("route-disabled:disabled-route");
        assertThat(view.active().getFirst().type()).isEqualTo("route_disabled");
        assertThat(view.recent()).hasSize(1);
    }

    @Test
    void shouldReturnFrozenUserAlerts() {
        long frozenAt = Instant.parse("2026-06-04T10:15:30Z").toEpochMilli();

        when(properties.getRoutes()).thenReturn(Map.of());
        when(userAccountService.listUsers()).thenReturn(Mono.just(List.of(
                new UserAccount("frozen-user", "hash", "user", null, null, null, null, null, List.of(), 1L, 0, true, frozenAt)
        )));
        when(resilience4jCircuitBreakerService.getOpenCircuitRouteIds()).thenReturn(List.of());

        AdminAlertsService.AlertsView view = adminAlertsService.getAlerts().block();

        assertThat(view).isNotNull();
        assertThat(view.active()).hasSize(1);
        assertThat(view.active().getFirst().id()).isEqualTo("account-frozen:frozen-user");
        assertThat(view.active().getFirst().type()).isEqualTo("account_frozen");
        assertThat(view.active().getFirst().detectedAt()).isEqualTo(Instant.ofEpochMilli(frozenAt));
    }

    @Test
    void shouldIncludeCircuitBreakerOpenAlerts() {
        when(properties.getRoutes()).thenReturn(Map.of());
        when(userAccountService.listUsers()).thenReturn(Mono.just(List.of()));
        when(resilience4jCircuitBreakerService.getOpenCircuitRouteIds()).thenReturn(List.of("route-b", "route-a"));

        AdminAlertsService.AlertsView view = adminAlertsService.getAlerts().block();

        assertThat(view).isNotNull();
        assertThat(view.active()).extracting("id")
                .containsExactly("circuit-open:route-a", "circuit-open:route-b");
        assertThat(view.active()).extracting("type")
                .containsOnly("circuit_open");
    }

    @Test
    void shouldReturnEmptyActiveListWhenNoIssues() {
        when(properties.getRoutes()).thenReturn(Map.of());
        when(userAccountService.listUsers()).thenReturn(Mono.just(List.of()));
        when(resilience4jCircuitBreakerService.getOpenCircuitRouteIds()).thenReturn(List.of());

        AdminAlertsService.AlertsView view = adminAlertsService.getAlerts().block();

        assertThat(view).isNotNull();
        assertThat(view.active()).isEmpty();
        assertThat(view.recent()).isEmpty();
    }

    @Test
    void shouldLimitRecentListToTwentyItems() {
        Map<String, RouteConfig> routes = new LinkedHashMap<>();
        for (int i = 0; i < 25; i++) {
            RouteConfig route = new RouteConfig();
            route.setEnabled(false);
            routes.put("route-" + i, route);
        }

        when(properties.getRoutes()).thenReturn(routes);
        when(userAccountService.listUsers()).thenReturn(Mono.just(List.of()));
        when(resilience4jCircuitBreakerService.getOpenCircuitRouteIds()).thenReturn(List.of());

        AdminAlertsService.AlertsView view = adminAlertsService.getAlerts().block();

        assertThat(view).isNotNull();
        assertThat(view.active()).hasSize(25);
        assertThat(view.recent()).hasSize(20);
    }

    @Test
    void shouldSortAlertsByDetectedAtDescendingThenById() {
        long newest = Instant.parse("2026-06-04T12:00:00Z").toEpochMilli();
        long older = Instant.parse("2026-06-04T11:00:00Z").toEpochMilli();

        RouteConfig routeB = new RouteConfig();
        routeB.setEnabled(false);
        RouteConfig routeA = new RouteConfig();
        routeA.setEnabled(false);

        when(properties.getRoutes()).thenReturn(Map.of(
                "route-b", routeB,
                "route-a", routeA
        ));
        when(userAccountService.listUsers()).thenReturn(Mono.just(List.of(
                new UserAccount("older-user", "hash", "user", null, null, null, null, null, List.of(), 1L, 0, true, older),
                new UserAccount("newer-user", "hash", "user", null, null, null, null, null, List.of(), 1L, 0, true, newest)
        )));
        when(resilience4jCircuitBreakerService.getOpenCircuitRouteIds()).thenReturn(List.of("c-route"));

        AdminAlertsService.AlertsView view = adminAlertsService.getAlerts().block();

        assertThat(view).isNotNull();
        assertThat(view.active()).extracting("id")
                .containsExactly(
                        "account-frozen:newer-user",
                        "account-frozen:older-user",
                        "circuit-open:c-route",
                        "route-disabled:route-a",
                        "route-disabled:route-b"
                );
    }
}
