package io.gateway.oss.admin.sync;

import io.gateway.oss.admin.pricing.BillingPriceResolver;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.upstream.RouteResilienceTracker;
import io.gateway.oss.core.web.ModelListProvider;
import io.gateway.oss.core.web.ModelsController;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ModelListService implements ModelListProvider {

    private final ProviderModelCatalogService catalogService;
    private final GatewayConfigView configView;
    private final RouteResilienceTracker resilienceTracker;
    private final BillingPriceResolver billingPriceResolver;
    private final ModelMetadataService metadataService;
    private final PublicModelMetadataService publicModelMetadataService;
    private final PublishedAliasModelBuilder publishedAliasModelBuilder;
    private final SnapshotModelBuilder snapshotModelBuilder;
    private final LocalConfigModelBuilder localConfigModelBuilder;

    public ModelListService(ProviderModelCatalogService catalogService,
                            GatewayConfigView configView,
                            RouteResilienceTracker resilienceTracker,
                            BillingPriceResolver billingPriceResolver,
                            ModelMetadataService metadataService,
                            PublicModelMetadataService publicModelMetadataService) {
        this.catalogService = catalogService;
        this.configView = configView;
        this.resilienceTracker = resilienceTracker;
        this.billingPriceResolver = billingPriceResolver;
        this.metadataService = metadataService;
        this.publicModelMetadataService = publicModelMetadataService;
        this.publishedAliasModelBuilder = new PublishedAliasModelBuilder(
                configView,
                resilienceTracker,
                billingPriceResolver,
                publicModelMetadataService,
                metadataService
        );
        this.snapshotModelBuilder = new SnapshotModelBuilder(metadataService, billingPriceResolver);
        this.localConfigModelBuilder = new LocalConfigModelBuilder(
                configView,
                resilienceTracker,
                metadataService,
                billingPriceResolver
        );
    }

    @Override
    public List<ModelsController.ModelObject> buildModels(String providerFilter, String modelFilter) {
        return buildListedModels(providerFilter, modelFilter).stream()
                .map(this::toModelObject)
                .toList();
    }

    @Override
    public boolean hasData() {
        ProviderModelCatalogService.CatalogSnapshot snapshot = catalogService.getSnapshot();
        if (snapshot.providerModels() != null && !snapshot.providerModels().isEmpty()) {
            return true;
        }
        return configView.getRoutes() != null && !configView.getRoutes().isEmpty();
    }

    private List<ListedModel> buildListedModels(String providerFilter, String modelFilter) {
        ProviderModelCatalogService.CatalogSnapshot snapshot = catalogService.getSnapshot();
        boolean hasSnapshotData = snapshot.providerModels() != null && !snapshot.providerModels().isEmpty();

        // Always build model groups (published aliases) — they need to appear even when snapshot is active
        List<ListedModel> modelGroups = buildFromModelGroups(providerFilter, modelFilter);

        if (hasSnapshotData) {
            List<ListedModel> snapshotModels = buildFromSnapshot(snapshot, providerFilter, modelFilter);
            // Merge model groups into snapshot results so published aliases are visible
            if (!modelGroups.isEmpty()) {
                Set<String> snapshotIds = new HashSet<>();
                for (ListedModel m : snapshotModels) {
                    snapshotIds.add(m.id());
                }
                for (ListedModel mg : modelGroups) {
                    if (!snapshotIds.contains(mg.id())) {
                        snapshotModels.add(mg);
                    }
                }
            }
            return snapshotModels;
        }

        if (!modelGroups.isEmpty()) {
            return modelGroups;
        }
        return buildFromLocalConfig(providerFilter, modelFilter);
    }

    private ModelsController.ModelObject toModelObject(ListedModel lm) {
        return new ModelsController.ModelObject(
                lm.id(),
                lm.executionId(),
                lm.canonicalId(),
                lm.sourceType(),
                lm.object(),
                lm.created(),
                lm.ownedBy(),
                lm.permission(),
                lm.contextLength(),
                lm.capabilities(),
                lm.pricing(),
                lm.status(),
                lm.metadata()
        );
    }

    private List<ListedModel> buildFromModelGroups(String providerFilter, String modelFilter) {
        return publishedAliasModelBuilder.build(providerFilter, modelFilter);
    }

    private List<ListedModel> buildFromSnapshot(ProviderModelCatalogService.CatalogSnapshot snapshot,
                                                String providerFilter, String modelFilter) {
        return snapshotModelBuilder.build(snapshot, providerFilter, modelFilter);
    }

    private List<ListedModel> buildFromLocalConfig(String providerFilter, String modelFilter) {
        return localConfigModelBuilder.build(providerFilter, modelFilter);
    }

    private List<String> resolveCapabilities(String modelId) {
        Map<String, Object> metadata = metadataService.getMetadata(modelId);
        List<String> caps = new ArrayList<>();
        caps.add("chat.completions");
        for (String key : metadata.keySet()) {
            Object value = metadata.get(key);
            if (value == null) continue;
            if ("context_length".equals(key)) continue;
            if ("max_tokens".equals(key)) continue;
            if ("display_name".equals(key)) continue;
            if ("description".equals(key)) continue;
            if ("output_price".equals(key)) continue;
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

    public record ListedModel(
            String id,
            String executionId,
            String canonicalId,
            String sourceType,
            String object,
            long created,
            String ownedBy,
            List<Object> permission,
            int contextLength,
            List<String> capabilities,
            Map<String, Object> pricing,
            String status,
            Map<String, Object> metadata
    ) {
    }
}
