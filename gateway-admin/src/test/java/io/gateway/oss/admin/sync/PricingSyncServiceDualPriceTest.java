package io.gateway.oss.admin.sync;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PricingSyncServiceDualPriceTest {

    @Test
    void shouldReturnInputAndOutputPricesFromDualEntry() {
        PricingSyncService service = new PricingSyncService();
        service.replaceSnapshot(
                Map.of(),
                Map.of("gpt-4o", new PricingSyncService.ModelPricingEntry(
                        null, new BigDecimal("0.0005"), new BigDecimal("0.0010"))),
                Instant.now()
        );

        assertEquals(new BigDecimal("0.0005"), service.getInputUnitPrice("gpt-4o"));
        assertEquals(new BigDecimal("0.0010"), service.getOutputUnitPrice("gpt-4o"));
    }

    @Test
    void shouldFallbackToUnitPriceWhenInputOutputNotSet() {
        PricingSyncService service = new PricingSyncService();
        service.replaceSnapshot(
                Map.of("gpt-4o-mini", new BigDecimal("0.0003")),
                Instant.now()
        );

        // Should fallback to unitPrice for both input and output
        assertEquals(new BigDecimal("0.0003"), service.getInputUnitPrice("gpt-4o-mini"));
        assertEquals(new BigDecimal("0.0003"), service.getOutputUnitPrice("gpt-4o-mini"));
    }

    @Test
    void shouldReturnNullForUnknownModel() {
        PricingSyncService service = new PricingSyncService();
        service.replaceSnapshot(
                Map.of("gpt-4o", new BigDecimal("0.0003")),
                Instant.now()
        );

        assertNull(service.getInputUnitPrice("unknown-model"));
        assertNull(service.getOutputUnitPrice("unknown-model"));
    }

    @Test
    void shouldReturnNullForBlankModel() {
        PricingSyncService service = new PricingSyncService();

        assertNull(service.getInputUnitPrice(""));
        assertNull(service.getInputUnitPrice(null));
        assertNull(service.getOutputUnitPrice(""));
        assertNull(service.getOutputUnitPrice(null));
    }

    @Test
    void shouldFallbackToUnitPriceWhenOnlyUnitPriceInEntry() {
        PricingSyncService service = new PricingSyncService();
        service.replaceSnapshot(
                Map.of(),
                Map.of("gpt-4o", new PricingSyncService.ModelPricingEntry(
                        new BigDecimal("0.0003"), null, null)),
                Instant.now()
        );

        assertEquals(new BigDecimal("0.0003"), service.getInputUnitPrice("gpt-4o"));
        assertEquals(new BigDecimal("0.0003"), service.getOutputUnitPrice("gpt-4o"));
    }

    @Test
    void shouldFallbackToInputPriceWhenOutputNotSet() {
        PricingSyncService service = new PricingSyncService();
        service.replaceSnapshot(
                Map.of(),
                Map.of("gpt-4o", new PricingSyncService.ModelPricingEntry(
                        null, new BigDecimal("0.0005"), null)),
                Instant.now()
        );

        assertEquals(new BigDecimal("0.0005"), service.getInputUnitPrice("gpt-4o"));
        // Output should fallback to input
        assertEquals(new BigDecimal("0.0005"), service.getOutputUnitPrice("gpt-4o"));
    }

    @Test
    void shouldMaintainLegacyUnitPriceMapWithDualEntries() {
        PricingSyncService service = new PricingSyncService();
        service.replaceSnapshot(
                Map.of(),
                Map.of("gpt-4o", new PricingSyncService.ModelPricingEntry(
                        null, new BigDecimal("0.0005"), new BigDecimal("0.0010"))),
                Instant.now()
        );

        // Legacy getUnitPrice should still work (uses first available price)
        assertEquals(new BigDecimal("0.0005"), service.getUnitPrice("gpt-4o"));
    }

    @Test
    void shouldHandleMixedLegacyAndDualEntries() {
        PricingSyncService service = new PricingSyncService();
        service.replaceSnapshot(
                Map.of("legacy-model", new BigDecimal("0.0002")),
                Map.of("dual-model", new PricingSyncService.ModelPricingEntry(
                        null, new BigDecimal("0.0005"), new BigDecimal("0.0010"))),
                Instant.now()
        );

        // Legacy model
        assertEquals(new BigDecimal("0.0002"), service.getInputUnitPrice("legacy-model"));
        assertEquals(new BigDecimal("0.0002"), service.getOutputUnitPrice("legacy-model"));

        // Dual model
        assertEquals(new BigDecimal("0.0005"), service.getInputUnitPrice("dual-model"));
        assertEquals(new BigDecimal("0.0010"), service.getOutputUnitPrice("dual-model"));
    }
}
