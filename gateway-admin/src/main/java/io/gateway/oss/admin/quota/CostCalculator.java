package io.gateway.oss.admin.quota;

import io.gateway.oss.admin.pricing.BillingPriceResolver;
import io.gateway.oss.admin.sync.PricingSyncService;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class CostCalculator {

    private final BillingPriceResolver billingPriceResolver;

    @Autowired
    public CostCalculator(BillingPriceResolver billingPriceResolver) {
        this.billingPriceResolver = billingPriceResolver;
    }

    public CostCalculator(GatewayConfigView configView,
                          PricingSyncService pricingSyncService) {
        this(new BillingPriceResolver(configView, pricingSyncService));
    }

    public CostCalculator(GatewayConfigView configView) {
        this(new BillingPriceResolver(configView, new PricingSyncService()));
    }

    /**
     * Calculate cost using split prompt/completion token counts.
     * Falls back to single unitPrice when input/output prices are not configured.
     */
    public BigDecimal calculate(ChatCompletionsRequest effectiveRequest,
                                ResolvedRoute route,
                                long promptTokens,
                                long completionTokens) {
        if (promptTokens <= 0 && completionTokens <= 0) {
            return BigDecimal.ZERO;
        }

        var resolved = billingPriceResolver.resolve(effectiveRequest.model(), route.upstreamModel(), route.provider());
        BigDecimal inputPrice = resolveInputPrice(resolved);
        BigDecimal outputPrice = resolveOutputPrice(resolved);

        BigDecimal inputCost = inputPrice
                .multiply(BigDecimal.valueOf(promptTokens))
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal outputCost = outputPrice
                .multiply(BigDecimal.valueOf(completionTokens))
                .setScale(6, RoundingMode.HALF_UP);

        return inputCost.add(outputCost);
    }

    /**
     * Backward-compatible overload: treats all tokens as prompt tokens.
     */
    public BigDecimal calculate(ChatCompletionsRequest effectiveRequest,
                                ResolvedRoute route,
                                long usageTokens) {
        if (usageTokens <= 0) {
            return BigDecimal.ZERO;
        }
        return calculate(effectiveRequest, route, usageTokens, 0L);
    }

    private BigDecimal resolveInputPrice(BillingPriceResolver.ResolvedPricing resolved) {
        if (resolved.inputUnitPrice() != null) {
            return resolved.inputUnitPrice();
        }
        return resolved.unitPrice() == null ? BigDecimal.ZERO : resolved.unitPrice();
    }

    private BigDecimal resolveOutputPrice(BillingPriceResolver.ResolvedPricing resolved) {
        if (resolved.outputUnitPrice() != null) {
            return resolved.outputUnitPrice();
        }
        if (resolved.inputUnitPrice() != null) {
            return resolved.inputUnitPrice();
        }
        return resolved.unitPrice() == null ? BigDecimal.ZERO : resolved.unitPrice();
    }
}
