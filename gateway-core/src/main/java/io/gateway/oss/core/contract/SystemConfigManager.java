package io.gateway.oss.core.contract;

import io.gateway.oss.core.config.AuthConfig;
import io.gateway.oss.core.config.ConcurrentLimitConfig;
import io.gateway.oss.core.config.LimitConfig;
import io.gateway.oss.core.config.LoadBalancerConfig;
import io.gateway.oss.core.config.OperationalConfig;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.ProviderHealthConfig;
import io.gateway.oss.core.config.ResilienceConfig;
import io.gateway.oss.core.config.SyncConfig;
import io.gateway.oss.core.config.TraceConfig;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * System 配置写入入口契约。
 * <p>
 * 本接口仅定义系统级配置的最小写边界，供后续 admin 侧逐步依赖该 manager 契约。
 * 当前不要求调用方一次性切换，也不扩展现有业务语义。
 * </p>
 */
public interface SystemConfigManager {

    Mono<Void> saveSystemLimit(LimitConfig config);

    Mono<Void> saveSystemResilience(ResilienceConfig config);

    Mono<Void> saveSystemPricing(PricingConfig config);

    Mono<Void> saveSystemOperational(OperationalConfig config);

    Mono<Void> saveSystemLoadBalancer(LoadBalancerConfig config);

    Mono<Void> saveSystemConcurrentLimit(ConcurrentLimitConfig config);

    Mono<Void> saveSystemTracing(TraceConfig config);

    Mono<Void> saveSystemSync(SyncConfig config);

    Mono<Void> saveSystemProviderHealth(ProviderHealthConfig config);

    Mono<Void> saveSystemAuth(AuthConfig config);

    /**
     * 返回已持久化但仍需调用方关注的 system key 集合。
     * 当前语义保持与既有 DynamicConfigService 一致。
     */
    Set<String> getPendingSystemKeys();
}
