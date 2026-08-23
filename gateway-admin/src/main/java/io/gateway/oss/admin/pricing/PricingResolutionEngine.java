package io.gateway.oss.admin.pricing;

import io.gateway.oss.admin.sync.PricingSyncService;
import io.gateway.oss.core.config.ModelPricing;
import io.gateway.oss.core.contract.PricingConfigView;
import io.gateway.oss.core.contract.PricingPublicationConfigView;
import io.gateway.oss.core.pricing.PricingResolver.ResolvedPricing;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 纯定价解析引擎：只负责按既有优先级解析命中结果，不组装预览 trace。
 */
@Service
final class PricingResolutionEngine {

    private static final String SOURCE_MANUAL_OVERRIDE = "manual_override";
    private static final String SOURCE_SYNCED_PRICING = "synced_pricing";
    private static final String SOURCE_DEFAULT = "configured_default";

    private final PricingPublicationConfigView configView;
    private final PricingSyncService pricingSyncService;

    PricingResolutionEngine(PricingPublicationConfigView configView, PricingSyncService pricingSyncService) {
        this.configView = configView;
        this.pricingSyncService = pricingSyncService;
    }

    ResolvedPricing resolve(String requestedModel, String upstreamModel, String provider) {
        List<String> candidates = candidateModels(requestedModel, upstreamModel);
        PricingConfigView pricing = configView.getPricing();

        ResolvedPricing manual = resolveManualOverride(pricing, candidates);
        if (manual.resolved()) {
            return manual;
        }

        AttemptEvaluation exactMapping = evaluateExactMapping(pricing, candidates);
        if (exactMapping.resolvedPricing().resolved()) {
            return exactMapping.resolvedPricing();
        }

        AttemptEvaluation exactMatch = evaluateExactMatch(candidates);
        if (exactMatch.resolvedPricing().resolved()) {
            return exactMatch.resolvedPricing();
        }

        AttemptEvaluation fuzzyMatch = evaluateFuzzyMatch(candidates);
        if (fuzzyMatch.resolvedPricing().resolved()) {
            return fuzzyMatch.resolvedPricing();
        }

        return resolveDefault(pricing);
    }

    ResolvedPricing resolveManualOverride(PricingConfigView pricing, List<String> candidates) {
        Map<String, ModelPricing> configuredModels = pricing.getModels();
        if (configuredModels == null || configuredModels.isEmpty()) {
            return ResolvedPricing.unresolved();
        }
        for (String candidate : candidates) {
            ModelPricing configured = configuredModels.get(candidate);
            if (configured == null) {
                continue;
            }
            if (configured.getUnitPrice() == null
                    && configured.getInputUnitPrice() == null
                    && configured.getOutputUnitPrice() == null) {
                continue;
            }
            return fromModelPricing(configured, SOURCE_MANUAL_OVERRIDE, candidate, "manual_override");
        }
        return ResolvedPricing.unresolved();
    }

    AttemptEvaluation evaluateExactMapping(PricingConfigView pricing, List<String> candidates) {
        Map<String, String> exactMatches = pricing.getExactMatches();
        if (exactMatches == null || exactMatches.isEmpty()) {
            return new AttemptEvaluation(ResolvedPricing.unresolved(),
                    buildAttempt("miss", "未配置 exactMatches，无法执行精确映射。", null));
        }
        List<Map<String, Object>> checked = new ArrayList<>();
        for (String candidate : candidates) {
            String rawMappedModel = exactMatches.get(candidate);
            String mappedModel = normalizeKey(rawMappedModel);
            if (mappedModel == null) {
                checked.add(buildCandidateCheck(candidate, "miss", "该候选模型未配置 exact mapping。", null));
                continue;
            }
            ResolvedPricing mapped = fromSyncedModel(mappedModel, mappedModel, "exact_mapping");
            if (mapped.resolved()) {
                return new AttemptEvaluation(mapped,
                        buildAttempt("hit", "候选模型命中 exact mapping，并解析到同步价格。",
                                Map.of("candidate", candidate, "mappedModel", mappedModel, "checked", checked)));
            }
            checked.add(buildCandidateCheck(candidate, "miss", "已命中 exact mapping，但映射目标没有可用同步价格。",
                    Map.of("mappedModel", mappedModel)));
        }
        return new AttemptEvaluation(ResolvedPricing.unresolved(),
                buildAttempt("miss", "存在 exactMatches 配置，但候选模型未解析到可用同步价格。",
                        Map.of("checked", checked)));
    }

