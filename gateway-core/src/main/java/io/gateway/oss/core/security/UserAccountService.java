package io.gateway.oss.core.security;

import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.config.ConfigStore;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.util.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 用户账户管理服务。
 * <p>
 * 用户数据存储到 Redis（通过 ConfigStore，configType="users"），
 * key 为用户名，value 为 {@link UserAccount} JSON。
 * </p>
 * <p>
 * 密码存储使用 SHA-256 哈希（MVP 简化）。
 * </p>
 * <p>
 * 维护内存 apiKey → username 索引，支持无阻塞 API key 认证查询。
 * </p>
 */
public class UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);
    static final String CONFIG_TYPE = "users";

    private final ConfigStore configStore;
    private final ObjectMapper objectMapper;
    private final PasswordService passwordService;
    private final UserApiKeyService userApiKeyService;
    private final UserAccountCacheIndex cacheIndex;
    private final DirtyAccountFlushBuffer dirtyAccountFlushBuffer;
    private final UserAccountCodec accountCodec;

    public UserAccountService(ConfigStore configStore,
                              ObjectMapper objectMapper,
                              PasswordService passwordService,
                              DirtyAccountFlushBuffer dirtyAccountFlushBuffer) {
        this.configStore = Objects.requireNonNull(configStore);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.passwordService = Objects.requireNonNull(passwordService);
        this.dirtyAccountFlushBuffer = Objects.requireNonNull(dirtyAccountFlushBuffer);
        this.accountCodec = new UserAccountCodec(objectMapper, passwordService);
        this.userApiKeyService = new UserApiKeyService(accountCodec::generateApiKey, this::normalizeAllowedModels, passwordService, accountCodec);
        this.cacheIndex = new UserAccountCacheIndex(userApiKeyService, accountCodec);
    }

    /**
     * 启动时从存储加载所有用户，构建 apiKey 索引。
     */
    public void init() {
        try {
            Map<String, String> all = configStore.loadAll(CONFIG_TYPE).block();
            if (all != null) {
                for (String json : all.values()) {
                    UserAccount account = accountCodec.fromJson(json);
                    if (account != null) {
                        cacheIndex.refreshIndexes(null, account);
                    }
                }
            }
            log.info("UserAccountService: loaded {} users into cache", cacheIndex.cachedAccountCount());
        } catch (Exception e) {
            log.warn("UserAccountService: failed to build user cache, falling back to empty", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        init();
    }

    /**
     * 注册新用户。用户名必须唯一。
     *
     * @param username 用户名
     * @param password 明文密码（方法内哈希存储）
     * @param role     角色（admin/user），null 时默认 "user"
     * @return 创建的用户账户
     * @throws GatewayException 409 username_taken（用户名已存在）
     */
    public Mono<UserAccount> register(String username, String password, String role) {
        return register(username, password, role, null, null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<UserAccount> register(String username,
                                      String password,
                                      String role,
                                      UserAccount.UserLimits limits,
                                      Set<String> allowedModels,
                                      String displayName,
                                      String email) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return Mono.error(new GatewayException(HttpStatus.BAD_REQUEST, "missing_fields", "Username and password are required"));
        }
        if (role == null || role.isBlank()) {
            role = "user";
        }
        String finalRole = role;
        UserAccount.UserLimits finalLimits = limits;
        Set<String> finalAllowedModels = allowedModels;
        String finalDisplayName = StringUtils.blankToNull(displayName);
        String finalEmail = normalizeEmail(email);

        return configStore.load(CONFIG_TYPE, username)
                .flatMap(existing -> {
                    if (existing != null) {
                        return Mono.<UserAccount>error(new GatewayException(HttpStatus.CONFLICT, "username_taken", "Username already exists"));
                    }
                    return createUser(username, password, finalRole, finalLimits, finalAllowedModels, finalDisplayName, finalEmail);
                })
                .switchIfEmpty(createUser(username, password, finalRole, finalLimits, finalAllowedModels, finalDisplayName, finalEmail));
    }

    @Transactional(rollbackFor = Exception.class)
    private Mono<UserAccount> createUser(String username,
                                         String password,
                                         String role,
                                         UserAccount.UserLimits limits,
                                         Set<String> allowedModels,
                                         String displayName,
                                         String email) {
        String passwordHash = passwordService.hashPassword(password);
        String apiKey = accountCodec.generateApiKey();
        UserAccount account = userApiKeyService.upgradeLegacyIfNeeded(UserAccount.create(
                username,
                passwordHash,
                role,
                apiKey,
                limits != null ? limits : UserAccount.UserLimits.highDefaults(),
                normalizeAllowedModels(allowedModels),
                displayName,
                email));
        return saveAndRefresh(null, account);
    }

    /**
     * 验证用户凭据。
     *
     * @return 验证通过的用户账户，失败抛 401
     */
    public Mono<UserAccount> authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return Mono.error(new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid username or password"));
        }
        UserAccount cached = cacheIndex.findCachedByUsername(username);
        if (cached != null) {
            if (!passwordService.verifyPassword(password, cached.passwordHash())) {
                return Mono.error(new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid username or password"));
            }
            if (cached.frozen()) {
                return Mono.error(new GatewayException(HttpStatus.FORBIDDEN, "account_frozen", "Account is frozen"));
            }
            return migrateLegacyPasswordIfNeeded(cached, password);
        }
        return configStore.load(CONFIG_TYPE, username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid username or password")))
                .flatMap(json -> {
                    UserAccount account = accountCodec.fromJson(json);
                    if (account == null || !passwordService.verifyPassword(password, account.passwordHash())) {
                        return Mono.error(new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid username or password"));
                    }
                    if (account.frozen()) {
                        return Mono.error(new GatewayException(HttpStatus.FORBIDDEN, "account_frozen", "Account is frozen"));
                    }
                    return migrateLegacyPasswordIfNeeded(account, password)
                            .doOnNext(updated -> refreshIndexes(null, updated));
                });
    }

    /**
     * 按用户名查找用户。
     */
    public Mono<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Mono.empty();
        }
        UserAccount cached = cacheIndex.findCachedByUsername(username);
        if (cached != null) {
            return Mono.just(cached);
        }
        return configStore.load(CONFIG_TYPE, username)
                .mapNotNull(json -> {
                    UserAccount account = accountCodec.fromJson(json);
                    if (account != null) {
                        cacheIndex.refreshIndexes(null, account);
                    }
                    return account;
                });
    }

    public Mono<UserAccount> findByApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.empty();
        }
        String username = cacheIndex.findUsernameByApiKey(apiKey);
        if (username == null) {
            return Mono.empty();
        }
        UserAccount cached = cacheIndex.findCachedByUsername(username);
        if (cached != null) {
            return userApiKeyService.isApiKeyEnabled(cached, apiKey) ? Mono.just(cached) : Mono.empty();
        }
        return findByUsername(username)
                .filter(account -> userApiKeyService.isApiKeyEnabled(account, apiKey));
    }

    /**
     * 通过 API key 同步查找用户（用于认证路径）。
     * 基于内存索引，不触发 .block() 阻塞。
     */
    public UserAccount findByApiKeySync(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String username = cacheIndex.findUsernameByApiKey(apiKey);
        if (username == null) {
            return null;
        }
        UserAccount account = cacheIndex.findCachedByUsername(username);
        if (account == null || !userApiKeyService.isApiKeyEnabled(account, apiKey)) {
            return null;
        }
        return account;
    }

    /**
     * 通过用户名同步查找用户（用于认证路径）。
     * 基于内存缓存，不触发 .block() 阻塞。缓存未命中返回 null。
     */
    public UserAccount findByUsernameSync(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return cacheIndex.findCachedByUsername(username);
    }

    /**
     * 检查是否为已删除用户且 tokenVersion 不匹配（即旧 token 未过期）。
     * 返回 true 表示该用户已被删除且 JWT 的 tokenVersion 与删除时的版本一致 → 应拒绝。
     */
    public boolean isDeletedUserWithOldToken(String username, int tokenVersion) {
        return cacheIndex.isDeletedUserWithOldToken(username, tokenVersion);
    }

    /**
     * 列出所有用户（脱敏）。
     */
    public Mono<List<UserAccount>> listUsers() {
        if (cacheIndex.hasCachedAccounts()) {
            return Mono.just(cacheIndex.cachedAccounts().stream()
                    .map(userApiKeyService::upgradeLegacyIfNeeded)
                    .toList());
        }
        return configStore.loadAll(CONFIG_TYPE)
                .map(map -> map.values().stream()
                        .map(accountCodec::fromJson)
                        .filter(Objects::nonNull)
                        .peek(account -> cacheIndex.refreshIndexes(null, account))
                        .collect(Collectors.toList()));
    }

    /**
     * 判断动态用户存储中是否至少存在一个管理员账户。
     * <p>
     * 仅检查 store-backed 动态账户，不包含 {@code gateway.auth.users.*} 静态 YAML 用户。
     * </p>
     */
    public Mono<Boolean> hasDynamicAdmin() {
        if (cacheIndex.hasCachedAccounts()) {
            return Mono.just(cacheIndex.cachedAccounts().stream()
                    .map(userApiKeyService::upgradeLegacyIfNeeded)
                    .anyMatch(account -> "admin".equalsIgnoreCase(account.role())));
        }
        return configStore.loadAll(CONFIG_TYPE)
                .map(map -> map.values().stream()
                        .map(accountCodec::fromJson)
                        .filter(Objects::nonNull)
                        .peek(account -> cacheIndex.refreshIndexes(null, account))
                        .anyMatch(account -> "admin".equalsIgnoreCase(account.role())));
    }

    public void resetForTests() {
        cacheIndex.resetForTests();
        dirtyAccountFlushBuffer.resetForTests();
    }

    /**
     * 修改用户角色（admin 操作）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Mono<UserAccount> updateRole(String username, String newRole) {
        if (!"admin".equals(newRole) && !"user".equals(newRole)) {
            return Mono.error(new GatewayException(HttpStatus.BAD_REQUEST, "invalid_role", "Role must be 'admin' or 'user'"));
        }
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    UserAccount updated = new UserAccount(existing.username(), existing.passwordHash(), newRole, existing.apiKey(), existing.limits(), existing.allowedModels(), existing.displayName(), existing.email(), existing.apiKeys(), existing.createdAt(), existing.tokenVersion() + 1, existing.frozen(), existing.frozenAt());
                    return saveAndRefresh(existing, updated);
                });
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<UserAccount> updateFrozen(String username, boolean frozen) {
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    Long frozenAt = frozen ? (existing.frozenAt() != null ? existing.frozenAt() : System.currentTimeMillis()) : null;
                    UserAccount updated = new UserAccount(existing.username(), existing.passwordHash(), existing.role(), existing.apiKey(), existing.limits(), existing.allowedModels(), existing.displayName(), existing.email(), existing.apiKeys(), existing.createdAt(), existing.tokenVersion() + 1, frozen, frozenAt);
                    return saveAndRefresh(existing, updated);
                });
    }

    /**
     * 更新用户资料，保持最小规范化，避免把空串写入存储。
     */
    public Mono<UserAccount> updateProfile(String username, String displayName, String email) {
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    UserAccount updated = new UserAccount(
                            existing.username(),
                            existing.passwordHash(),
                            existing.role(),
                            existing.apiKey(),
                            existing.limits(),
                            existing.allowedModels(),
                            StringUtils.blankToNull(displayName),
                            normalizeEmail(email),
                            existing.apiKeys(),
                            existing.createdAt(),
                            existing.tokenVersion() + 1,
                            existing.frozen(),
                            existing.frozenAt()
                    );
                    return saveAndRefresh(existing, updated);
                });
    }

    /**
     * 用户自主修改密码。验证旧密码后更新为新密码。
     *
     * @throws GatewayException 400 old_password_wrong（旧密码错误）
     * @throws GatewayException 404 user_not_found
     */
    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> changePassword(String username, String oldPassword, String newPassword) {
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    if (!passwordService.verifyPassword(oldPassword, existing.passwordHash())) {
                        return Mono.error(new GatewayException(HttpStatus.BAD_REQUEST, "old_password_wrong", "Old password is incorrect"));
                    }
                    String newHash = passwordService.hashPassword(newPassword);
                    UserAccount updated = new UserAccount(existing.username(), newHash, existing.role(), existing.apiKey(), existing.limits(), existing.allowedModels(), existing.displayName(), existing.email(), existing.apiKeys(), existing.createdAt(), existing.tokenVersion() + 1, existing.frozen(), existing.frozenAt());
                    return saveAndRefresh(existing, updated).then();
                });
    }

    /**
     * 管理员重置密码。生成临时密码并返回。
     *
     * @return 临时密码
     */
    public Mono<String> resetPassword(String username) {
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    String tempPassword = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                    String newHash = passwordService.hashPassword(tempPassword);
                    UserAccount updated = new UserAccount(existing.username(), newHash, existing.role(), existing.apiKey(), existing.limits(), existing.allowedModels(), existing.displayName(), existing.email(), existing.apiKeys(), existing.createdAt(), existing.tokenVersion() + 1, existing.frozen(), existing.frozenAt());
                    return saveAndRefresh(existing, updated)
                            .thenReturn(tempPassword);
                });
    }

    /**
     * 删除用户。
     */
    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> deleteUser(String username) {
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    dirtyAccountFlushBuffer.removeDirty(username);
                    UserAccount bumped = new UserAccount(
                            existing.username(),
                            existing.passwordHash(),
                            existing.role(),
                            existing.apiKey(),
                            existing.limits(),
                            existing.allowedModels(),
                            existing.displayName(),
                            existing.email(),
                            existing.apiKeys(),
                            existing.createdAt(),
                            existing.tokenVersion() + 1,
                            existing.frozen(),
                            existing.frozenAt()
                    );
                    return configStore.save(CONFIG_TYPE, username, accountCodec.toJson(bumped))
                            .then(Mono.fromRunnable(() -> {
                                cacheIndex.recordDeletedVersion(existing.username(), existing.tokenVersion() + 1);
                                evictAccount(existing);
                            }))
                            .then(configStore.delete(CONFIG_TYPE, username));
                });
    }

    /**
     * 为用户生成并替换单个 API key（MVP：每个用户仅保留 1 个当前 key）。
     */
    public Mono<UserAccount.ApiKeyRecord> createApiKey(String username, String name, Set<String> allowedModels) {
        return configStore.load(CONFIG_TYPE, username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(json -> {
                    UserAccount existing = accountCodec.fromJson(json);
                    if (existing == null) {
                        return Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found"));
                    }
                    if (existing.frozen()) {
                        return Mono.error(new GatewayException(HttpStatus.FORBIDDEN, "account_frozen", "Account is frozen"));
                    }
                    UserAccount.ApiKeyRecord record = userApiKeyService.createApiKeyRecord(name, allowedModels);
                    UserAccount updated = userApiKeyService.addApiKey(existing, record);
                    return configStore.save(CONFIG_TYPE, username, accountCodec.toJsonForStorage(updated))
                            .then(Mono.fromRunnable(() -> refreshIndexes(existing, updated)))
                            .thenReturn(record);
                });
    }

    /**
     * 列出用户当前可见 key（MVP 单 key，返回 0 或 1 条）。
     */
    public Mono<List<ApiKeyView>> listApiKeys(String username) {
        return findByUsername(username)
                .map(account -> userApiKeyService.safeApiKeys(account).stream()
                        .map(key -> new ApiKeyView(
                                key.keyId(),
                                key.name(),
                                UserAccountCodec.maskApiKey(key.apiKey()),
                                key.enabled(),
                                key.createdAt(),
                                key.lastUsedAt(),
                                key.requestCount(),
                                key.allowedModels()))
                        .toList())
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")));
    }

    /**
     * 删除用户自己的 key。
     * MVP keyId 语义：仅支持 "primary"。
     */
    public Mono<Void> deleteApiKey(String username, String keyId) {
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    UserAccount updated = userApiKeyService.deleteApiKey(existing, keyId);
                    return configStore.save(CONFIG_TYPE, username, accountCodec.toJsonForStorage(updated))
                            .then(Mono.fromRunnable(() -> refreshIndexes(existing, updated)));
                });
    }

    public Mono<Void> updateApiKey(String username, String keyId, Boolean enabled, String name, Set<String> allowedModels) {
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    UserAccount updated = userApiKeyService.updateApiKey(existing, keyId, enabled, name, allowedModels);
                    return configStore.save(CONFIG_TYPE, username, accountCodec.toJsonForStorage(updated))
                            .then(Mono.fromRunnable(() -> refreshIndexes(existing, updated)));
                });
    }

    public Mono<Void> updateApiKeyEnabled(String username, String keyId, boolean enabled) {
        return updateApiKey(username, keyId, enabled, null, null);
    }

    public Mono<UserAccount.ApiKeyRecord> rotateApiKey(String username, String keyId) {
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    UserApiKeyService.RotateApiKeyResult result = userApiKeyService.rotateApiKey(existing, keyId);
                    UserAccount updated = result.updatedAccount();
                    return configStore.save(CONFIG_TYPE, username, accountCodec.toJsonForStorage(updated))
                            .then(Mono.fromRunnable(() -> refreshIndexes(existing, updated)))
                            .thenReturn(result.newRecord());
                });
    }

    public Mono<UserAccount> updateAllowedModels(String username, Set<String> allowedModels) {
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    UserAccount updated = new UserAccount(
                            existing.username(),
                            existing.passwordHash(),
                            existing.role(),
                            existing.apiKey(),
                            existing.limits(),
                            normalizeAllowedModels(allowedModels),
                            existing.displayName(),
                            existing.email(),
                            existing.apiKeys(),
                            existing.createdAt(),
                            existing.tokenVersion(),
                            existing.frozen(),
                            existing.frozenAt()
                    );
                    return saveAndRefresh(existing, updated);
                });
    }

    public Mono<UserAccount> updateLimits(String username, UserAccount.UserLimits newLimits) {
        return findByUsername(username)
                .switchIfEmpty(Mono.error(new GatewayException(HttpStatus.NOT_FOUND, "user_not_found", "User not found")))
                .flatMap(existing -> {
                    UserAccount updated = new UserAccount(
                            existing.username(),
                            existing.passwordHash(),
                            existing.role(),
                            existing.apiKey(),
                            newLimits,
                            existing.allowedModels(),
                            existing.displayName(),
                            existing.email(),
                            existing.apiKeys(),
                            existing.createdAt(),
                            existing.tokenVersion(),
                            existing.frozen(),
                            existing.frozenAt()
                    );
                    return saveAndRefresh(existing, updated);
                });
    }

    public void markApiKeyUsed(String apiKey) {
        String username = cacheIndex.findUsernameByApiKey(apiKey);
        if (username == null) return;
        UserAccount existing = cacheIndex.findCachedByUsername(username);
        if (existing == null) return;
        if (existing.frozen() || cacheIndex.isDeleted(username)) {
            return;
        }
        long now = System.currentTimeMillis();
        UserAccount updated = userApiKeyService.markApiKeyUsed(existing, apiKey, now);
        cacheIndex.replaceCachedAccount(updated);
        dirtyAccountFlushBuffer.markDirty(updated);
    }

    /**
     * 确保用户在动态 users store 中存在；不存在则按给定 role 创建影子账户。
     */
    public Mono<UserAccount> ensureUserAccount(String username, String role) {
        return findByUsername(username)
                .switchIfEmpty(createShadowUser(username, role));
    }

    private Mono<UserAccount> createShadowUser(String username, String role) {
        String finalRole = (role == null || role.isBlank()) ? "user" : role;
        UserAccount account = new UserAccount(
                username,
                passwordService.hashPassword(UUID.randomUUID().toString()),
                finalRole,
                accountCodec.generateApiKey(),
                UserAccount.UserLimits.highDefaults(),
                null,
                null,
                null,
                null,
                System.currentTimeMillis(),
                0,
                false,
                null
        );
        account = userApiKeyService.upgradeLegacyIfNeeded(account);
        UserAccount finalAccount = account;
        return saveAndRefresh(null, finalAccount);
    }

    private Mono<UserAccount> migrateLegacyPasswordIfNeeded(UserAccount account, String rawPassword) {
        if (account == null || passwordService.isBcryptHash(account.passwordHash())) {
            return Mono.just(account);
        }
        String upgradedHash = passwordService.hashPassword(rawPassword);
        UserAccount upgraded = new UserAccount(
                account.username(),
                upgradedHash,
                account.role(),
                account.apiKey(),
                account.limits(),
                account.allowedModels(),
                account.displayName(),
                account.email(),
                account.apiKeys(),
                account.createdAt(),
                account.tokenVersion(),
                account.frozen(),
                account.frozenAt()
        );
        return saveAndRefresh(account, upgraded);
    }

    public record ApiKeyView(String keyId, String name, String apiKeyMasked, boolean enabled, long createdAt, Long lastUsedAt, long requestCount, Set<String> allowedModels) {
    }

    private String normalizeEmail(String email) {
        String normalized = StringUtils.blankToNull(email);
        return normalized == null ? null : normalized.toLowerCase(java.util.Locale.ROOT);
    }

    public UserAccount.ApiKeyRecord findApiKeyRecord(UserAccount account, String apiKey) {
        return userApiKeyService.findApiKeyRecord(account, apiKey);
    }

    private Set<String> normalizeAllowedModels(Set<String> allowedModels) {
        if (allowedModels == null || allowedModels.isEmpty()) {
            return Set.of();
        }
        return allowedModels.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Transactional(rollbackFor = Exception.class)
    private Mono<UserAccount> saveAndRefresh(UserAccount existing, UserAccount updated) {
        UserAccount responseAccount = userApiKeyService.upgradeLegacyIfNeeded(updated);
        UserAccount storedAccount = accountCodec.hashApiKeysIfNeeded(responseAccount);
        return configStore.save(CONFIG_TYPE, storedAccount.username(), accountCodec.toJson(storedAccount))
                .then(Mono.fromRunnable(() -> refreshIndexes(existing, responseAccount)))
                .thenReturn(responseAccount);
    }

    private void evictAccount(UserAccount existing) {
        if (existing == null) {
            return;
        }
        cacheIndex.evictAccount(existing);
        dirtyAccountFlushBuffer.removeDirty(existing.username());
    }

    private void refreshIndexes(UserAccount existing, UserAccount updatedRaw) {
        cacheIndex.refreshIndexes(existing, updatedRaw);
    }
}
