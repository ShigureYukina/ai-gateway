package io.gateway.oss.core.config;

import io.gateway.oss.core.routing.ModelRouteResolver;
import io.gateway.oss.core.routing.RouteLoadBalancer;

/**
 * 运行时刷新相关钩子。
 * <p>
 * 将动态配置落地后的运行时副作用集中到一个小委托中，避免
 * {@link DynamicConfigService} 继续膨胀，同时保持外部行为不变。
 * </p>
 */
final class RuntimeRefreshHooks {

    private final RouteLoadBalancer routeLoadBalancer;
    private final io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService resilienceService;
    private final ModelRouteResolver modelRouteResolver;

    private RuntimeRefreshHooks(RouteLoadBalancer routeLoadBalancer,
                                io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService resilienceService,
                                ModelRouteResolver modelRouteResolver) {
        this.routeLoadBalancer = routeLoadBalancer;
        this.resilienceService = resilienceService;
        this.modelRouteResolver = modelRouteResolver;
    }

    static RuntimeRefreshHooks of(RouteLoadBalancer routeLoadBalancer,
                                  io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService resilienceService,
                                  ModelRouteResolver modelRouteResolver) {
        return new RuntimeRefreshHooks(routeLoadBalancer, resilienceService, modelRouteResolver);
    }

    static RuntimeRefreshHooks noop() {
        return new RuntimeRefreshHooks(null, null, null);
    }

    /** 路由、场景或 provider 配置变更后刷新运行时路由状态。 */
    void onRoutingConfigChanged() {
        if (routeLoadBalancer != null) {
            routeLoadBalancer.onConfigChange();
        }
        if (modelRouteResolver != null) {
            modelRouteResolver.resetCache();
        }
    }

    /** 删除路由后沿用统一的路由刷新逻辑。 */
    void onRouteDeleted() {
        onRoutingConfigChanged();
    }

    /** 韧性配置变更后刷新 Resilience4j 对象。 */
    void onResilienceConfigChanged() {
        if (resilienceService != null) {
            resilienceService.resetResilience();
        }
    }
}
