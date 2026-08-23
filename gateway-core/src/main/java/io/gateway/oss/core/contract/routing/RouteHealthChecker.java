package io.gateway.oss.core.contract.routing;

/**
 * Abstraction for checking route health/availability.
 * Decouples routing layer from upstream resilience implementation.
 */
public interface RouteHealthChecker {

    boolean isAvailable(String routeId);

    boolean isAvailable(ResolvedRoute route);
}
