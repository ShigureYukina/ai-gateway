package io.gateway.oss.admin.web;

import io.gateway.oss.admin.pricing.BillingPriceResolver;
import io.gateway.oss.admin.sync.ModelListService;
import io.gateway.oss.admin.sync.ProviderModelCatalogService;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.config.SceneConfig;
import io.gateway.oss.core.contract.ModelPublicationConfigWriter;
import io.gateway.oss.core.contract.PricingConfigView;
import io.gateway.oss.core.contract.PricingPublicationConfigView;
import io.gateway.oss.core.contract.ProviderConfigView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.SceneConfigView;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelPublicationService {

    private final PricingPublicationConfigView gatewayConfigView;
    private final ModelPublicationConfigWriter modelPublicationConfigWriter;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final BillingPriceResolver billingPriceResolver;
    private final ModelListService modelListService;

    public ModelPublicationService(PricingPublicationConfigView gatewayConfigView,
                                   ModelPublicationConfigWriter modelPublicationConfigWriter,
                                   ProviderModelCatalogService providerModelCatalogService,
                                   BillingPriceResolver billingPriceResolver,
                                   ModelListService modelListService) {
        this.gatewayConfigView = gatewayConfigView;
        this.modelPublicationConfigWriter = modelPublicationConfigWriter;
        this.providerModelCatalogService = providerModelCatalogService;
        this.billingPriceResolver = billingPriceResolver;
        this.modelListService = modelListService;
    }

    public Mono<PublishOutcome> publish(String alias, PublishRequest request) {
        String normalizedAlias = requireText(alias, "alias must not be blank");
        String provider = requireText(request.provider(), "provider must not be blank");
        String upstreamModel = requireText(request.upstreamModel(), "upstreamModel must not be blank");

        ProviderConfigView providerConfig = gatewayConfigView.getProviders().get(provider);
        if (providerConfig == null) {
            return Mono.error(new IllegalArgumentException("Provider not found: " + provider));
        }
        if (!providerConfig.isEnabled()) {
            return Mono.error(new IllegalArgumentException("Provider is disabled: " + provider));
        }
        if (!providerHasModel(provider, providerConfig, upstreamModel)) {
            return Mono.error(new IllegalArgumentException(
                    "upstreamModel not found in provider catalog or configured models: " + upstreamModel));
        }

        boolean created = !gatewayConfigView.getRoutes().containsKey(normalizedAlias);
        String sceneId = normalizedAlias + "-scene";
        String primaryRouteId = normalizedAlias + "-primary";
        ExistingPublication existingPublication = resolveExistingPublication(normalizedAlias);

        RouteConfig primaryRoute = new RouteConfig();
        primaryRoute.setProvider(provider);
        primaryRoute.setUpstreamModel(upstreamModel);
        primaryRoute.setWeight(1);
        primaryRoute.setEnabled(true);

        SceneConfig sceneConfig = new SceneConfig();
        sceneConfig.setPrimaryRoute(primaryRouteId);
        sceneConfig.setFallbackRoutes(List.of());

        RouteConfig aliasRoute = new RouteConfig();
        aliasRoute.setScene(sceneId);
        aliasRoute.setEnabled(true);

        PricingConfig updatedPricing = mergeExactMatch(gatewayConfigView.getPricing(), normalizedAlias, upstreamModel);
        Mono<Void> flow = modelPublicationConfigWriter.saveRoute(primaryRouteId, primaryRoute)
                .then(modelPublicationConfigWriter.saveScene(sceneId, sceneConfig))
                .then(modelPublicationConfigWriter.saveRoute(normalizedAlias, aliasRoute));

        if (updatedPricing != null) {
            flow = flow.then(modelPublicationConfigWriter.saveSystemPricing(updatedPricing));
        }
        for (String obsoleteRouteId : existingPublication.obsoleteRouteIds(primaryRouteId)) {
            flow = flow.then(modelPublicationConfigWriter.deleteRoute(obsoleteRouteId));
        }
        if (existingPublication.shouldDeleteScene(sceneId)) {
            flow = flow.then(modelPublicationConfigWriter.deleteScene(existingPublication.sceneId()));
        }

        return flow
                .then(Mono.fromSupplier(() -> new PublishOutcome(created,
                        buildResponse(normalizedAlias, provider, upstreamModel))));
    }

    private PublicationResponse buildResponse(String alias, String provider, String upstreamModel) {
        Map<String, Object> pricingPreview = billingPriceResolver.preview(alias, upstreamModel, provider);
        boolean visibleInV1Models = !modelListService.buildModels(null, alias).isEmpty();
        List<String> warnings = buildWarnings(pricingPreview, visibleInV1Models);
        return new PublicationResponse(
                alias,
                provider,
                upstreamModel,
                visibleInV1Models,
                new PriceSummary(
                        stringValue(pricingPreview.get("source")),
                        stringValue(pricingPreview.get("matchedBy")),
                        stringValue(pricingPreview.get("matchedModel")),
                        decimalValue(pricingPreview.get("unitPrice")),
                        decimalValue(pricingPreview.get("inputUnitPrice")),
                        decimalValue(pricingPreview.get("outputUnitPrice")),
                        buildPriceSummary(pricingPreview)
                ),
                warnings
        );
    }

    private List<String> buildWarnings(Map<String, Object> pricingPreview, boolean visibleInV1Models) {
        List<String> warnings = new ArrayList<>();
        String source = stringValue(pricingPreview.get("source"));
        String matchedBy = stringValue(pricingPreview.get("matchedBy"));
        if (!visibleInV1Models) {
            // /v1/models 当前优先读取 snapshot；发布配置已保存，但不一定立刻成为主展示项。
            warnings.add("当前 /v1/models 优先返回 snapshot 数据，alias 可能暂不可见；发布配置已保存。");
        }
        if ("configured_default".equals(source)) {
            warnings.add("价格未精确解析，当前回落到默认定价配置，请人工确认 exact match 或同步定价快照。");
        } else if ("fuzzy_name_fallback".equals(matchedBy)) {
            warnings.add("价格通过归一化名称模糊匹配得到，建议后续补充 exact match 以避免歧义。");
        }
        return List.copyOf(warnings);
    }

    private String buildPriceSummary(Map<String, Object> pricingPreview) {
        String source = stringValue(pricingPreview.get("source"));
        String matchedBy = stringValue(pricingPreview.get("matchedBy"));
        String matchedModel = stringValue(pricingPreview.get("matchedModel"));
        if (matchedModel != null && matchedBy != null) {
            return "source=" + source + ", matchedBy=" + matchedBy + ", matchedModel=" + matchedModel;
        }
        if (matchedBy != null) {
            return "source=" + source + ", matchedBy=" + matchedBy;
        }
        return "source=" + source;
    }

    private boolean providerHasModel(String provider, ProviderConfigView providerConfig, String upstreamModel) {
        if (providerModelCatalogService.hasModel(provider, upstreamModel)) {
            return true;
        }
        List<String> configuredModels = providerConfig.getModels();
        return configuredModels != null && configuredModels.contains(upstreamModel);
    }

    private ExistingPublication resolveExistingPublication(String alias) {
        RouteConfigView aliasRoute = gatewayConfigView.getRoutes().get(alias);
        if (aliasRoute == null || aliasRoute.getScene() == null || aliasRoute.getScene().isBlank()) {
            return ExistingPublication.empty();
        }
        String existingSceneId = aliasRoute.getScene();
        SceneConfigView sceneConfig = gatewayConfigView.getScenes().get(existingSceneId);
        if (sceneConfig == null) {
            return ExistingPublication.empty();
        }
        List<String> routeIds = new ArrayList<>();
        if (sceneConfig.getPrimaryRoute() != null && !sceneConfig.getPrimaryRoute().isBlank()) {
            routeIds.add(sceneConfig.getPrimaryRoute());
        }
        if (sceneConfig.getFallbackRoutes() != null) {
            routeIds.addAll(sceneConfig.getFallbackRoutes());
        }
        return new ExistingPublication(existingSceneId, List.copyOf(routeIds));
    }

    private PricingConfig mergeExactMatch(PricingConfigView current, String alias, String upstreamModel) {
        PricingConfigView pricingConfig = current == null ? new PricingConfig() : current;
        Map<String, String> currentExactMatches = pricingConfig.getExactMatches() == null
                ? Map.of() : pricingConfig.getExactMatches();
        if (upstreamModel.equals(currentExactMatches.get(alias))) {
            return null;
        }

        PricingConfig merged = new PricingConfig();
        merged.setDefault(pricingConfig.getDefault());
        merged.setModels(pricingConfig.getModels() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(pricingConfig.getModels()));
        Map<String, String> exactMatches = new LinkedHashMap<>(currentExactMatches);
        exactMatches.put(alias, upstreamModel);
        merged.setExactMatches(exactMatches);
        return merged;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    private record ExistingPublication(String sceneId, List<String> routeIds) {
        static ExistingPublication empty() {
            return new ExistingPublication(null, List.of());
        }

        List<String> obsoleteRouteIds(String newPrimaryRouteId) {
            List<String> obsolete = new ArrayList<>();
            for (String routeId : routeIds) {
                if (!newPrimaryRouteId.equals(routeId)) {
                    obsolete.add(routeId);
                }
            }
            return obsolete;
        }

        boolean shouldDeleteScene(String newSceneId) {
            return sceneId != null && !sceneId.isBlank() && !sceneId.equals(newSceneId);
        }
    }

    public record PublishRequest(String provider, String upstreamModel) {
    }

    public record PublishOutcome(boolean created, PublicationResponse response) {
    }

    public record PublicationResponse(String alias,
                                      String provider,
                                      String upstreamModel,
                                      boolean visibleInV1Models,
                                      PriceSummary price,
                                      List<String> warnings) {
    }

    public record PriceSummary(String source,
                               String matchedBy,
                               String matchedModel,
                               BigDecimal unitPrice,
                               BigDecimal inputUnitPrice,
                               BigDecimal outputUnitPrice,
                               String summary) {
    }
}
