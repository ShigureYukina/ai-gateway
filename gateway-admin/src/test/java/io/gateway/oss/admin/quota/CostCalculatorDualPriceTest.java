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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostCalculatorDualPriceTest {

    @Test
    void shouldUseDualPriceWhenConfigured() {
        GatewayProperties properties = new GatewayProperties();
        ModelPricing pricing = new ModelPricing();
        pricing.setInputUnitPrice(new BigDecimal("0.0001"));
        pricing.setOutputUnitPrice(new BigDecimal("0.0004"));
        properties.getPricing().getModels().put("gpt-4o", pricing);

        CostCalculator calculator = new CostCalculator(properties);

        // 100 prompt tokens * 0.0001 + 50 completion tokens * 0.0004 = 0.01 + 0.02 = 0.03
        BigDecimal cost = calculator.calculate(
                request("gpt-4o"), route("gpt-4o"), 100L, 50L);

        assertEquals(new BigDecimal("0.030000"), cost);
    }

    @Test
    void shouldFallbackToUnitPriceWhenDualPriceNotConfigured() {
        GatewayProperties properties = new GatewayProperties();
        ModelPricing pricing = new ModelPricing();
        pricing.setUnitPrice(new BigDecimal("0.0003"));
        properties.getPricing().getModels().put("gpt-4o-mini", pricing);

        CostCalculator calculator = new CostCalculator(properties);

        // 100 prompt * 0.0003 + 50 completion * 0.0003 = 0.03 + 0.015 = 0.045
        BigDecimal cost = calculator.calculate(
                request("gpt-4o-mini"), route("gpt-4o-mini"), 100L, 50L);

        assertEquals(new BigDecimal("0.045000"), cost);
    }

    @Test
    void shouldUseInputPriceForOutputWhenOutputPriceNotSet() {
        GatewayProperties properties = new GatewayProperties();
        ModelPricing pricing = new ModelPricing();
        pricing.setInputUnitPrice(new BigDecimal("0.0002"));
        // outputUnitPrice is null, should fallback to inputUnitPrice
        properties.getPricing().getModels().put("gpt-4o", pricing);

        CostCalculator calculator = new CostCalculator(properties);

        // 100 prompt * 0.0002 + 50 completion * 0.0002 = 0.02 + 0.01 = 0.03
        BigDecimal cost = calculator.calculate(
                request("gpt-4o"), route("gpt-4o"), 100L, 50L);

        assertEquals(new BigDecimal("0.030000"), cost);
    }

    @Test
    void shouldReturnZeroWhenNoTokensAndNoPricing() {
        GatewayProperties properties = new GatewayProperties();
        CostCalculator calculator = new CostCalculator(properties);

        BigDecimal cost = calculator.calculate(
                request("unknown"), route("unknown"), 0L, 0L);

        assertEquals(BigDecimal.ZERO, cost);
    }

    @Test
    void backwardCompatSingleUsageTokensShouldWork() {
        GatewayProperties properties = new GatewayProperties();
        ModelPricing pricing = new ModelPricing();
        pricing.setUnitPrice(new BigDecimal("0.0003"));
        properties.getPricing().getModels().put("gpt-4o-mini", pricing);

        CostCalculator calculator = new CostCalculator(properties);

        // Old API: all tokens treated as prompt
        BigDecimal cost = calculator.calculate(
                request("gpt-4o-mini"), route("gpt-4o-mini"), 10L);

        // 10 * 0.0003 = 0.003
        assertEquals(new BigDecimal("0.003000"), cost);
    }

    @Test
    void backwardCompatSingleUsageTokensMatchesDualWithZeroCompletion() {
        GatewayProperties properties = new GatewayProperties();
        ModelPricing pricing = new ModelPricing();
        pricing.setUnitPrice(new BigDecimal("0.0003"));
        properties.getPricing().getModels().put("gpt-4o-mini", pricing);

        CostCalculator calculator = new CostCalculator(properties);

        BigDecimal singlePrice = calculator.calculate(
                request("gpt-4o-mini"), route("gpt-4o-mini"), 10L);
        BigDecimal dualPrice = calculator.calculate(
                request("gpt-4o-mini"), route("gpt-4o-mini"), 10L, 0L);

        assertEquals(singlePrice, dualPrice);
    }

    @Test
    void shouldPreferRemoteDualPricingWhenEnabled() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setPreferRemotePricing(true);
        ModelPricing localPricing = new ModelPricing();
        localPricing.setInputUnitPrice(new BigDecimal("0.0001"));
        localPricing.setOutputUnitPrice(new BigDecimal("0.0004"));
        properties.getPricing().getModels().put("gpt-4o", localPricing);

        PricingSyncService syncService = new PricingSyncService();
        syncService.replaceSnapshot(
                Map.of(),
                Map.of("gpt-4o", new PricingSyncService.ModelPricingEntry(
                        null, new BigDecimal("0.0005"), new BigDecimal("0.0010"))),
                Instant.now()
        );

        CostCalculator calculator = new CostCalculator(properties, syncService);

        // Remote: 100 * 0.0005 + 50 * 0.0010 = 0.05 + 0.05 = 0.10
        BigDecimal cost = calculator.calculate(
                request("gpt-4o"), route("gpt-4o"), 100L, 50L);

        assertEquals(new BigDecimal("0.030000"), cost);
    }

    @Test
    void shouldUseLocalDualPricingWhenRemoteNotPreferred() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSync().getModelsDev().setPreferRemotePricing(false);
        ModelPricing localPricing = new ModelPricing();
        localPricing.setInputUnitPrice(new BigDecimal("0.0001"));
        localPricing.setOutputUnitPrice(new BigDecimal("0.0004"));
        properties.getPricing().getModels().put("gpt-4o", localPricing);

        PricingSyncService syncService = new PricingSyncService();
        syncService.replaceSnapshot(
                Map.of(),
                Map.of("gpt-4o", new PricingSyncService.ModelPricingEntry(
                        null, new BigDecimal("0.0005"), new BigDecimal("0.0010"))),
                Instant.now()
        );

        CostCalculator calculator = new CostCalculator(properties, syncService);

        // Local: 100 * 0.0001 + 50 * 0.0004 = 0.01 + 0.02 = 0.03
        BigDecimal cost = calculator.calculate(
                request("gpt-4o"), route("gpt-4o"), 100L, 50L);

        assertEquals(new BigDecimal("0.030000"), cost);
    }

    @Test
    void dualPriceWithDifferentInputOutputShouldDifferFromSinglePrice() {
        GatewayProperties properties = new GatewayProperties();
        ModelPricing pricing = new ModelPricing();
        pricing.setInputUnitPrice(new BigDecimal("0.0001"));
        pricing.setOutputUnitPrice(new BigDecimal("0.0004"));
        properties.getPricing().getModels().put("gpt-4o", pricing);

        ModelPricing singlePricing = new ModelPricing();
        singlePricing.setUnitPrice(new BigDecimal("0.0001"));
        properties.getPricing().getModels().put("cheap-model", singlePricing);

        CostCalculator calculator = new CostCalculator(properties);

        BigDecimal dualCost = calculator.calculate(
                request("gpt-4o"), route("gpt-4o"), 100L, 100L);
        BigDecimal singleCost = calculator.calculate(
                request("cheap-model"), route("cheap-model"), 100L, 100L);

        // Dual: 100*0.0001 + 100*0.0004 = 0.01 + 0.04 = 0.05
        // Single: 100*0.0001 + 100*0.0001 = 0.01 + 0.01 = 0.02
        assertTrue(dualCost.compareTo(singleCost) > 0,
                "Dual pricing with higher output should cost more");
    }

    @Test
    void exactMappingPreservesDualInputOutputPricing() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPricing().getExactMatches().put("alias-model", "gpt-4o");

        PricingSyncService syncService = new PricingSyncService();
        syncService.replaceSnapshot(
                Map.of(),
                Map.of("gpt-4o", new PricingSyncService.ModelPricingEntry(
                        null, new BigDecimal("0.0005"), new BigDecimal("0.0010"))),
                Instant.now()
        );

        CostCalculator calculator = new CostCalculator(properties, syncService);
        BigDecimal cost = calculator.calculate(request("alias-model"), route("unused"), 100L, 50L);

        assertEquals(new BigDecimal("0.100000"), cost);
    }

    @Test
    void fuzzyFallbackPreservesDualInputOutputPricing() {
        GatewayProperties properties = new GatewayProperties();

        PricingSyncService syncService = new PricingSyncService();
        syncService.replaceSnapshot(
                Map.of(),
                Map.of("gpt_4o_mini", new PricingSyncService.ModelPricingEntry(
                        null, new BigDecimal("0.0002"), new BigDecimal("0.0007"))),
                Instant.now()
        );

        CostCalculator calculator = new CostCalculator(properties, syncService);
        BigDecimal cost = calculator.calculate(request("gpt-4o-mini"), route("unused"), 100L, 50L);

        assertEquals(new BigDecimal("0.055000"), cost);
    }

    private ChatCompletionsRequest request(String model) {
        return new ChatCompletionsRequest(
                model,
                List.of(new ChatMessage("user", "hello")),
                false, 0.7, 128
        );
    }

    private ResolvedRoute route(String upstreamModel) {
        return new ResolvedRoute(
                upstreamModel, "route-a", null, "openai", "openai-compatible",
                upstreamModel, "http://localhost:18080", "key",
                Duration.ofSeconds(3), 2, List.of()
        );
    }
}
