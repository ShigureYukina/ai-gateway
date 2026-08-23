package io.gateway.oss.admin.sync;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PricingSyncService {

    private final AtomicLong versionCounter = new AtomicLong(0L);
    private final AtomicReference<PricingSnapshot> snapshotRef =
            new AtomicReference<>(PricingSnapshot.empty());

    public PricingSnapshot getSnapshot() {
        return snapshotRef.get();
    }

    /**
     * Resets all internal state to initial values. For use by test cleanup only.
     */
    public void resetForTests() {
        versionCounter.set(0L);
        snapshotRef.set(PricingSnapshot.empty());
    }

    public BigDecimal getUnitPrice(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return snapshotRef.get().modelUnitPrices().get(model);
    }

    public BigDecimal getInputUnitPrice(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        ModelPricingEntry entry = snapshotRef.get().modelPricings().get(model);
        if (entry == null) {
            return null;
        }
        return entry.inputUnitPrice() != null ? entry.inputUnitPrice() : entry.unitPrice();
    }

    public BigDecimal getOutputUnitPrice(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        ModelPricingEntry entry = snapshotRef.get().modelPricings().get(model);
        if (entry == null) {
            return null;
        }
        if (entry.outputUnitPrice() != null) return entry.outputUnitPrice();
        if (entry.inputUnitPrice() != null) return entry.inputUnitPrice();
        return entry.unitPrice();
    }

    public void replaceSnapshot(Map<String, BigDecimal> modelUnitPrices, Instant now) {
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        Map<String, ModelPricingEntry> pricingEntries = new LinkedHashMap<>();
        if (modelUnitPrices != null) {
            for (Map.Entry<String, BigDecimal> entry : modelUnitPrices.entrySet()) {
                String model = normalizeKey(entry.getKey());
                BigDecimal price = normalizePrice(entry.getValue());
                if (model == null || price == null) {
                    continue;
                }
                normalized.put(model, price);
                pricingEntries.put(model, new ModelPricingEntry(price, null, null));
            }
        }

        snapshotRef.set(new PricingSnapshot(
                Map.copyOf(normalized),
                Map.copyOf(pricingEntries),
                now == null ? Instant.now() : now,
                versionCounter.incrementAndGet()
        ));
    }

    public void replaceSnapshot(Map<String, BigDecimal> modelUnitPrices,
                                 Map<String, ModelPricingEntry> modelPricings,
                                 Instant now) {
        Map<String, BigDecimal> normalizedUnitPrices = new LinkedHashMap<>();
        Map<String, ModelPricingEntry> normalizedPricings = new LinkedHashMap<>();

        if (modelUnitPrices != null) {
            for (Map.Entry<String, BigDecimal> entry : modelUnitPrices.entrySet()) {
                String model = normalizeKey(entry.getKey());
                BigDecimal price = normalizePrice(entry.getValue());
                if (model == null || price == null) {
                    continue;
                }
                normalizedUnitPrices.put(model, price);
                // Also add to modelPricings so getInputUnitPrice/getOutputUnitPrice can find it
                if (!normalizedPricings.containsKey(model)) {
                    normalizedPricings.put(model, new ModelPricingEntry(price, null, null));
                }
            }
        }

        if (modelPricings != null) {
            for (Map.Entry<String, ModelPricingEntry> entry : modelPricings.entrySet()) {
                String model = normalizeKey(entry.getKey());
                if (model == null) {
                    continue;
                }
                ModelPricingEntry pricing = entry.getValue();
                normalizedPricings.put(model, pricing);
                // Also populate legacy unitPrice map from the first non-null price
                if (!normalizedUnitPrices.containsKey(model)) {
                    BigDecimal fallback = pricing.unitPrice() != null ? pricing.unitPrice()
                            : pricing.inputUnitPrice();
                    if (fallback != null) {
                        normalizedUnitPrices.put(model, fallback);
                    }
                }
            }
        }

        snapshotRef.set(new PricingSnapshot(
                Map.copyOf(normalizedUnitPrices),
                Map.copyOf(normalizedPricings),
                now == null ? Instant.now() : now,
                versionCounter.incrementAndGet()
        ));
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BigDecimal normalizePrice(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return null;
        }
        return value;
    }

    public record ModelPricingEntry(
            BigDecimal unitPrice,
            BigDecimal inputUnitPrice,
            BigDecimal outputUnitPrice
    ) {}

    public record PricingSnapshot(
            Map<String, BigDecimal> modelUnitPrices,
            Map<String, ModelPricingEntry> modelPricings,
            Instant updatedAt,
            long version
    ) {
        public static PricingSnapshot empty() {
            return new PricingSnapshot(Map.of(), Map.of(), Instant.EPOCH, 0L);
        }
    }
}
