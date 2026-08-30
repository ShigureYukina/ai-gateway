package io.gateway.oss.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gateway.oss.core.config.InMemoryConfigStore;
import io.gateway.oss.core.contract.security.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DirtyAccountFlushBufferTest {

    private InMemoryConfigStore configStore;
    private UserAccountCodec accountCodec;
    private DirtyAccountFlushBuffer buffer;

    @BeforeEach
    void setUp() {
        configStore = new InMemoryConfigStore();
        ObjectMapper objectMapper = new ObjectMapper();
        PasswordService passwordService = new PasswordService();
        accountCodec = new UserAccountCodec(objectMapper, passwordService);
        buffer = new DirtyAccountFlushBuffer(configStore, objectMapper, passwordService);
    }

    private UserAccount accountWithKey(String username, Long lastUsedAt) {
        UserAccount.ApiKeyRecord key = new UserAccount.ApiKeyRecord(
                "key-1", "default", "gw-" + username, null, true, 1L, lastUsedAt, lastUsedAt == null ? 0L : 1L, null);
        return new UserAccount(username, "hash", "user", key.apiKey(),
                UserAccount.UserLimits.highDefaults(), null, null, null,
                List.of(key), 1L, 0, false, null);
    }

    @Test
    void flushSkipsSaveWhenUserMarkedDeleted() {
        // 审查 D5：删除与 in-flight flush 并发时，已标记删除的用户不得被写回存储
        UserAccount stored = accountWithKey("alice", null);
        configStore.save(UserAccountService.CONFIG_TYPE, "alice", accountCodec.toJsonForStorage(stored)).block();

        buffer.markDirty(accountWithKey("alice", 123L));
        buffer.markDeleted("alice");
        buffer.flushDirtyAccounts(true);

        UserAccount current = accountCodec.fromJson(configStore.load(UserAccountService.CONFIG_TYPE, "alice").block());
        // 存储中的账户未被 flush 覆盖（lastUsedAt 合并没有发生）
        assertThat(current.apiKeys().get(0).lastUsedAt()).isNull();
    }

    @Test
    void flushMergesUsageMetadataForLiveUsers() {
        // 对照组：未标记删除时，flush 正常合并使用元数据。
        // 注意 toJsonForStorage 会哈希 apiKey，dirty 快照必须基于读回的存储账户构造，
        // 否则 mergeUsageMetadata 按 apiKey 匹配不上。
        configStore.save(UserAccountService.CONFIG_TYPE, "bob",
                accountCodec.toJsonForStorage(accountWithKey("bob", null))).block();
        UserAccount stored = accountCodec.fromJson(configStore.load(UserAccountService.CONFIG_TYPE, "bob").block());

        // 主 apiKey 字段与列表记录各自独立 BCrypt 哈希（随机盐），必须取列表记录的哈希值
        UserAccount.ApiKeyRecord dirtyKey = new UserAccount.ApiKeyRecord(
                "key-1", "default", stored.apiKeys().get(0).apiKey(), null, true, 1L, 123L, 1L, null);
        UserAccount dirty = new UserAccount("bob", stored.passwordHash(), stored.role(), stored.apiKey(),
                stored.limits(), stored.allowedModels(), stored.displayName(), stored.email(),
                List.of(dirtyKey), stored.createdAt(), stored.tokenVersion(), stored.frozen(), stored.frozenAt());
        buffer.markDirty(dirty);
        buffer.flushDirtyAccounts(true);

        UserAccount current = accountCodec.fromJson(configStore.load(UserAccountService.CONFIG_TYPE, "bob").block());
        assertThat(current.apiKeys().get(0).lastUsedAt()).isEqualTo(123L);
    }
}