    AttemptEvaluation evaluateExactMatch(List<String> candidates) {
        Map<String, PricingSyncService.ModelPricingEntry> synced = pricingSyncService.getSnapshot().modelPricings();
        if (synced == null || synced.isEmpty()) {
            return new AttemptEvaluation(ResolvedPricing.unresolved(),
                    buildAttempt("miss", "同步价格快照为空，无法执行 exact match。", null));
        }
        List<Map<String, Object>> checked = new ArrayList<>();
        for (String candidate : candidates) {
            ResolvedPricing exact = fromSyncedModel(candidate, candidate, "exact_match");
            if (exact.resolved()) {
                return new AttemptEvaluation(exact,
                        buildAttempt("hit", "候选模型直接命中同步价格。",
                                Map.of("candidate", candidate, "matchedModel", candidate, "checked", checked)));
            }
            checked.add(buildCandidateCheck(candidate, "miss", "同步价格中不存在该候选模型的精确条目。", null));
        }
        return new AttemptEvaluation(ResolvedPricing.unresolved(),
                buildAttempt("miss", "候选模型均未命中同步价格精确匹配。", Map.of("checked", checked)));
    }

    AttemptEvaluation evaluateFuzzyMatch(List<String> candidates) {
        Map<String, PricingSyncService.ModelPricingEntry> synced = pricingSyncService.getSnapshot().modelPricings();
        if (synced == null || synced.isEmpty()) {
            return new AttemptEvaluation(ResolvedPricing.unresolved(),
                    buildAttempt("miss", "同步价格快照为空，无法执行 fuzzy match。", null));
        }

        List<Map<String, Object>> checked = new ArrayList<>();
        for (String candidate : candidates) {
            String normalizedCandidate = normalizeName(candidate);
            if (normalizedCandidate == null) {
                checked.add(buildCandidateCheck(candidate, "miss", "候选模型为空白，无法进行名称归一化匹配。", null));
                continue;
            }
            List<String> matches = new ArrayList<>();
            for (String model : synced.keySet()) {
                if (normalizedCandidate.equals(normalizeName(model))) {
                    matches.add(model);
                }
            }
            if (matches.size() == 1) {
                String matchedModel = matches.get(0);
                return new AttemptEvaluation(fromSyncedModel(matchedModel, matchedModel, "fuzzy_name_fallback"),
                        buildAttempt("hit", "候选模型归一化后唯一命中同步价格模型。",
                                Map.of("candidate", candidate,
                                        "normalizedCandidate", normalizedCandidate,
                                        "matchedModel", matchedModel,
                                        "checked", checked)));
            }
            if (matches.isEmpty()) {
                checked.add(buildCandidateCheck(candidate, "miss", "归一化后未找到同步价格候选。",
                        Map.of("normalizedCandidate", normalizedCandidate)));
                continue;
            }
            checked.add(buildCandidateCheck(candidate, "miss", "归一化后匹配到多个同步价格模型，按现有语义不做歧义猜测。",
                    Map.of("normalizedCandidate", normalizedCandidate, "matchedModels", matches)));
        }
        return new AttemptEvaluation(ResolvedPricing.unresolved(),
                buildAttempt("miss", "候选模型未形成唯一 fuzzy match。", Map.of("checked", checked)));
    }

    ResolvedPricing resolveDefault(PricingConfigView pricing) {
        if (pricing.getDefault() == null) {
            return new ResolvedPricing(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    SOURCE_DEFAULT, null, "default_price");
        }
        ResolvedPricing resolved = fromModelPricing(pricing.getDefault(), SOURCE_DEFAULT, null, "default_price");
        if (resolved.resolved()) {
            return resolved;
        }
        return new ResolvedPricing(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                SOURCE_DEFAULT, null, "default_price");
    }

