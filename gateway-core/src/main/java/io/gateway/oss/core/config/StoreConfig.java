package io.gateway.oss.core.config;

/**
 * 各存储后端的类型选择配置。
 * <p>
 * 从 {@link GatewayProperties} 内部类提取为独立顶层类，
 * 供 {@link io.gateway.oss.core.contract.config.SystemConfigView} 等契约接口引用。
 * </p>
 */
public class StoreConfig {

    private Backend rateLimiter = Backend.REDIS;
    private Backend tpm = Backend.REDIS;
    private Backend usage = Backend.POSTGRESQL;
    private Backend cost = Backend.POSTGRESQL;
    private Backend routeState = Backend.REDIS;
    private Backend providerState = Backend.REDIS;
    private Backend aggregateMetrics = Backend.POSTGRESQL;
    private Backend config = Backend.POSTGRESQL;
    private Backend trace = Backend.POSTGRESQL;

    public Backend getRateLimiter() {
        return rateLimiter;
    }

    public void setRateLimiter(Backend rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public Backend getTpm() {
        return tpm;
    }

    public void setTpm(Backend tpm) {
        this.tpm = tpm;
    }

    public Backend getUsage() {
        return usage;
    }

    public void setUsage(Backend usage) {
        this.usage = usage;
    }

    public Backend getCost() {
        return cost;
    }

    public void setCost(Backend cost) {
        this.cost = cost;
    }

    public Backend getRouteState() {
        return routeState;
    }

    public void setRouteState(Backend routeState) {
        this.routeState = routeState;
    }

    public Backend getProviderState() {
        return providerState;
    }

    public void setProviderState(Backend providerState) {
        this.providerState = providerState;
    }

    public Backend getAggregateMetrics() {
        return aggregateMetrics;
    }

    public void setAggregateMetrics(Backend aggregateMetrics) {
        this.aggregateMetrics = aggregateMetrics;
    }

    public Backend getConfig() {
        return config;
    }

    public void setConfig(Backend config) {
        this.config = config;
    }

    public Backend getTrace() {
        return trace;
    }

    public void setTrace(Backend trace) {
        this.trace = trace;
    }
}
