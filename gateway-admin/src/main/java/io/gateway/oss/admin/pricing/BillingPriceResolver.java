package io.gateway.oss.admin.pricing;

import io.gateway.oss.admin.sync.PricingSyncService;
import io.gateway.oss.core.contract.PricingPublicationConfigView;
import io.gateway.oss.core.pricing.PricingResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class BillingPriceResolver implements PricingResolver {

    /**
     * 对外继续保留 BillingPriceResolver 作为门面，避免影响现有调用方；
     * 纯解析与预览 trace 组装分别下沉到独立协作类。
     */
    private final PricingResolutionEngine pricingResolutionEngine;
    private final PricingPreviewService pricingPreviewService;

    @Autowired
    public BillingPriceResolver(PricingResolutionEngine pricingResolutionEngine,
                                PricingPreviewService pricingPreviewService) {
        this.pricingResolutionEngine = pricingResolutionEngine;
        this.pricingPreviewService = pricingPreviewService;
    }

    /**
     * 保留原有测试/兼容构造方式，避免扩大本轮改动面。
     */
    public BillingPriceResolver(PricingPublicationConfigView configView,
                                PricingSyncService pricingSyncService) {
        PricingResolutionEngine pricingResolutionEngine = new PricingResolutionEngine(configView, pricingSyncService);
        this.pricingResolutionEngine = pricingResolutionEngine;
        this.pricingPreviewService = new PricingPreviewService(configView, pricingResolutionEngine);
    }

    @Override
    public ResolvedPricing resolve(String requestedModel, String upstreamModel, String provider) {
        return pricingResolutionEngine.resolve(requestedModel, upstreamModel, provider);
    }

    public Map<String, Object> preview(String requestedModel, String upstreamModel, String provider) {
        return pricingPreviewService.preview(requestedModel, upstreamModel, provider);
    }
}
