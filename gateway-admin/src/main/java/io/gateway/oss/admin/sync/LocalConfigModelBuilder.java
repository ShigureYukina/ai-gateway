package io.gateway.oss.admin.sync;

import io.gateway.oss.admin.pricing.BillingPriceResolver;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.upstream.RouteResilienceTracker;
import io.gateway.oss.core.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class LocalConfigModelBuilder {

    private final GatewayConfigView configView;
    private final RouteResilienceTracker resilienceTracker;
    private final ModelMetadataService metadataService;
    private final BillingPriceResolver billingPriceResolver;

    LocalConfigModelBuilder(GatewayConfigView configView,
                            RouteResilienceTracker resilienceTracker,
                            ModelMetadataService metadataService,
                            BillingPriceResolver billingPriceResolver) {
        this.configView = configView;
        this.resilienceTracker = resilienceTracker;
        this.metadataService = metadataService;
        this.billingPriceResolver = billingPriceResolver;
    }

    public List<ModelListService.ListedModel> build(String providerFilter, String modelFilter) {
        String np = StringUtils.blankToNull(providerFilter);
        String nm = StringUtils.blankToNull(modelFilter);
        List<ModelListService.ListedModel> result = new ArrayList<>();

        for (Map.Entry<String, ? extends RouteConfigView> entry : configView.getRoutes().entrySet()) {
            String routeId = entry.getKey();
            RouteConfigView route = entry.getValue();
            String routeProvider = route.getProvider();

            if (np != null && (routeProvider == null || !np.equals(routeProvider))) {
                continue;
            }
            if (nm != null && !nm.equals(routeId)) {
                continue;
            }
            String ownedBy = routeProvider != null ? routeProvider : "";
            String status = resilienceTracker.isAvailable(routeId) ? "available" : "degraded";
            int contextLength = metadataService != null ? metadataService.getContextLength(routeId) : 0;
            List<String> capabilities = resolveCapabilities(routeId);
            result.add(new ModelListService.ListedModel(
                    routeId,
                    routeId,
                    buildCanonicalId(route.getProvider(), route.getUpstreamModel()),
                    "local",
                    "model",
                    0,
                    ownedBy,
                    List.of(),
                    contextLength,
                    capabilities,
                    pricing(routeId, route.getUpstreamModel(), route.getProvider()),
                    status,
                    Map.of()
            ));
        }
        return result;
    }

    private String buildCanonicalId(String provider, String upstreamModel) {
        if (provider == null || provider.isBlank() || upstreamModel == null || upstreamModel.isBlank()) {
            return null;
        }
        return provider + "/" + upstreamModel;
    }

    private List<String> resolveCapabilities(String modelId) {
        Map<String, Object> metadata = metadataService.getMetadata(modelId);
        List<String> caps = new ArrayList<>();
        caps.add("chat.completions");
        for (String key : metadata.keySet()) {
            Object value = metadata.get(key);
            if (value == null) {
                continue;
            }
            if ("context_length".equals(key)) {
                continue;
            }
            if ("max_tokens".equals(key)) {
                continue;
            }
            if ("display_name".equals(key)) {
                continue;
            }
            if ("description".equals(key)) {
                continue;
            }
            if ("output_price".equals(key)) {
                continue;
            }
            if (Boolean.TRUE.equals(value)) {
                caps.add(key);
            }
        }
        return caps;
    }

    private Map<String, Object> pricing(String requestedModel, String upstreamModel, String provider) {
        var resolved = billingPriceResolver.resolve(requestedModel, upstreamModel, provider);
        Map<String, Object> out = new HashMap<>();
        if (resolved.inputUnitPrice() != null) {
            out.put("input", resolved.inputUnitPrice());
        } else if (resolved.unitPrice() != null) {
            out.put("input", resolved.unitPrice());
        }
        if (resolved.outputUnitPrice() != null) {
            out.put("output", resolved.outputUnitPrice());
        }
        out.put("source", resolved.source());
        out.put("matchedBy", resolved.matchedBy());
        out.put("matchedModel", resolved.matchedModel());
        return out;
    }
}
