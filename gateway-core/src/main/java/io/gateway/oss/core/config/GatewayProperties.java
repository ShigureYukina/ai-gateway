package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.GatewayConfigView;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties implements GatewayConfigView {

    private volatile Map<String, ProviderConfig> providers = Map.of();
    private volatile Map<String, RouteConfig> routes = Map.of();
    private volatile Map<String, SceneConfig> scenes = Map.of();
    private volatile Map<String, ClientConfig> clients = Map.of();
    private LimitConfig limit = new LimitConfig();
    private ConcurrentLimitConfig concurrentLimit = new ConcurrentLimitConfig();
    private TraceConfig tracing = new TraceConfig();
    private ResilienceConfig resilience = new ResilienceConfig();
    private SharedStateConfig sharedState = new SharedStateConfig();
    private PricingConfig pricing = new PricingConfig();
    private LoadBalancerConfig loadBalancer = new LoadBalancerConfig();
    private SyncConfig sync = new SyncConfig();
    private ProviderHealthConfig providerHealth = new ProviderHealthConfig();
    private AuthConfig auth = new AuthConfig();
    private volatile OperationalConfig operational = new OperationalConfig();
    private StoreConfig store = new StoreConfig();
    private BatchFlusherConfig batchFlusher = new BatchFlusherConfig();

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    public Map<String, RouteConfig> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, RouteConfig> routes) {
        this.routes = routes == null ? Map.of() : Map.copyOf(routes);
    }

    public Map<String, SceneConfig> getScenes() {
        return scenes;
    }

    public void setScenes(Map<String, SceneConfig> scenes) {
        this.scenes = scenes == null ? Map.of() : Map.copyOf(scenes);
    }

    public Map<String, ClientConfig> getClients() {
        return clients;
    }

    public void setClients(Map<String, ClientConfig> clients) {
        this.clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    public LimitConfig getLimit() {
        return limit;
    }

    public void setLimit(LimitConfig limit) {
        this.limit = limit;
    }

    public ConcurrentLimitConfig getConcurrentLimit() {
        return concurrentLimit;
    }

    public void setConcurrentLimit(ConcurrentLimitConfig concurrentLimit) {
        this.concurrentLimit = concurrentLimit;
    }

    public TraceConfig getTracing() {
        return tracing;
    }

    public void setTracing(TraceConfig tracing) {
        this.tracing = tracing;
    }

    public ResilienceConfig getResilience() {
        return resilience;
    }

    public void setResilience(ResilienceConfig resilience) {
        this.resilience = resilience;
    }

    public SharedStateConfig getSharedState() {
        return sharedState;
    }

    public void setSharedState(SharedStateConfig sharedState) {
        this.sharedState = sharedState;
    }

    public PricingConfig getPricing() {
        return pricing;
    }

    public void setPricing(PricingConfig pricing) {
        this.pricing = pricing;
    }

    public LoadBalancerConfig getLoadBalancer() {
        return loadBalancer;
    }

    public void setLoadBalancer(LoadBalancerConfig loadBalancer) {
        this.loadBalancer = loadBalancer;
    }

    public SyncConfig getSync() {
        return sync;
    }

    public void setSync(SyncConfig sync) {
        this.sync = sync;
    }

    public ProviderHealthConfig getProviderHealth() {
        return providerHealth;
    }

    public void setProviderHealth(ProviderHealthConfig providerHealth) {
        this.providerHealth = providerHealth;
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public void setAuth(AuthConfig auth) {
        this.auth = auth;
    }

    public OperationalConfig getOperational() {
        return operational;
    }

    public void setOperational(OperationalConfig operational) {
        this.operational = operational;
    }

    public StoreConfig getStore() {
        return store;
    }

    public void setStore(StoreConfig store) {
        this.store = store;
    }

    public BatchFlusherConfig getBatchFlusher() {
        return batchFlusher;
    }

    public void setBatchFlusher(BatchFlusherConfig batchFlusher) {
        this.batchFlusher = batchFlusher;
    }

    public static class BatchFlusherConfig {
        private int threadPoolSize = 16;
        private int maxQueueDepth = 10000;

        public int getThreadPoolSize() { return threadPoolSize; }
        public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }
        public int getMaxQueueDepth() { return maxQueueDepth; }
        public void setMaxQueueDepth(int maxQueueDepth) { this.maxQueueDepth = maxQueueDepth; }
    }

}
