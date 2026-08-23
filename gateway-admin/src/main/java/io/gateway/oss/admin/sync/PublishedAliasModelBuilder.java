package io.gateway.oss.admin.sync;

import io.gateway.oss.admin.pricing.BillingPriceResolver;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.SceneConfigView;
import io.gateway.oss.core.upstream.RouteResilienceTracker;
import io.gateway.oss.core.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class PublishedAliasModelBuilder {

    private final GatewayConfigView configView;
    private final RouteResilienceTracker resilienceTracker;
    private final BillingPriceResolver billingPriceResolver;
    private final PublicModelMetadataService publicModelMetadataService;
    private final ModelMetadataService metadataService;

    PublishedAliasModelBuilder(GatewayConfigView configView,
                               RouteResilienceTracker resilienceTracker,
                               BillingPriceResolver billingPriceResolver,
                               PublicModelMetadataService publicModelMetadataService,
                               ModelMetadataService metadataService) {
        this.configView = configView;
        this.resilienceTracker = resilienceTracker;
        this.billingPriceResolver = billingPriceResolver;
        this.publicModelMetadataService = publicModelMetadataService;
        this.metadataService = metadataService;
    }

    public List<ModelListService.ListedModel> build(String providerFilter, String modelFilter) {
        String np = StringUtils.blankToNull(providerFilter);
        String nm = StringUtils.blankToNull(modelFilter);
        List<ModelListService.ListedModel> result = new ArrayList<>();

        for (Map.Entry<String, ? extends RouteConfigView> entry : configView.getRoutes().entrySet()) {
            String alias = entry.getKey();
            RouteConfigView route = entry.getValue();
            String sceneId = route.getScene();

            if (sceneId == null || sceneId.isBlank()) {
                continue;
            }

            SceneConfigView scene = configView.getScenes().get(sceneId);
            if (scene == null) {
                continue;
            }

            RouteConfigView primaryRoute = configView.getRoutes().get(scene.getPrimaryRoute());
            String ownedBy = (primaryRoute != null && primaryRoute.getProvider() != null)
                    ? primaryRoute.getProvider() : "";

            if (np != null && !np.equals(ownedBy)) {
                continue;
            }
            if (nm != null && !nm.equals(alias)) {
                continue;
            }

            result.add(buildModelGroupObject(alias, sceneId, scene));
        }
        return result;
    }

    private ModelListService.ListedModel buildModelGroupObject(String alias, String sceneId, SceneConfigView scene) {
        List<Map<String, String>> members = new ArrayList<>();
        List<String> allRouteIds = new ArrayList<>();
        allRouteIds.add(scene.getPrimaryRoute());
        if (scene.getFallbackRoutes() != null) {
            allRouteIds.addAll(scene.getFallbackRoutes());
        }

        for (String routeId : allRouteIds) {
            RouteConfigView rc = configView.getRoutes().get(routeId);
            if (rc != null) {
                Map<String, String> member = new HashMap<>();
                member.put("routeId", routeId);
                member.put("provider", rc.getProvider() != null ? rc.getProvider() : "");
                member.put("upstreamModel", rc.getUpstreamModel() != null ? rc.getUpstreamModel() : "");
                members.add(member);
            }
        }

        RouteConfigView primaryRoute = configView.getRoutes().get(scene.getPrimaryRoute());
        String ownedBy = (primaryRoute != null && primaryRoute.getProvider() != null)
                ? primaryRoute.getProvider() : "";
        String canonicalId = buildCanonicalId(
                primaryRoute != null ? primaryRoute.getProvider() : null,
                primaryRoute != null ? primaryRoute.getUpstreamModel() : null
        );

        int contextLength = 0;
        List<String> capabilities = List.of("chat.completions");
        Map<String, Object> dbPricing = null;

        PublicModelMetadataService.ModelMetadata publicMetadata = publicModelMetadataService.findByAlias(alias);
        Map<String, Object> caps = publicMetadata.capabilities();
        if (!caps.isEmpty()) {
            Object clObj = caps.get("context_length");
            if (clObj instanceof Number n) {
                contextLength = n.intValue();
            }
            capabilities = new ArrayList<>();
            for (String key : caps.keySet()) {
                if (!key.equals("context_length")) {
                    capabilities.add(key);
                }
            }
            if (capabilities.isEmpty()) {
                capabilities = List.of("chat.completions");
            }
        }
        if (!publicMetadata.pricing().isEmpty()) {
            dbPricing = publicMetadata.pricing();
        }

        Map<String, Object> resolvedPricing = dbPricing != null ? dbPricing : pricing(
                alias,
                primaryRoute != null ? primaryRoute.getUpstreamModel() : null,
                primaryRoute != null ? primaryRoute.getProvider() : null
        );
        String status = resilienceTracker.isAvailable(alias) ? "available" : "degraded";

        Map<String, Object> modelMetadata = new HashMap<>();
        modelMetadata.put("members", members);
        modelMetadata.put("scene", sceneId);

        return new ModelListService.ListedModel(
                alias,
                alias,
                canonicalId,
                "model_group",
                "model",
                0,
                ownedBy,
                List.of(),
                contextLength,
                capabilities,
                resolvedPricing,
                status,
                modelMetadata
        );
    }

    private String buildCanonicalId(String provider, String upstreamModel) {
        if (provider == null || provider.isBlank() || upstreamModel == null || upstreamModel.isBlank()) {
            return null;
        }
        return provider + "/" + upstreamModel;
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
