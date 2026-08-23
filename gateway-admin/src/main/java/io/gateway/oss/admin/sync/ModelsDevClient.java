package io.gateway.oss.admin.sync;

import io.gateway.oss.core.config.ModelsDevConfig;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.util.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ModelsDevClient {

    private final WebClient.Builder webClientBuilder;
    private final GatewayConfigView configView;

    public ModelsDevClient(WebClient.Builder webClientBuilder,
                           GatewayConfigView configView) {
        this.webClientBuilder = webClientBuilder;
        this.configView = configView;
    }

    public Mono<ModelsDevSnapshot> fetchSnapshot() {
        ModelsDevConfig syncConfig = configView.getSync().getModelsDev();
        return webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build()
                .get()
                .uri(syncConfig.getEndpoint())
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(syncConfig.getTimeout())
                .map(this::parseSnapshot);
    }

    private ModelsDevSnapshot parseSnapshot(Map<String, Object> payload) {
        Map<String, Set<String>> providerModels = new LinkedHashMap<>();
        Map<String, BigDecimal> modelPrices = new LinkedHashMap<>();
        Map<String, PricingSyncService.ModelPricingEntry> modelPricings = new LinkedHashMap<>();
        Map<String, Map<String, Object>> modelMetadata = new LinkedHashMap<>();

        if (payload == null || payload.isEmpty()) {
            return new ModelsDevSnapshot(providerModels, modelPrices, Map.of(), modelMetadata, Instant.now());
        }

        Object providersNode = payload.get("providers");
        parseProvidersNode(providersNode, providerModels, modelPrices, modelPricings, modelMetadata);

        Object dataNode = payload.get("data");
        if (providerModels.isEmpty() || modelPrices.isEmpty()) {
            parseProvidersNode(dataNode, providerModels, modelPrices, modelPricings, modelMetadata);
        }

        Object modelsNode = payload.get("models");
        parseTopLevelModels(modelsNode, providerModels, modelPrices, modelPricings, modelMetadata);

        if (providerModels.isEmpty()) {
            parseProvidersNode(payload, providerModels, modelPrices, modelPricings, modelMetadata);
        }

        return new ModelsDevSnapshot(providerModels, modelPrices, modelPricings, modelMetadata, Instant.now());
    }

    private void parseProvidersNode(Object node,
                                    Map<String, Set<String>> providerModels,
                                    Map<String, BigDecimal> modelPrices,
                                    Map<String, PricingSyncService.ModelPricingEntry> modelPricings,
                                    Map<String, Map<String, Object>> modelMetadata) {
        if (node instanceof Map<?, ?> mapNode) {
            for (Map.Entry<?, ?> entry : mapNode.entrySet()) {
                String provider = text(entry.getKey());
                parseProviderEntry(provider, entry.getValue(), providerModels, modelPrices, modelPricings, modelMetadata);
            }
            return;
        }
        if (node instanceof List<?> listNode) {
            for (Object item : listNode) {
                if (!(item instanceof Map<?, ?> providerMap)) {
                    continue;
                }
                String provider = firstNonBlank(
                        valueAsText(providerMap.get("id")),
                        valueAsText(providerMap.get("name")),
                        valueAsText(providerMap.get("provider"))
                );
                parseProviderEntry(provider, providerMap, providerModels, modelPrices, modelPricings, modelMetadata);
            }
        }
    }

    private void parseProviderEntry(String provider,
                                    Object providerValue,
                                    Map<String, Set<String>> providerModels,
                                    Map<String, BigDecimal> modelPrices,
                                    Map<String, PricingSyncService.ModelPricingEntry> modelPricings,
                                    Map<String, Map<String, Object>> modelMetadata) {
        String normalizedProvider = StringUtils.blankToNull(provider);
        if (!(providerValue instanceof Map<?, ?> providerMap)) {
            return;
        }

        List<Object> modelCollections = new ArrayList<>();
        modelCollections.add(providerMap.get("models"));
        modelCollections.add(providerMap.get("model_list"));
        modelCollections.add(providerMap.get("items"));
        for (Object modelsNode : modelCollections) {
            parseModelCollection(normalizedProvider, modelsNode, providerModels, modelPrices, modelPricings, modelMetadata);
        }
    }

    private void parseTopLevelModels(Object modelsNode,
                                     Map<String, Set<String>> providerModels,
                                     Map<String, BigDecimal> modelPrices,
                                     Map<String, PricingSyncService.ModelPricingEntry> modelPricings,
                                     Map<String, Map<String, Object>> modelMetadata) {
        if (!(modelsNode instanceof List<?> listNode)) {
            return;
        }
        for (Object item : listNode) {
            if (!(item instanceof Map<?, ?> modelMap)) {
                continue;
            }
            String provider = firstNonBlank(
                    valueAsText(modelMap.get("provider")),
                    valueAsText(modelMap.get("vendor"))
            );
            String model = firstNonBlank(
                    valueAsText(modelMap.get("id")),
                    valueAsText(modelMap.get("name")),
                    valueAsText(modelMap.get("model"))
            );
            addModel(providerModels, provider, model);
            PricingSyncService.ModelPricingEntry pricingEntry = extractPricingEntry(modelMap);
            BigDecimal price = pricingEntry == null ? null : firstNonNull(pricingEntry.unitPrice(), pricingEntry.inputUnitPrice(), pricingEntry.outputUnitPrice());
            if (price != null && model != null && !model.isBlank()) {
                modelPrices.put(model, price);
            }
            if (pricingEntry != null && model != null && !model.isBlank()) {
                modelPricings.put(model, pricingEntry);
            }
            if (model != null && !model.isBlank()) {
                Map<String, Object> metadata = extractModelMetadata(modelMap);
                if (!metadata.isEmpty()) {
                    modelMetadata.put(model, metadata);
                }
            }
        }
    }

    private void parseModelCollection(String provider,
                                      Object modelsNode,
                                      Map<String, Set<String>> providerModels,
                                      Map<String, BigDecimal> modelPrices,
                                      Map<String, PricingSyncService.ModelPricingEntry> modelPricings,
                                      Map<String, Map<String, Object>> modelMetadata) {
        if (modelsNode instanceof Map<?, ?> mapNode) {
            for (Map.Entry<?, ?> entry : mapNode.entrySet()) {
                String model = text(entry.getKey());
                addModel(providerModels, provider, model);
                if (entry.getValue() instanceof Map<?, ?> modelMap) {
                    PricingSyncService.ModelPricingEntry pricingEntry = extractPricingEntry(modelMap);
                    BigDecimal price = pricingEntry == null ? null : firstNonNull(pricingEntry.unitPrice(), pricingEntry.inputUnitPrice(), pricingEntry.outputUnitPrice());
                    if (price != null && model != null && !model.isBlank()) {
                        modelPrices.put(model, price);
                    }
                    if (pricingEntry != null && model != null && !model.isBlank()) {
                        modelPricings.put(model, pricingEntry);
                    }
                    if (model != null && !model.isBlank()) {
                        Map<String, Object> metadata = extractModelMetadata(modelMap);
                        if (!metadata.isEmpty()) {
                            modelMetadata.put(model, metadata);
                        }
                    }
                }
            }
            return;
        }

        if (!(modelsNode instanceof List<?> listNode)) {
            return;
        }
        for (Object item : listNode) {
            if (!(item instanceof Map<?, ?> modelMap)) {
                continue;
            }
            String model = firstNonBlank(
                    valueAsText(modelMap.get("id")),
                    valueAsText(modelMap.get("name")),
                    valueAsText(modelMap.get("model"))
            );
            addModel(providerModels, provider, model);
            PricingSyncService.ModelPricingEntry pricingEntry = extractPricingEntry(modelMap);
            BigDecimal price = pricingEntry == null ? null : firstNonNull(pricingEntry.unitPrice(), pricingEntry.inputUnitPrice(), pricingEntry.outputUnitPrice());
            if (price != null && model != null && !model.isBlank()) {
                modelPrices.put(model, price);
            }
            if (pricingEntry != null && model != null && !model.isBlank()) {
                modelPricings.put(model, pricingEntry);
            }
            if (model != null && !model.isBlank()) {
                Map<String, Object> metadata = extractModelMetadata(modelMap);
                if (!metadata.isEmpty()) {
                    modelMetadata.put(model, metadata);
                }
            }
        }
    }

    private PricingSyncService.ModelPricingEntry extractPricingEntry(Map<?, ?> modelMap) {
        BigDecimal inputPrice = null;
        BigDecimal outputPrice = null;
        BigDecimal unitPrice = null;

        Object costNode = modelMap.get("cost");
        if (costNode instanceof Map<?, ?> costMap) {
            inputPrice = firstNonNull(toDecimal(costMap.get("input")), toDecimal(costMap.get("input_per_token")));
            outputPrice = firstNonNull(toDecimal(costMap.get("output")), toDecimal(costMap.get("output_per_token")));
            unitPrice = toDecimal(costMap.get("unit_price"));
        }

        Object pricingNode = modelMap.get("pricing");
        if (pricingNode instanceof Map<?, ?> pricingMap) {
            unitPrice = firstNonNull(unitPrice, toDecimal(pricingMap.get("unit_price")));
            inputPrice = firstNonNull(inputPrice, toDecimal(pricingMap.get("input_per_token")), toDecimal(pricingMap.get("input")));
            outputPrice = firstNonNull(outputPrice, toDecimal(pricingMap.get("output_per_token")), toDecimal(pricingMap.get("output")));
        }

        unitPrice = firstNonNull(unitPrice, toDecimal(modelMap.get("unit_price")));
        inputPrice = firstNonNull(inputPrice, toDecimal(modelMap.get("input_price")), toDecimal(modelMap.get("input_per_token")));
        outputPrice = firstNonNull(outputPrice, toDecimal(modelMap.get("output_price")), toDecimal(modelMap.get("output_per_token")));
        unitPrice = firstNonNull(unitPrice, inputPrice, outputPrice);

        if (unitPrice == null && inputPrice == null && outputPrice == null) {
            return null;
        }
        return new PricingSyncService.ModelPricingEntry(unitPrice, inputPrice, outputPrice);
    }

    private Map<String, Object> extractModelMetadata(Map<?, ?> modelMap) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        // context_length: from modelMap directly
        Object contextLength = modelMap.get("context_length");
        if (contextLength instanceof Number n) {
            metadata.put("context_length", n.intValue());
        }

        // max_tokens: from modelMap directly
        Object maxTokens = modelMap.get("max_tokens");
        if (maxTokens instanceof Number n) {
            metadata.put("max_tokens", n.intValue());
        }

        // display_name: from display_name or name
        String displayName = valueAsText(modelMap.get("display_name"));
        if (displayName == null) {
            displayName = valueAsText(modelMap.get("name"));
        }
        if (displayName != null && !displayName.isBlank()) {
            metadata.put("display_name", displayName);
        }

        // description
        String description = valueAsText(modelMap.get("description"));
        if (description != null && !description.isBlank()) {
            metadata.put("description", description);
        }

        // output_price: pricing.output_per_token, pricing.output, then top-level output_price, output_per_token
        BigDecimal outputPrice = null;
        Object costNode = modelMap.get("cost");
        if (costNode instanceof Map<?, ?> costMap) {
            outputPrice = toDecimal(costMap.get("output"));
            if (outputPrice == null) {
                outputPrice = toDecimal(costMap.get("output_per_token"));
            }
        }
        Object pricingNode = modelMap.get("pricing");
        if (outputPrice == null && pricingNode instanceof Map<?, ?> pricingMap) {
            outputPrice = toDecimal(pricingMap.get("output_per_token"));
            if (outputPrice == null) {
                outputPrice = toDecimal(pricingMap.get("output"));
            }
        }
        if (outputPrice == null) {
            outputPrice = toDecimal(modelMap.get("output_price"));
        }
        if (outputPrice == null) {
            outputPrice = toDecimal(modelMap.get("output_per_token"));
        }
        if (outputPrice != null) {
            metadata.put("output_price", outputPrice);
        }

        extractBooleanCapability(modelMap, metadata, "supports_files");
        extractBooleanCapability(modelMap, metadata, "supports_images");
        extractBooleanCapability(modelMap, metadata, "supports_vision");
        extractBooleanCapability(modelMap, metadata, "supports_audio");
        extractBooleanCapability(modelMap, metadata, "supports_tools");

        return metadata;
    }

    private void extractBooleanCapability(Map<?, ?> modelMap, Map<String, Object> metadata, String key) {
        Object val = modelMap.get(key);
        if (val instanceof Boolean b && b) {
            metadata.put(key, true);
        }
    }

    private void addModel(Map<String, Set<String>> providerModels, String provider, String model) {
        String normalizedProvider = StringUtils.blankToNull(provider);
        String normalizedModel = StringUtils.blankToNull(model);
        if (normalizedProvider == null || normalizedModel == null) {
            return;
        }
        providerModels.computeIfAbsent(normalizedProvider, ignored -> new LinkedHashSet<>())
                .add(normalizedModel);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = StringUtils.blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String valueAsText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal parsed;
            if (value instanceof Number number) {
                parsed = new BigDecimal(number.toString());
            } else {
                String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
                if (text.isEmpty()) {
                    return null;
                }
                parsed = new BigDecimal(text);
            }
            return parsed.signum() < 0 ? null : parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public record ModelsDevSnapshot(
            Map<String, Set<String>> providerModels,
            Map<String, BigDecimal> modelPrices,
            Map<String, PricingSyncService.ModelPricingEntry> modelPricings,
            Map<String, Map<String, Object>> modelMetadata,
            Instant fetchedAt
    ) {
    }
}
