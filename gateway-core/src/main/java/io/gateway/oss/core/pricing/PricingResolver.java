package io.gateway.oss.core.pricing;

import java.math.BigDecimal;

public interface PricingResolver {

    ResolvedPricing resolve(String requestedModel, String upstreamModel, String provider);

    record ResolvedPricing(
            BigDecimal unitPrice,
            BigDecimal inputUnitPrice,
            BigDecimal outputUnitPrice,
            String source,
            String matchedModel,
            String matchedBy
    ) {
        public static ResolvedPricing unresolved() {
            return new ResolvedPricing(null, null, null, "unresolved", null, null);
        }

        public boolean resolved() {
            return unitPrice != null || inputUnitPrice != null || outputUnitPrice != null;
        }
    }
}
