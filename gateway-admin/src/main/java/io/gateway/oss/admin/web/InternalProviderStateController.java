package io.gateway.oss.admin.web;

import io.gateway.oss.core.security.AuthorizationService;
import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/internal")
public class InternalProviderStateController {

    private final ProviderRuntimeStateStore runtimeStateStore;
    private final ProviderDiscoveryService discoveryService;
    private final AuthorizationService authorizationService;

    public InternalProviderStateController(ProviderRuntimeStateStore runtimeStateStore,
                                           ProviderDiscoveryService discoveryService,
                                           AuthorizationService authorizationService) {
        this.runtimeStateStore = runtimeStateStore;
        this.discoveryService = discoveryService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/providers/runtime")
    public ProviderRuntimeSnapshotResponse runtime(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "provider", required = false) String provider) {
        requireSystemAccess(exchange);
        Map<String, ProviderRuntimeStateStore.ProviderRuntimeState> all = runtimeStateStore.getAll();
        return new ProviderRuntimeSnapshotResponse(Instant.now(), filter(all, provider));
    }

    @GetMapping("/providers/discovery")
    public ProviderDiscoveryService.DiscoverySnapshot discovery(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireSystemAccess(exchange);
        return discoveryService.getSnapshot();
    }

    private void requireSystemAccess(ServerWebExchange exchange) {
        var principal = InternalEndpointAuthFilter.requiredPrincipal(exchange);
        authorizationService.requireSystemView(principal);
    }

    private Map<String, ProviderRuntimeStateStore.ProviderRuntimeState> filter(
            Map<String, ProviderRuntimeStateStore.ProviderRuntimeState> source,
            String provider) {
        if (provider == null || provider.isBlank()) {
            return source;
        }
        Map<String, ProviderRuntimeStateStore.ProviderRuntimeState> result = new LinkedHashMap<>();
        ProviderRuntimeStateStore.ProviderRuntimeState state = source.get(provider.trim());
        if (state != null) {
            result.put(provider.trim(), state);
        }
        return Map.copyOf(result);
    }

    public record ProviderRuntimeSnapshotResponse(
            Instant generatedAt,
            Map<String, ProviderRuntimeStateStore.ProviderRuntimeState> providers
    ) {
    }
}
