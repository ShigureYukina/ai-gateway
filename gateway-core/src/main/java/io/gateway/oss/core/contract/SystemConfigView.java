package io.gateway.oss.core.contract;

import io.gateway.oss.core.config.AuthConfig;
import io.gateway.oss.core.config.ConcurrentLimitConfig;
import io.gateway.oss.core.config.LoadBalancerConfig;
import io.gateway.oss.core.config.ProviderHealthConfig;
import io.gateway.oss.core.config.SharedStateConfig;
import io.gateway.oss.core.config.StoreConfig;
import io.gateway.oss.core.config.SyncConfig;
import io.gateway.oss.core.config.TraceConfig;

/**
 * System 配置只读视图。
 * <p>
 * 该接口用于沉淀系统级配置读取边界；当前保持与既有配置对象一致，
 * 不引入新的转换层或运行时语义。
 * </p>
 */
public interface SystemConfigView {

    LimitConfigView getLimit();

    ConcurrentLimitConfig getConcurrentLimit();

    TraceConfig getTracing();

    ResilienceConfigView getResilience();

    PricingConfigView getPricing();

    LoadBalancerConfig getLoadBalancer();

    SyncConfig getSync();

    ProviderHealthConfig getProviderHealth();

    AuthConfig getAuth();

    OperationalConfigView getOperational();

    SharedStateConfig getSharedState();

    StoreConfig getStore();
}
