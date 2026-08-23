package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.ProviderConfig;
import io.gateway.oss.core.contract.ProviderCatalogView;
import io.gateway.oss.core.contract.ProviderConfigView;
import io.gateway.oss.core.contract.ProviderConfigWriter;
import io.gateway.oss.core.dto.ProviderModelsUpdateRequest;
import io.gateway.oss.core.dto.ProviderUpsertRequest;
import io.gateway.oss.core.security.BaseUrlValidator;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.admin.sync.ProviderDiscoveryService;
import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import io.gateway.oss.core.upstream.ProviderHealthService;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminProviderController extends AdminBaseController {

    private final ProviderCatalogView providerCatalogView;
    private final ProviderHealthService providerHealthService;
    private final ProviderModelCatalogService catalogService;
    private final ProviderRuntimeStateStore providerRuntimeStateStore;
    private final ProviderDiscoveryService providerDiscoveryService;
    private final ProviderConfigWriter providerConfigWriter;
    private final BaseUrlValidator baseUrlValidator;

    public AdminProviderController(ClientAuthService clientAuthService,
                                   ProviderCatalogView providerCatalogView,
                                   ProviderHealthService providerHealthService,
                                   ProviderModelCatalogService catalogService,
                                   ProviderRuntimeStateStore providerRuntimeStateStore,
                                   ProviderDiscoveryService providerDiscoveryService,
                                   ProviderConfigWriter providerConfigWriter,
                                   BaseUrlValidator baseUrlValidator) {
        super(clientAuthService);
        this.providerCatalogView = providerCatalogView;
        this.providerHealthService = providerHealthService;
        this.catalogService = catalogService;
        this.providerRuntimeStateStore = providerRuntimeStateStore;
        this.providerDiscoveryService = providerDiscoveryService;
        this.providerConfigWriter = providerConfigWriter;
        this.baseUrlValidator = baseUrlValidator;
    }

    @GetMapping("/providers")
    public ProvidersResponse providers(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        Map<String, ProviderView> result = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends ProviderConfigView> entry : providerCatalogView.getProviders().entrySet()) {
            ProviderConfigView cfg = entry.getValue();
            result.put(entry.getKey(), new ProviderView(
                    cfg.getType(),
                    cfg.getBaseUrl(),
                    mask(cfg.getApiKey()),
                    maskKeys(cfg.getKeys()),
                    cfg.getKeyWeights(),
                    cfg.getTimeout(),
                    cfg.isEnabled(),
                    cfg.getModels()
            ));
        }
        return new ProvidersResponse(Instant.now(), result);
    }

    @PostMapping("/providers/{name}/test")
    public Mono<ProviderHealthService.ProviderTestResult> testProvider(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String name) {
        requireAdminAccess(authorizationHeader);
        ProviderConfig cfg = providerConfig(name);
        if (cfg == null) {
            return Mono.error(new IllegalArgumentException("Provider not found: " + name));
        }
        baseUrlValidator.validate(cfg.getBaseUrl());
        return providerHealthService.test(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getTimeout());
    }

    @GetMapping("/providers/{name}/models")
    public ProviderModelsResponse providerModels(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String name) {
        requireAdminAccess(authorizationHeader);
        List<String> models = catalogService.getModels(name);
        return new ProviderModelsResponse(Instant.now(), name, models);
    }

    @PostMapping("/providers/{name}/models/fetch")
    public Mono<ProviderModelsResponse> fetchProviderModels(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String name) {
        requireAdminAccess(authorizationHeader);
        ProviderConfig cfg = providerConfig(name);
        if (cfg == null) {
            return Mono.error(new IllegalArgumentException("Provider not found: " + name));
        }
        String baseUrl = cfg.getBaseUrl();
        String apiKey = cfg.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            // fallback to first key from keys list
            if (cfg.getKeys() != null) {
                for (String k : cfg.getKeys()) {
                    if (k != null && !k.isBlank()) {
                        apiKey = k;
                        break;
                    }
                }
            }
        }
        final String token = apiKey;
        baseUrlValidator.validate(baseUrl);
        return providerHealthService.fetchModels(baseUrl, token, cfg.getTimeout())
                .map(models -> new ProviderModelsResponse(Instant.now(), name, models))
                .onErrorResume(e -> Mono.just(new ProviderModelsResponse(Instant.now(), name, List.of())));
    }

    @PutMapping("/providers/{name}/models")
    public Mono<ResponseEntity<Void>> saveProviderModels(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String name,
            @Valid @RequestBody ProviderModelsUpdateRequest body) {
        requireAdminAccess(authorizationHeader);
        ProviderConfig cfg = providerConfig(name);
        if (cfg == null) {
            return Mono.error(new IllegalArgumentException("Provider not found: " + name));
        }
        List<String> models = body.models() != null ? body.models() : List.of();
        cfg.setModels(models);
        return providerConfigWriter.saveProvider(name, cfg)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @GetMapping("/providers/runtime")
    public ProviderRuntimeResponse providerRuntime(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        return new ProviderRuntimeResponse(Instant.now(), providerRuntimeStateStore.getAll());
    }

    @GetMapping("/providers/discovery")
    public ProviderDiscoveryService.DiscoverySnapshot providerDiscovery(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        return providerDiscoveryService.getSnapshot();
    }

    @PutMapping("/providers/{name}")
    public Mono<ResponseEntity<ProviderConfig>> putProvider(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String name,
            @RequestBody ProviderUpsertRequest incoming) {
        requireAdminAccess(authorizationHeader);
        ProviderConfig existing = providerConfig(name);
        boolean isNew = existing == null;
        ProviderConfig merged = isNew ? mapToProvider(incoming) : mergeProvider(existing, incoming);
        baseUrlValidator.validate(merged.getBaseUrl());
        return providerConfigWriter.saveProvider(name, merged)
                .then(Mono.fromCallable(() -> {
                    int status = isNew ? 201 : 200;
                    return ResponseEntity.status(status).body(merged);
                }));
    }

    @DeleteMapping("/providers/{name}")
    public Mono<ResponseEntity<?>> deleteProvider(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String name) {
        requireAdminAccess(authorizationHeader);
        if (!providerCatalogView.getProviders().containsKey(name)) {
            return Mono.just(ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).<Void>build());
        }
        // 检查是否有 route 引用该 provider，有则拒绝删除
        List<String> refs = providerConfigWriter.getRouteReferences(name);
        if (!refs.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "provider_in_use");
            body.put("message", "Provider is referenced by existing routes");
            body.put("routes", refs);
            return Mono.just(ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(body));
        }
        return providerConfigWriter.deleteProvider(name)
                .then(Mono.just(ResponseEntity.status(org.springframework.http.HttpStatus.NO_CONTENT).<Void>build()));
    }

    // ─── helpers ───

    private ProviderConfig providerConfig(String name) {
        ProviderConfigView providerConfigView = providerCatalogView.getProviders().get(name);
        if (providerConfigView instanceof ProviderConfig providerConfig) {
            return providerConfig;
        }
        return null;
    }

    private ProviderConfig mapToProvider(ProviderUpsertRequest map) {
        ProviderConfig cfg = new ProviderConfig();
        if (map.type() != null) cfg.setType(map.type());
        if (map.baseUrl() != null) cfg.setBaseUrl(map.baseUrl());
        if (map.apiKey() != null) cfg.setApiKey(map.apiKey());
        if (map.timeoutSeconds() != null) cfg.setTimeout(java.time.Duration.ofSeconds(map.timeoutSeconds()));
        if (map.enabled() != null) cfg.setEnabled(map.enabled());
        if (map.models() != null) cfg.setModels(map.models());
        return cfg;
    }

    private ProviderConfig mergeProvider(ProviderConfig existing, ProviderUpsertRequest map) {
        ProviderConfig merged = new ProviderConfig();
        merged.setType(map.type() != null ? map.type() : existing.getType());
        merged.setBaseUrl(map.baseUrl() != null ? map.baseUrl() : existing.getBaseUrl());
        String apiKey = map.apiKey();
        merged.setApiKey((apiKey != null && !apiKey.isBlank()) ? apiKey : existing.getApiKey());
        merged.setKeys(existing.getKeys());
        merged.setKeyWeights(existing.getKeyWeights());
        if (map.timeoutSeconds() != null) {
            merged.setTimeout(java.time.Duration.ofSeconds(map.timeoutSeconds()));
        } else {
            merged.setTimeout(existing.getTimeout());
        }
        merged.setEnabled(map.enabled() != null ? map.enabled() : existing.isEnabled());
        if (map.models() != null) {
            merged.setModels(map.models());
        } else {
            merged.setModels(existing.getModels());
        }
        return merged;
    }

    // ─── response records ───

    public record ProvidersResponse(
            Instant generatedAt,
            Map<String, ProviderView> providers
    ) {
    }

    public record ProviderView(
            String type,
            String baseUrl,
            String apiKey,
            List<String> keys,
            List<Integer> keyWeights,
            java.time.Duration timeout,
            boolean enabled,
            List<String> models
    ) {
    }

    public record ProviderModelsResponse(
            Instant generatedAt,
            String provider,
            List<String> models
    ) {
    }

    public record ProviderRuntimeResponse(
            Instant generatedAt,
            Map<String, ProviderRuntimeStateStore.ProviderRuntimeState> providers
    ) {
    }
}
