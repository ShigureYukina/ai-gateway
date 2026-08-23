package io.gateway.oss.admin.pricing;

import io.gateway.oss.core.contract.PricingConfigView;
import io.gateway.oss.core.contract.PricingPublicationConfigView;
import io.gateway.oss.core.pricing.PricingResolver.ResolvedPricing;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预览服务只负责把解析过程组装成管理员可读的 trace，解析规则仍由引擎统一提供。
 */
@Service
final class PricingPreviewService {

    private final PricingPublicationConfigView configView;
    private final PricingResolutionEngine pricingResolutionEngine;

    PricingPreviewService(PricingPublicationConfigView configView, PricingResolutionEngine pricingResolutionEngine) {
        this.configView = configView;
        this.pricingResolutionEngine = pricingResolutionEngine;
    }

    Map<String, Object> preview(String requestedModel, String upstreamModel, String provider) {
        PreviewResolution previewResolution = previewResolution(requestedModel, upstreamModel, provider);
        ResolvedPricing resolved = previewResolution.resolvedPricing();
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("requestedModel", requestedModel);
        preview.put("upstreamModel", upstreamModel);
        preview.put("provider", provider);
        preview.put("matchedModel", resolved.matchedModel());
        preview.put("source", resolved.source());
        preview.put("matchedBy", resolved.matchedBy());
        preview.put("unitPrice", resolved.unitPrice());
        preview.put("inputUnitPrice", resolved.inputUnitPrice());
        preview.put("outputUnitPrice", resolved.outputUnitPrice());
        preview.put("resolved", resolved.resolved());
        preview.put("trace", previewResolution.trace());
        return preview;
    }

    private PreviewResolution previewResolution(String requestedModel, String upstreamModel, String provider) {
        List<String> candidates = pricingResolutionEngine.candidateModels(requestedModel, upstreamModel);
        PricingConfigView pricing = configView.getPricing();
        Map<String, Object> attempts = new LinkedHashMap<>();

        ResolvedPricing manual = pricingResolutionEngine.resolveManualOverride(pricing, candidates);
        if (manual.resolved()) {
            attempts.put("manualOverride", pricingResolutionEngine.buildAttempt("hit",
                    "命中手工覆盖配置，按既定优先级直接返回。",
                    Map.of("matchedModel", manual.matchedModel())));
            attempts.put("exactMapping", pricingResolutionEngine.buildAttempt("skipped", "已命中更高优先级 manual override，跳过 exact mapping。", null));
            attempts.put("exactMatch", pricingResolutionEngine.buildAttempt("skipped", "已命中更高优先级 manual override，跳过 exact match。", null));
            attempts.put("fuzzyMatch", pricingResolutionEngine.buildAttempt("skipped", "已命中更高优先级 manual override，跳过 fuzzy match。", null));
            attempts.put("defaultApplied", pricingResolutionEngine.buildAttempt("not_applied", "已命中更高优先级价格，未回落到默认价格。", null));
            return new PreviewResolution(manual, buildTrace(candidates, attempts,
                    "命中 manual override：候选模型存在手工定价配置。"));
        }
        attempts.put("manualOverride", pricingResolutionEngine.describeManualOverrideMiss(pricing, candidates));

        PricingResolutionEngine.AttemptEvaluation exactMapping = pricingResolutionEngine.evaluateExactMapping(pricing, candidates);
        attempts.put("exactMapping", exactMapping.attempt());
        if (exactMapping.resolvedPricing().resolved()) {
            attempts.put("exactMatch", pricingResolutionEngine.buildAttempt("skipped", "已命中更高优先级 exact mapping，跳过 exact match。", null));
            attempts.put("fuzzyMatch", pricingResolutionEngine.buildAttempt("skipped", "已命中更高优先级 exact mapping，跳过 fuzzy match。", null));
            attempts.put("defaultApplied", pricingResolutionEngine.buildAttempt("not_applied", "已命中同步价格映射，未回落到默认价格。", null));
            return new PreviewResolution(exactMapping.resolvedPricing(), buildTrace(candidates, attempts,
                    "命中 exact mapping：候选模型映射到同步价格模型。"));
        }

        PricingResolutionEngine.AttemptEvaluation exactMatch = pricingResolutionEngine.evaluateExactMatch(candidates);
        attempts.put("exactMatch", exactMatch.attempt());
        if (exactMatch.resolvedPricing().resolved()) {
            attempts.put("fuzzyMatch", pricingResolutionEngine.buildAttempt("skipped", "已命中更高优先级 exact match，跳过 fuzzy match。", null));
            attempts.put("defaultApplied", pricingResolutionEngine.buildAttempt("not_applied", "已命中同步价格精确匹配，未回落到默认价格。", null));
            return new PreviewResolution(exactMatch.resolvedPricing(), buildTrace(candidates, attempts,
                    "命中 exact match：候选模型直接命中同步价格。"));
        }

        PricingResolutionEngine.AttemptEvaluation fuzzyMatch = pricingResolutionEngine.evaluateFuzzyMatch(candidates);
        attempts.put("fuzzyMatch", fuzzyMatch.attempt());
        if (fuzzyMatch.resolvedPricing().resolved()) {
            attempts.put("defaultApplied", pricingResolutionEngine.buildAttempt("not_applied", "已命中 fuzzy match，未回落到默认价格。", null));
            return new PreviewResolution(fuzzyMatch.resolvedPricing(), buildTrace(candidates, attempts,
                    "命中 fuzzy match：候选模型经名称归一化后唯一匹配同步价格。"));
        }

        ResolvedPricing defaultPricing = pricingResolutionEngine.resolveDefault(pricing);
        attempts.put("defaultApplied", pricingResolutionEngine.describeDefaultApplied(pricing, defaultPricing));
        return new PreviewResolution(defaultPricing, buildTrace(candidates, attempts,
                defaultPricing.resolved()
                        ? "前序解析均未命中，回落到默认价格。"
                        : "前序解析均未命中，且默认价格未配置有效值，返回 0 价格。"));
    }

    private Map<String, Object> buildTrace(List<String> candidates, Map<String, Object> attempts, String reason) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("candidates", candidates);
        trace.put("attempts", attempts);
        trace.put("reason", reason);
        return trace;
    }

    private record PreviewResolution(ResolvedPricing resolvedPricing, Map<String, Object> trace) {
    }
}
