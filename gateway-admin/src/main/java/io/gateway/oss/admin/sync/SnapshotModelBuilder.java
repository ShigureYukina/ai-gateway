package io.gateway.oss.admin.sync;

import io.gateway.oss.admin.pricing.BillingPriceResolver;
import io.gateway.oss.core.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class SnapshotModelBuilder {

    private final ModelMetadataService metadataService;
    private final BillingPriceResolver billingPriceResolver;

    SnapshotModelBuilder(ModelMetadataService metadataService,
                         BillingPriceResolver billingPriceResolver) {
        this.metadataService = metadataService;
        this.billingPriceResolver = billingPriceResolver;
    }

    public List<ModelListService.ListedModel> build(ProviderModelCatalogService.CatalogSnapshot snapshot,
                                                    String providerFilter,
                                                    String modelFilter) {
        Map<String, List<String>> providerModels = snapshot.providerModels();
        if (providerModels == null || providerModels.isEmpty()) {
            return List.of();
        }

        String np = StringUtils.blankToNull(providerFilter);
        String nm = StringUtils.blankToNull(modelFilter);
        List<ModelListService.ListedModel> result = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : providerModels.entrySet()) {
            String providerName = entry.getKey();
            if (np != null && !np.equals(providerName)) {
                continue;
            }
            List<String> models = entry.getValue();
            if (models == null) {
                continue;
            }
            for (String modelId : models) {
                if (nm != null && !nm.equals(modelId)) {
                    continue;
                }
                int contextLength = metadataService != null ? metadataService.getContextLength(modelId) : 0;
                List<String> capabilities = resolveCapabilities(modelId);
                result.add(new ModelListService.ListedModel(
                        modelId,
                        modelId,
                        buildCanonicalId(providerName, modelId),
                        "snapshot",
                        "model",
                        0,
                        providerName,
                        List.of(),
                        contextLength,
                        capabilities,
                        pricing(modelId, modelId, providerName),
                        "available",
                        Map.of()
                ));
            }
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
