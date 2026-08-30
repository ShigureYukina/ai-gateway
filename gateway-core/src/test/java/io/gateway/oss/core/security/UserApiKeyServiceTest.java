package io.gateway.oss.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审查 S1：自助 API key 的 allowedModels 必须收敛到账户模型上限的子集。
 * key 级白名单非空时运行时 authorizeModel 会跳过 client/账户级检查，
 * 不收敛交集即可借 key 白名单绕过账户级限制。
 */
class UserApiKeyServiceTest {

    private UserApiKeyService newService() {
        PasswordService passwordService = new PasswordService();
        UserAccountCodec accountCodec = new UserAccountCodec(new ObjectMapper(), passwordService);
        return new UserApiKeyService(
                () -> "gw-key-test",
                requested -> {
                    if (requested == null || requested.isEmpty()) {
                        return Set.of();
                    }
                    return requested.stream()
                            .filter(java.util.Objects::nonNull)
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toUnmodifiableSet());
                },
                passwordService,
                accountCodec);
    }

    @Test
    void restrict_noAccountCeiling_returnsNormalizedRequest() {
        Set<String> result = newService().restrictToAccountModels(Set.of(" gpt-4o ", "claude-3"), null);
        assertEquals(Set.of("gpt-4o", "claude-3"), result);
    }

    @Test
    void restrict_emptyAccountCeiling_returnsNormalizedRequest() {
        // 未设置账户级约束 = 不收敛
        Set<String> result = newService().restrictToAccountModels(Set.of("gpt-4o"), Set.of());
        assertEquals(Set.of("gpt-4o"), result);
    }

    @Test
    void restrict_requestWithinCeiling_keptAsIs() {
        Set<String> result = newService().restrictToAccountModels(
                Set.of("gpt-4o", "gpt-4o-mini"), Set.of("gpt-4o", "gpt-4o-mini", "claude-3"));
        assertEquals(Set.of("gpt-4o", "gpt-4o-mini"), result);
    }

    @Test
    void restrict_requestBeyondCeiling_intersectedToCeiling() {
        Set<String> result = newService().restrictToAccountModels(
                Set.of("gpt-4o", "o3-mini"), Set.of("gpt-4o"));
        assertEquals(Set.of("gpt-4o"), result);
    }

    @Test
    void restrict_disjointCeiling_returnsEmpty() {
        Set<String> result = newService().restrictToAccountModels(
                Set.of("gpt-4o"), Set.of("claude-3"));
        assertTrue(result.isEmpty());
    }
}
