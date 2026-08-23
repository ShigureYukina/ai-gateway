package io.gateway.oss.core.web.support;

import io.gateway.oss.core.util.BatchFlusher;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 提取辅助方法，供编排层复用。
 */
public final class TokenExtractionHelper {

    private TokenExtractionHelper() {
    }

    /**
     * 从上游响应提取 prompt_tokens。
     */
    public static long extractPromptTokens(Map<String, Object> resp) {
        if (resp == null) return 0L;
        Object usage = resp.get("usage");
        if (!(usage instanceof Map<?, ?> usageMap)) return 0L;
        return asLong(usageMap.get("prompt_tokens"));
    }

    /**
     * 从上游响应提取 completion_tokens。
     * 当上游仅返回 total_tokens 时，回退为 total - prompt。
     */
    public static long extractCompletionTokens(Map<String, Object> resp, long totalTokens) {
        if (resp == null) return 0L;
        Object usage = resp.get("usage");
        if (!(usage instanceof Map<?, ?> usageMap)) return 0L;
        long completion = asLong(usageMap.get("completion_tokens"));
        if (completion > 0) return completion;
        long prompt = asLong(usageMap.get("prompt_tokens"));
        if (totalTokens > 0 && prompt > 0) {
            return Math.max(0L, totalTokens - prompt);
        }
        return 0L;
    }

    public static long extractTotalTokens(Map<String, Object> resp) {
        if (resp == null) return 0L;
        Object usage = resp.get("usage");
        if (!(usage instanceof Map<?, ?> usageMap)) return 0L;
        return asLong(usageMap.get("total_tokens"));
    }

    public static long asLong(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * 捕获流式 usage 中的 token 拆分信息。
     */
    public static void captureStreamingUsage(String chunk,
                                             AtomicLong streamPromptTokens,
                                             AtomicLong streamCompletionTokens,
                                             AtomicLong streamTotalTokens,
                                             String modelId,
                                             String requestId,
                                             String clientId,
                                             BatchFlusher batchFlusher) {
        if (chunk == null || chunk.isBlank()) {
            return;
        }
        long total = extractLongByIndex(chunk, "\"total_tokens\":");
        long prompt = extractLongByIndex(chunk, "\"prompt_tokens\":");
        long completion = extractLongByIndex(chunk, "\"completion_tokens\":");

        if (total > 0) {
            streamTotalTokens.set(total);
        }
        if (prompt > 0) {
            streamPromptTokens.set(prompt);
        }
        if (completion > 0) {
            streamCompletionTokens.set(completion);
        }
        if (total <= 0 && prompt > 0 && completion > 0) {
            streamTotalTokens.set(prompt + completion);
        }
    }

    public static long extractLongByIndex(String source, String key) {
        int idx = source.indexOf(key);
        if (idx < 0) return 0L;
        int start = idx + key.length();
        while (start < source.length() && (source.charAt(start) == ' ' || source.charAt(start) == ':')) {
            start++;
        }
        if (start >= source.length()) return 0L;
        while (start < source.length() && !Character.isDigit(source.charAt(start)) && source.charAt(start) != '-') {
            start++;
        }
        int end = start;
        while (end < source.length() && Character.isDigit(source.charAt(end))) {
            end++;
        }
        if (end == start) return 0L;
        try {
            return Long.parseLong(source.substring(start, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
