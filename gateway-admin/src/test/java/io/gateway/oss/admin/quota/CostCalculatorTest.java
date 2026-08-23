package io.gateway.oss.admin.quota;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.ModelPricing;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.admin.sync.PricingSyncService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CostCalculatorTest {

    @Test
    void shouldUseModelSpecificPriceFirst() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0001"));
        ModelPricing perModel = new ModelPricing();
        perModel.setUnitPrice(new BigDecimal("0.0003"));
        properties.getPricing().getModels().put("gpt-4o-mini", perModel);

        CostCalculator calculator = new CostCalculator(properties);
        BigDecimal cost = calculator.calculate(request("gpt-4o-mini", 128), route("upstream-x"), 10L);

        assertEquals(new BigDecimal("0.003000"), cost);
    }

    @Test
    void shouldFallbackToDefaultPriceWhenModelPriceMissing() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0002"));

        CostCalculator calculator = new CostCalculator(properties);
        BigDecimal cost = calculator.calculate(request("unknown-model", 128), route("upstream-x"), 10L);

        assertEquals(new BigDecimal("0.002000"), cost);
    }

    @Test
    void shouldReturnZeroWhenPricingMissing() {
        GatewayProperties properties = new GatewayProperties();
        CostCalculator calculator = new CostCalculator(properties);

        BigDecimal cost = calculator.calculate(request("unknown-model", 128), route("upstream-x"), 10L);

        assertEquals(0, BigDecimal.ZERO.compareTo(cost));
    }

    @Test
    void shouldPreferRemotePricingWhenEnabled() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setPreferRemotePricing(true);
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0002"));
        ModelPricing staticModel = new ModelPricing();
        staticModel.setUnitPrice(new BigDecimal("0.0003"));
        properties.getPricing().getModels().put("gpt-4o-mini", staticModel);

        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(Map.of("gpt-4o-mini", new BigDecimal("0.0010")), java.time.Instant.now());

        CostCalculator calculator = new CostCalculator(properties, pricingSyncService);
        BigDecimal cost = calculator.calculate(request("gpt-4o-mini", 128), route("upstream-x"), 10L);

        assertEquals(new BigDecimal("0.003000"), cost);
    }

    @Test
    void shouldFallbackToStaticPricingWhenRemoteNotPreferred() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setPreferRemotePricing(false);
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0002"));
        ModelPricing staticModel = new ModelPricing();
        staticModel.setUnitPrice(new BigDecimal("0.0003"));
        properties.getPricing().getModels().put("gpt-4o-mini", staticModel);

        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(Map.of("gpt-4o-mini", new BigDecimal("0.0010")), java.time.Instant.now());

        CostCalculator calculator = new CostCalculator(properties, pricingSyncService);
        BigDecimal cost = calculator.calculate(request("gpt-4o-mini", 128), route("upstream-x"), 10L);

        assertEquals(new BigDecimal("0.003000"), cost);
    }

    @Test
    void shouldUseExactMappedSyncedPricingWhenConfigured() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getExactMatches().put("alias-model", "gpt-4o-mini");

        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(Map.of("gpt-4o-mini", new BigDecimal("0.0010")), java.time.Instant.now());

        CostCalculator calculator = new CostCalculator(properties, pricingSyncService);
        BigDecimal cost = calculator.calculate(request("alias-model", 128), route("upstream-x"), 10L);

        assertEquals(new BigDecimal("0.010000"), cost);
    }

    @Test
    void shouldUseFuzzySyncedFallbackWhenFormattingDiffers() {
        GatewayProperties properties = new GatewayProperties();

        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(Map.of("GPT_4O-MINI", new BigDecimal("0.0010")), java.time.Instant.now());

        CostCalculator calculator = new CostCalculator(properties, pricingSyncService);
        BigDecimal cost = calculator.calculate(request(" gpt-4o mini ", 128), route("upstream-x"), 10L);

        assertEquals(new BigDecimal("0.010000"), cost);
    }

    @Test
    void exactManualOverrideWinsOverExactMappingAndSyncedPricing() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setPreferRemotePricing(true);

        ModelPricing override = new ModelPricing();
        override.setUnitPrice(new BigDecimal("0.0007"));
        properties.getPricing().getModels().put("gpt-4o-mini", override);
        properties.getPricing().getExactMatches().put("gpt-4o-mini", "openai/gpt-4o-mini");
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0001"));

        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(
                Map.of(),
                Map.of("openai/gpt-4o-mini", new PricingSyncService.ModelPricingEntry(
                        new BigDecimal("0.0015"),
                        new BigDecimal("0.0015"),
                        new BigDecimal("0.0025"))),
                java.time.Instant.now());

        CostCalculator calculator = new CostCalculator(properties, pricingSyncService);
        BigDecimal cost = calculator.calculate(request("gpt-4o-mini", 128), route("gpt-4o-mini"), 10L);

        assertEquals(new BigDecimal("0.007000"), cost);
    }

    @Test
    void exactConfiguredModelMatchUsesMappedSyncedModel() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getExactMatches().put("alias-model", "gpt-4o-mini");
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0001"));

        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(
                Map.of(),
                Map.of("gpt-4o-mini", new PricingSyncService.ModelPricingEntry(
                        new BigDecimal("0.0009"), null, null)),
                java.time.Instant.now());

        CostCalculator calculator = new CostCalculator(properties, pricingSyncService);
        BigDecimal cost = calculator.calculate(request("alias-model", 128), route("ignored-upstream"), 10L);

        assertEquals(new BigDecimal("0.009000"), cost);
    }

    @Test
    void exactProviderQualifiedSyncedMatchUsesConfiguredMapping() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getExactMatches().put("alias-model", "openai/gpt-4o-mini");
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0001"));

        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(
                Map.of(),
                Map.of("openai/gpt-4o-mini", new PricingSyncService.ModelPricingEntry(
                        new BigDecimal("0.0008"), null, null)),
                java.time.Instant.now());

        CostCalculator calculator = new CostCalculator(properties, pricingSyncService);
        BigDecimal cost = calculator.calculate(request("alias-model", 128), route("gpt-4o-mini"), 10L);

        assertEquals(new BigDecimal("0.008000"), cost);
    }

    @Test
    void fuzzyFallbackWorksForSafeNormalizationDifferences() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0001"));

        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(
                Map.of(),
                Map.of("gpt_4o-mini", new PricingSyncService.ModelPricingEntry(
                        new BigDecimal("0.0006"), null, null)),
                java.time.Instant.now());

        CostCalculator calculator = new CostCalculator(properties, pricingSyncService);
        BigDecimal cost = calculator.calculate(request("gpt-4o.mini", 128), route("unused"), 10L);

        assertEquals(new BigDecimal("0.006000"), cost);
    }

    @Test
    void ambiguousFuzzyMatchFallsBackToDefaultPrice() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0002"));

        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(
                Map.of(),
                Map.of(
                        "gpt-4o-mini", new PricingSyncService.ModelPricingEntry(new BigDecimal("0.0006"), null, null),
                        "gpt_4o.mini", new PricingSyncService.ModelPricingEntry(new BigDecimal("0.0007"), null, null)
                ),
                java.time.Instant.now());

        CostCalculator calculator = new CostCalculator(properties, pricingSyncService);
        BigDecimal cost = calculator.calculate(request("gpt/4o:mini", 128), route("unused"), 10L);

        assertEquals(new BigDecimal("0.002000"), cost);
    }

    @Test
    void defaultPriceIsUsedOnlyAfterOverrideExactAndFuzzyFail() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getDefault().setUnitPrice(new BigDecimal("0.0004"));

        PricingSyncService pricingSyncService = new PricingSyncService();
        pricingSyncService.replaceSnapshot(Map.of("other-model", new BigDecimal("0.0009")), java.time.Instant.now());

        CostCalculator calculator = new CostCalculator(properties, pricingSyncService);
        BigDecimal cost = calculator.calculate(request("totally-unknown", 128), route("still-unknown"), 10L);

        assertEquals(new BigDecimal("0.004000"), cost);
    }

    private ChatCompletionsRequest request(String model, Integer maxTokens) {
        return new ChatCompletionsRequest(
                model,
                List.of(new ChatMessage("user", "hello")),
                false,
                0.7d,
                maxTokens
        );
    }

    private ResolvedRoute route(String upstreamModel) {
        return new ResolvedRoute(
                "gpt-4o-mini",
                "route-a",
                null,
                "openai",
                "openai-compatible",
                upstreamModel,
                "http://localhost:18080",
                "key",
                Duration.ofSeconds(3),
                2,
                List.of()
        );
    }
}
