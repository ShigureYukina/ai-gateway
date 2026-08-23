package io.gateway.oss.admin.quota;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.contract.security.UserAccount;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ClientQuotaService implements io.gateway.oss.core.contract.QuotaService {

    private static final Logger log = LoggerFactory.getLogger(ClientQuotaService.class);

    private final ClientUsageStore usageStore;

    private final Cache<String, Long> dailyUsageCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.SECONDS)
            .maximumSize(2000)
            .build();

    private final Cache<String, Long> monthlyUsageCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .maximumSize(2000)
            .build();

    public ClientQuotaService(ClientUsageStore usageStore) {
        this.usageStore = usageStore;
    }

    /**
     * Clear usage caches for tests.
     */
    public void resetCache() {
        dailyUsageCache.invalidateAll();
        monthlyUsageCache.invalidateAll();
    }

    private String dailyKey(String clientId, Instant now) {
        return clientId + ":daily:" + usagePeriodKey(now);
    }

    private String monthlyKey(String clientId, Instant now) {
        return clientId + ":monthly:" + monthlyPeriodKey(now);
    }

    public void checkDailyQuota(ClientPrincipal principal, Instant now) {
        Long dailyTokens = getEffectiveDailyTokens(principal);
        if (dailyTokens == null) {
            return;
        }
        String key = dailyKey(principal.clientId(), now);
        long used = dailyUsageCache.get(key, k -> usageStore.currentDailyUsage(principal.clientId(), now));
        if (used >= dailyTokens) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "quota_exceeded", "Daily token quota exceeded");
        }
    }

    public void checkMonthlyQuota(ClientPrincipal principal, Instant now) {
        Long monthlyTokens = getEffectiveMonthlyTokens(principal);
        if (monthlyTokens == null) {
            return;
        }
        String key = monthlyKey(principal.clientId(), now);
        long used = monthlyUsageCache.get(key, k -> usageStore.currentMonthlyUsage(principal.clientId(), now));
        if (used >= monthlyTokens) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "monthly_quota_exceeded", "Monthly token quota exceeded");
        }
    }

    public void recordUsage(ClientPrincipal principal,
                            Map<String, Object> upstreamResponse,
                            ChatCompletionsRequest effectiveRequest,
                            Instant now) {
        long tokens = resolveUsageTokensForResponse(upstreamResponse, effectiveRequest);
        ClientUsageStore.UsageCheckResult result = usageStore.checkAndRecordBoth(
                principal.clientId(), tokens, resolveDailyQuota(principal), resolveMonthlyQuota(principal), now);
        if (result.daily() < 0) {
            log.warn("usage_quota_exceeded_during_record clientId={}", principal.clientId());
        }
        if (result.monthly() < 0) {
            log.warn("usage_monthly_quota_exceeded_during_record clientId={}", principal.clientId());
        }
        dailyUsageCache.put(dailyKey(principal.clientId(), now), result.daily() >= 0 ? result.daily() : 0);
        monthlyUsageCache.put(monthlyKey(principal.clientId(), now), result.monthly() >= 0 ? result.monthly() : 0);
    }

    public void recordStreamingUsageOnSuccess(ClientPrincipal principal,
                                               ChatCompletionsRequest effectiveRequest,
                                               Long upstreamTotalTokens,
                                               Instant now) {
        long tokens = resolveUsageTokensForStreaming(upstreamTotalTokens, effectiveRequest);
        ClientUsageStore.UsageCheckResult result = usageStore.checkAndRecordBoth(
                principal.clientId(), tokens, resolveDailyQuota(principal), resolveMonthlyQuota(principal), now);
        if (result.daily() < 0) {
            log.warn("streaming_usage_quota_exceeded_during_record clientId={}", principal.clientId());
        }
        if (result.monthly() < 0) {
            log.warn("streaming_usage_monthly_quota_exceeded_during_record clientId={}", principal.clientId());
        }
        dailyUsageCache.put(dailyKey(principal.clientId(), now), result.daily() >= 0 ? result.daily() : 0);
        monthlyUsageCache.put(monthlyKey(principal.clientId(), now), result.monthly() >= 0 ? result.monthly() : 0);
    }

    public long currentDailyUsage(String clientId, Instant now) {
        return usageStore.currentDailyUsage(clientId, now);
    }

    public long currentMonthlyUsage(String clientId, Instant now) {
        return usageStore.currentMonthlyUsage(clientId, now);
    }

    static String usagePeriodKey(Instant now) {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }

    static String monthlyPeriodKey(Instant now) {
        return java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString();
    }

    public long resolveUsageTokensForResponse(Map<String, Object> upstreamResponse,
                                               ChatCompletionsRequest effectiveRequest) {
        long tokens = extractTotalTokens(upstreamResponse);
        if (tokens > 0) {
            return tokens;
        }
        return fallbackUsage(effectiveRequest);
    }

    public long resolveUsageTokensForStreaming(Long upstreamTotalTokens,
                                                ChatCompletionsRequest effectiveRequest) {
        return upstreamTotalTokens != null && upstreamTotalTokens > 0
                ? upstreamTotalTokens
                : fallbackUsage(effectiveRequest);
    }

    private Long getEffectiveDailyTokens(ClientPrincipal principal) {
        if (principal.config() != null) {
            Long v = principal.config().getLimits().getDailyTokens();
            if (v != null) return v;
        }
        UserAccount.UserLimits ul = principal.userLimits();
        return ul != null ? ul.dailyTokens() : null;
    }

    private Long getEffectiveMonthlyTokens(ClientPrincipal principal) {
        if (principal.config() != null) {
            Long v = principal.config().getLimits().getMonthlyTokens();
            if (v != null) return v;
        }
        UserAccount.UserLimits ul = principal.userLimits();
        return ul != null ? ul.monthlyTokens() : null;
    }

    private long resolveDailyQuota(ClientPrincipal principal) {
        Long v = getEffectiveDailyTokens(principal);
        return v != null ? v : Long.MAX_VALUE;
    }

    private long resolveMonthlyQuota(ClientPrincipal principal) {
        Long v = getEffectiveMonthlyTokens(principal);
        return v != null ? v : Long.MAX_VALUE;
    }

    private long extractTotalTokens(Map<String, Object> upstreamResponse) {
        if (upstreamResponse == null) {
            return 0L;
        }
        Object usage = upstreamResponse.get("usage");
        if (!(usage instanceof Map<?, ?> usageMap)) {
            return 0L;
        }
        long fromTotal = asLong(usageMap.get("total_tokens"));
        if (fromTotal > 0) {
            return fromTotal;
        }
        long prompt = asLong(usageMap.get("prompt_tokens"));
        long completion = asLong(usageMap.get("completion_tokens"));
        long sum = Math.max(0L, prompt) + Math.max(0L, completion);
        return sum > 0 ? sum : 0L;
    }

    private long fallbackUsage(ChatCompletionsRequest effectiveRequest) {
        Integer maxTokens = effectiveRequest.maxTokens();
        if (maxTokens == null || maxTokens <= 0) {
            return 0L;
        }
        return maxTokens.longValue();
    }

    private long asLong(Object raw) {
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
}
