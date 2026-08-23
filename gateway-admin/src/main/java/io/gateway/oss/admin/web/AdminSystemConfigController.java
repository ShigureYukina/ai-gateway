package io.gateway.oss.admin.web;

import io.gateway.oss.admin.pricing.BillingPriceResolver;
import io.gateway.oss.core.config.ConcurrentLimitConfig;
import io.gateway.oss.core.config.LimitConfig;
import io.gateway.oss.core.config.LoadBalancerConfig;
import io.gateway.oss.core.config.OperationalConfig;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.ProviderHealthConfig;
import io.gateway.oss.core.config.ResilienceConfig;
import io.gateway.oss.core.config.SyncConfig;
import io.gateway.oss.core.config.AuthConfig;
import io.gateway.oss.core.config.TraceConfig;
import io.gateway.oss.core.contract.SystemConfigManager;
import io.gateway.oss.core.security.ClientAuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminSystemConfigController extends AdminBaseController {

    private final SystemConfigManager systemConfigManager;
    private final BillingPriceResolver billingPriceResolver;

    public AdminSystemConfigController(ClientAuthService clientAuthService,
                                       SystemConfigManager systemConfigManager,
                                       BillingPriceResolver billingPriceResolver) {
        super(clientAuthService);
        this.systemConfigManager = systemConfigManager;
        this.billingPriceResolver = billingPriceResolver;
    }

    @PutMapping("/system/limit")
    public Mono<ResponseEntity<LimitConfig>> putSystemLimit(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody LimitConfig config) {
        requireAdminAccess(authorizationHeader);
        return systemConfigManager.saveSystemLimit(config)
                .then(Mono.just(ResponseEntity.ok(config)));
    }

    @PutMapping("/system/resilience")
    public Mono<ResponseEntity<ResilienceConfig>> putSystemResilience(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody ResilienceConfig config) {
        requireAdminAccess(authorizationHeader);
        return systemConfigManager.saveSystemResilience(config)
                .then(Mono.just(ResponseEntity.ok(config)));
    }

    @PutMapping("/system/pricing")
    public Mono<ResponseEntity<PricingConfig>> putSystemPricing(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody PricingConfig config) {
        requireAdminAccess(authorizationHeader);
        return systemConfigManager.saveSystemPricing(config)
                .then(Mono.just(ResponseEntity.ok(config)));
    }

    @GetMapping("/system/pricing/resolve")
    public ResponseEntity<Map<String, Object>> previewSystemPricing(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(name = "model") String model,
            @RequestParam(name = "upstreamModel", required = false) String upstreamModel,
            @RequestParam(name = "provider", required = false) String provider) {
        requireAdminAccess(authorizationHeader);
        return ResponseEntity.ok(billingPriceResolver.preview(model, upstreamModel, provider));
    }

    @PutMapping("/system/operational")
    public Mono<ResponseEntity<OperationalConfig>> putSystemOperational(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody OperationalConfig config) {
        requireAdminAccess(authorizationHeader);
        return systemConfigManager.saveSystemOperational(config)
                .then(Mono.just(ResponseEntity.ok(config)));
    }

    @PutMapping("/system/load-balancer")
    public Mono<ResponseEntity<LoadBalancerConfig>> putSystemLoadBalancer(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody LoadBalancerConfig config) {
        requireAdminAccess(authorizationHeader);
        return systemConfigManager.saveSystemLoadBalancer(config)
                .then(Mono.just(ResponseEntity.ok(config)));
    }

    @PutMapping("/system/concurrent-limit")
    public Mono<ResponseEntity<ConcurrentLimitConfig>> putSystemConcurrentLimit(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody ConcurrentLimitConfig config) {
        requireAdminAccess(authorizationHeader);
        return systemConfigManager.saveSystemConcurrentLimit(config)
                .then(Mono.just(ResponseEntity.ok(config)));
    }

    @PutMapping("/system/tracing")
    public Mono<ResponseEntity<TraceConfig>> putSystemTracing(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody TraceConfig config) {
        requireAdminAccess(authorizationHeader);
        return systemConfigManager.saveSystemTracing(config)
                .then(Mono.just(ResponseEntity.ok(config)));
    }

    @PutMapping("/system/sync")
    public Mono<ResponseEntity<SyncConfig>> putSystemSync(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody SyncConfig config) {
        requireAdminAccess(authorizationHeader);
        return systemConfigManager.saveSystemSync(config)
                .then(Mono.just(ResponseEntity.ok(config)));
    }

    @PutMapping("/system/provider-health")
    public Mono<ResponseEntity<ProviderHealthConfig>> putSystemProviderHealth(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody ProviderHealthConfig config) {
        requireAdminAccess(authorizationHeader);
        return systemConfigManager.saveSystemProviderHealth(config)
                .then(Mono.just(ResponseEntity.ok(config)));
    }

    @PutMapping("/system/auth")
    public Mono<ResponseEntity<AuthConfig>> putSystemAuth(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody AuthConfig config) {
        requireAdminAccess(authorizationHeader);
        return systemConfigManager.saveSystemAuth(config)
                .then(Mono.just(ResponseEntity.ok(config)));
    }
}
