package io.gateway.oss.admin.web;

import io.gateway.oss.core.security.AuthorizationService;
import io.gateway.oss.admin.sync.PricingSyncService;
import io.gateway.oss.core.util.StringUtils;
import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/internal")
public class InternalModelsSnapshotController {

    private static final String SNAPSHOT_SOURCE = "models.dev";

    private final ProviderModelCatalogService catalogService;
    private final PricingSyncService pricingSyncService;
    private final AuthorizationService authorizationService;

    public InternalModelsSnapshotController(ProviderModelCatalogService catalogService,
                                            PricingSyncService pricingSyncService,
                                            AuthorizationService authorizationService) {
        this.catalogService = catalogService;
        this.pricingSyncService = pricingSyncService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/catalog/providers")
    public CatalogSnapshotResponse providerCatalog(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "provider", required = false) String provider,
            @RequestParam(name = "model", required = false) String model) {
        requireSystemAccess(exchange);
        ProviderModelCatalogService.CatalogSnapshot snapshot = catalogService.getSnapshot();
        Map<String, List<String>> filtered = filterProviderModels(snapshot.providerModels(), provider, model);
        return new CatalogSnapshotResponse(
                snapshotVersion(snapshot.version()),
                snapshotTimestamp(snapshot.version(), snapshot.updatedAt()),
                SNAPSHOT_SOURCE,
                filtered
        );
    }

    @GetMapping("/pricing/models")
    public PricingSnapshotResponse dynamicPricing(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "model", required = false) String model) {
        requireSystemAccess(exchange);
        PricingSyncService.PricingSnapshot snapshot = pricingSyncService.getSnapshot();
        Map<String, BigDecimal> filtered = filterModelPrices(snapshot.modelUnitPrices(), model);
        return new PricingSnapshotResponse(
                snapshotVersion(snapshot.version()),
                snapshotTimestamp(snapshot.version(), snapshot.updatedAt()),
                SNAPSHOT_SOURCE,
                filtered
        );
    }

    @GetMapping("/snapshots/models-pricing")
    public ModelsPricingSnapshotResponse modelsPricingSnapshot(
            ServerWebExchange exchange,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(name = "provider", required = false) String provider,
            @RequestParam(name = "model", required = false) String model) {
        requireSystemAccess(exchange);
        ProviderModelCatalogService.CatalogSnapshot catalogSnapshot = catalogService.getSnapshot();
        PricingSyncService.PricingSnapshot pricingSnapshot = pricingSyncService.getSnapshot();

        CatalogSnapshotResponse catalog = new CatalogSnapshotResponse(
                snapshotVersion(catalogSnapshot.version()),
                snapshotTimestamp(catalogSnapshot.version(), catalogSnapshot.updatedAt()),
                SNAPSHOT_SOURCE,
                filterProviderModels(catalogSnapshot.providerModels(), provider, model)
        );
        PricingSnapshotResponse pricing = new PricingSnapshotResponse(
                snapshotVersion(pricingSnapshot.version()),
                snapshotTimestamp(pricingSnapshot.version(), pricingSnapshot.updatedAt()),
                SNAPSHOT_SOURCE,
                filterModelPrices(pricingSnapshot.modelUnitPrices(), model)
        );

        return new ModelsPricingSnapshotResponse(Instant.now(), catalog, pricing);
    }

    private Map<String, List<String>> filterProviderModels(Map<String, List<String>> providerModels,
                                                            String provider,
                                                            String model) {
        String normalizedProvider = StringUtils.blankToNull(provider);
        String normalizedModel = StringUtils.blankToNull(model);
        Map<String, List<String>> base = providerModels == null ? Map.of() : providerModels;

        if (normalizedProvider == null && normalizedModel == null) {
            return base;
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (var entry : base.entrySet()) {
            String prov = entry.getKey();
            List<String> models = entry.getValue();

            if (normalizedProvider != null && !prov.equalsIgnoreCase(normalizedProvider)) {
                continue;
            }

            if (normalizedModel == null) {
                result.put(prov, models);
            } else {
                List<String> matched = models.stream()
                        .filter(m -> m.equalsIgnoreCase(normalizedModel))
                        .toList();
                if (!matched.isEmpty()) {
                    result.put(prov, matched);
                }
            }
        }
        return result;
    }

    private Map<String, BigDecimal> filterModelPrices(Map<String, BigDecimal> modelUnitPrices,
                                                       String model) {
        String normalizedModel = StringUtils.blankToNull(model);
        Map<String, BigDecimal> base = modelUnitPrices == null ? Map.of() : modelUnitPrices;
        if (normalizedModel == null) {
            return base;
        }

        BigDecimal price = base.get(normalizedModel);
        if (price == null) {
            return Map.of();
        }
        return Map.of(normalizedModel, price);
    }

    private Long snapshotVersion(long version) {
        return version <= 0 ? null : version;
    }

    private Instant snapshotTimestamp(long version, Instant updatedAt) {
        if (version <= 0) {
            return null;
        }
        return updatedAt;
    }

    private void requireSystemAccess(ServerWebExchange exchange) {
        var principal = InternalEndpointAuthFilter.requiredPrincipal(exchange);
        authorizationService.requireSystemView(principal);
    }

    public record CatalogSnapshotResponse(
            Long version,
            Instant fetchedAt,
            String source,
            Map<String, List<String>> providerModels
    ) {
    }

    public record PricingSnapshotResponse(
            Long version,
            Instant fetchedAt,
            String source,
            Map<String, BigDecimal> modelUnitPrices
    ) {
    }

    public record ModelsPricingSnapshotResponse(
            Instant generatedAt,
            CatalogSnapshotResponse catalog,
            PricingSnapshotResponse pricing
    ) {
    }
}
