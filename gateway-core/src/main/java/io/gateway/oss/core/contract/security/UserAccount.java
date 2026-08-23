package io.gateway.oss.core.contract.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 用户账户数据模型。
 *
 * @param username    用户名（唯一标识）
 * @param passwordHash 密码哈希（SHA-256）
 * @param role        角色：admin 或 user
 * @param apiKey      关联的 API key
 * @param createdAt   创建时间（epoch millis）
 * @param tokenVersion token 版本号（用于 JWT 失效）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserAccount(
        String username,
        String passwordHash,
        String role,
        String apiKey,
        @Nullable UserLimits limits,
        @Nullable Set<String> allowedModels,
        @Nullable String displayName,
        @Nullable String email,
        List<ApiKeyRecord> apiKeys,
        long createdAt,
        int tokenVersion,
        boolean frozen,
        Long frozenAt
) {
/**
  * 工厂方法：创建新用户。
  */
public static UserAccount create(String username, String passwordHash, String role, String apiKey) {
        return create(username, passwordHash, role, apiKey, UserLimits.highDefaults(), null, null, null);
    }

    /**
     * 工厂方法：按给定模板创建新用户。
     */
    public static UserAccount create(String username,
                                     String passwordHash,
                                     String role,
                                     String apiKey,
                                     @Nullable UserLimits limits,
                                     @Nullable Set<String> allowedModels,
                                     @Nullable String displayName,
                                     @Nullable String email) {
        long now = System.currentTimeMillis();
        return new UserAccount(username, passwordHash, role, apiKey,
                limits,
                allowedModels,
                displayName,
                email,
                List.of(new ApiKeyRecord("primary", "default", apiKey, Set.of(), true, now, null, 0L, null)),
                now, 0, false, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserLimits(
            Long dailyTokens,
            Long monthlyTokens,
            Long tokensPerMinute,
            Integer maxTokens,
            BigDecimal dailyCost,
            BigDecimal monthlyCost
    ) {
        /**
         * 足够高的默认限额，确保正常用户不会受限，同时防止异常情况下的资源滥用。
         * 每日 1B tokens、每月 30B tokens、每分钟 10M tokens、单次最大 200K tokens、
         * 每日 $10,000 预算、每月 $300,000 预算。
         */
        public static UserLimits highDefaults() {
            return new UserLimits(
                    1_000_000_000L,
                    30_000_000_000L,
                    10_000_000L,
                    200_000,
                    new BigDecimal("10000"),
                    new BigDecimal("300000")
            );
        }

        public static UserLimits of(Long dailyTokens, Long monthlyTokens, Long tokensPerMinute,
                                     Integer maxTokens, BigDecimal dailyCost, BigDecimal monthlyCost) {
            return new UserLimits(dailyTokens, monthlyTokens, tokensPerMinute, maxTokens, dailyCost, monthlyCost);
        }
    }

public record ApiKeyRecord(
            String keyId,
            String name,
            String apiKey,
            Set<String> allowedModels,
            boolean enabled,
            long createdAt,
            Long lastUsedAt,
            long requestCount,
            Long expiresAt
    ) {
    }
}