    Map<String, Object> describeManualOverrideMiss(PricingConfigView pricing, List<String> candidates) {
        Map<String, ModelPricing> configuredModels = pricing.getModels();
        if (configuredModels == null || configuredModels.isEmpty()) {
            return buildAttempt("miss", "未配置手工模型价格，无法命中 manual override。", null);
        }
        List<Map<String, Object>> checked = new ArrayList<>();
        for (String candidate : candidates) {
            ModelPricing configured = configuredModels.get(candidate);
            if (configured == null) {
                checked.add(buildCandidateCheck(candidate, "miss", "该候选模型不存在手工价格配置。", null));
                continue;
            }
            if (configured.getUnitPrice() == null
                    && configured.getInputUnitPrice() == null
                    && configured.getOutputUnitPrice() == null) {
                checked.add(buildCandidateCheck(candidate, "miss", "已配置模型条目，但价格字段均为空。", null));
            }
        }
        return buildAttempt("miss", "候选模型未命中可用的手工价格配置。", Map.of("checked", checked));
    }

    Map<String, Object> describeDefaultApplied(PricingConfigView pricing, ResolvedPricing resolvedPricing) {
        if (pricing.getDefault() == null) {
            return buildAttempt("applied", "未配置默认价格，按现有语义返回 0 价格。", null);
        }
        if (resolvedPricing.unitPrice() != null || resolvedPricing.inputUnitPrice() != null || resolvedPricing.outputUnitPrice() != null) {
            return buildAttempt("applied", "前序解析未命中，已应用配置中的默认价格。", null);
        }
        return buildAttempt("applied", "默认价格对象存在，但价格字段均为空，按现有语义返回 0 价格。", null);
    }

    List<String> candidateModels(String requestedModel, String upstreamModel) {
        Set<String> models = new LinkedHashSet<>();
        String requested = normalizeKey(requestedModel);
        String upstream = normalizeKey(upstreamModel);
        if (requested != null) {
            models.add(requested);
        }
        if (upstream != null) {
            models.add(upstream);
        }
        return List.copyOf(models);
    }

    Map<String, Object> buildAttempt(String status, String reason, Map<String, Object> extras) {
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("status", status);
        attempt.put("reason", reason);
        if (extras != null && !extras.isEmpty()) {
            attempt.putAll(extras);
        }
        return attempt;
    }

    private ResolvedPricing fromSyncedModel(String lookupModel, String matchedModel, String matchedBy) {
        PricingSyncService.ModelPricingEntry entry = pricingSyncService.getSnapshot().modelPricings().get(lookupModel);
        if (entry == null) {
            return ResolvedPricing.unresolved();
        }
        BigDecimal input = firstNonNull(entry.inputUnitPrice(), entry.unitPrice());
        BigDecimal output = firstNonNull(entry.outputUnitPrice(), input, entry.unitPrice());
        BigDecimal unit = firstNonNull(entry.unitPrice(), input, output);
        if (unit == null && input == null && output == null) {
            return ResolvedPricing.unresolved();
        }
        return new ResolvedPricing(unit, input, output, SOURCE_SYNCED_PRICING, matchedModel, matchedBy);
    }

    private ResolvedPricing fromModelPricing(ModelPricing pricing, String source, String matchedModel, String matchedBy) {
        BigDecimal input = firstNonNull(pricing.getInputUnitPrice(), pricing.getUnitPrice());
        BigDecimal output = firstNonNull(pricing.getOutputUnitPrice(), input, pricing.getUnitPrice());
        BigDecimal unit = firstNonNull(pricing.getUnitPrice(), input, output);
        if (unit == null && input == null && output == null) {
            return ResolvedPricing.unresolved();
        }
        return new ResolvedPricing(unit, input, output, source, matchedModel, matchedBy);
    }

    private Map<String, Object> buildCandidateCheck(String candidate, String status, String reason, Map<String, Object> extras) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("candidate", candidate);
        check.put("status", status);
        check.put("reason", reason);
        if (extras != null && !extras.isEmpty()) {
            check.putAll(extras);
        }
        return check;
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeName(String value) {
        String normalized = normalizeKey(value);
        if (normalized == null) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-./:]+", "");
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

    record AttemptEvaluation(ResolvedPricing resolvedPricing, Map<String, Object> attempt) {
    }
}
