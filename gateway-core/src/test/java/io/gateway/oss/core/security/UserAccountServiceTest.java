package io.gateway.oss.core.security;

import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.config.InMemoryConfigStore;
import io.gateway.oss.core.error.GatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountServiceTest {

    private InMemoryConfigStore configStore;
    private PasswordService passwordService;
    private UserAccountService service;
    private DirtyAccountFlushBuffer dirtyAccountFlushBuffer;

    @BeforeEach
    void setUp() {
        configStore = new InMemoryConfigStore();
        ObjectMapper objectMapper = new ObjectMapper();
        passwordService = new PasswordService();
        dirtyAccountFlushBuffer = new DirtyAccountFlushBuffer(configStore, objectMapper, passwordService);
        service = new UserAccountService(configStore, objectMapper, passwordService, dirtyAccountFlushBuffer);
    }

    @AfterEach
    void tearDown() {
        dirtyAccountFlushBuffer.shutdown();
    }

    // ─── register ───

    @Test
    void shouldRegisterNewUser() {
        StepVerifier.create(service.register("alice", "password123", null))
                .assertNext(account -> {
                    assertThat(account.username()).isEqualTo("alice");
                    assertThat(account.role()).isEqualTo("user");
                    assertThat(account.apiKey()).startsWith("gw-");
                    assertThat(account.passwordHash()).isNotEqualTo("password123"); // hashed
                    assertThat(account.passwordHash()).startsWith("$2");
                    assertThat(account.createdAt()).isGreaterThan(0);
                })
                .verifyComplete();
    }

    @Test
    void shouldRegisterUserWithProfileAndCustomPolicy() {
        UserAccount.UserLimits limits = new UserAccount.UserLimits(
                10_000L, 300_000L, 10_000L, 512,
                new java.math.BigDecimal("5.0"),
                new java.math.BigDecimal("100.0"));

        StepVerifier.create(service.register(
                        "profile-user",
                        "password123",
                        "user",
                        limits,
                        Set.of("gpt-4o-mini"),
                        "  Profile User  ",
                        "  PROFILE@EXAMPLE.COM  "))
                .assertNext(account -> {
                    assertThat(account.limits()).isEqualTo(limits);
                    assertThat(account.allowedModels()).containsExactly("gpt-4o-mini");
                    assertThat(account.displayName()).isEqualTo("Profile User");
                    assertThat(account.email()).isEqualTo("profile@example.com");
                })
                .verifyComplete();
    }

    @Test
    void shouldRegisterAdminUser() {
        StepVerifier.create(service.register("admin", "secret", "admin"))
                .assertNext(account -> {
                    assertThat(account.username()).isEqualTo("admin");
                    assertThat(account.role()).isEqualTo("admin");
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectDuplicateUsername() {
        StepVerifier.create(service.register("alice", "pass1", null))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(service.register("alice", "pass2", null))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    GatewayException ge = (GatewayException) e;
                    assertThat(ge.getCode()).isEqualTo("username_taken");
                    assertThat(ge.getStatus().value()).isEqualTo(409);
                })
                .verify();
    }

    @Test
    void shouldRejectMissingUsername() {
        StepVerifier.create(service.register("", "pass", null))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) e).getCode()).isEqualTo("missing_fields");
                })
                .verify();
    }

    @Test
    void shouldRejectMissingPassword() {
        StepVerifier.create(service.register("user1", "", null))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) e).getCode()).isEqualTo("missing_fields");
                })
                .verify();
    }

    // ─── authenticate ───

    @Test
    void shouldAuthenticateWithValidCredentials() {
        service.register("alice", "password123", null).block();

        StepVerifier.create(service.authenticate("alice", "password123"))
                .assertNext(account -> assertThat(account.username()).isEqualTo("alice"))
                .verifyComplete();
    }

    @Test
    void shouldRejectInvalidPassword() {
        service.register("alice", "password123", null).block();

        StepVerifier.create(service.authenticate("alice", "wrongpass"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) e).getCode()).isEqualTo("invalid_credentials");
                    assertThat(((GatewayException) e).getStatus().value()).isEqualTo(401);
                })
                .verify();
    }

    @Test
    void shouldRejectUnknownUsername() {
        StepVerifier.create(service.authenticate("nonexistent", "pass"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) e).getCode()).isEqualTo("invalid_credentials");
                })
                .verify();
    }

    // ─── findByUsername ───

    @Test
    void shouldFindExistingUser() {
        service.register("alice", "password123", "admin").block();

        StepVerifier.create(service.findByUsername("alice"))
                .assertNext(account -> {
                    assertThat(account.username()).isEqualTo("alice");
                    assertThat(account.role()).isEqualTo("admin");
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyForUnknownUser() {
        StepVerifier.create(service.findByUsername("ghost"))
                .verifyComplete();
    }

    // ─── listUsers ───

    @Test
    void shouldListAllUsersWithSanitization() {
        service.register("alice", "pass1", null).block();
        service.register("bob", "pass2", "admin").block();

        StepVerifier.create(service.listUsers())
                .assertNext(users -> {
                    assertThat(users).hasSize(2);
                    users.forEach(u -> {
                        assertThat(u.passwordHash()).isNotBlank();
                        assertThat(u.passwordHash()).doesNotContain("pass");
                        assertThat(u.apiKey()).startsWith("gw-");
                    });
                })
                .verifyComplete();
    }

    @Test
    void shouldDetectDynamicAdmin() {
        service.register("alice", "pass1", null).block();
        service.register("bob", "pass2", "admin").block();

        StepVerifier.create(service.hasDynamicAdmin())
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void shouldReturnFalseWhenNoDynamicAdminExists() {
        service.register("alice", "pass1", null).block();

        StepVerifier.create(service.hasDynamicAdmin())
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldListApiKeysWithOriginalViewShape() {
        UserAccount created = service.register("api-user", "pass1", null).block();

        StepVerifier.create(service.listApiKeys("api-user"))
                .assertNext(keys -> {
                    assertThat(keys).hasSize(1);
                    UserAccountService.ApiKeyView key = keys.get(0);
                    assertThat(key.keyId()).isEqualTo("primary");
                    assertThat(key.name()).isEqualTo("default");
                    assertThat(key.apiKeyMasked()).isEqualTo(UserAccountCodec.maskApiKey(created.apiKey()));
                    assertThat(key.enabled()).isTrue();
                    assertThat(key.allowedModels()).isEmpty();
                })
                .verifyComplete();
    }

    // ─── updateRole ───

    @Test
    void shouldUpdateUserRole() {
        service.register("alice", "pass", null).block();

        StepVerifier.create(service.updateRole("alice", "admin"))
                .assertNext(account -> assertThat(account.role()).isEqualTo("admin"))
                .verifyComplete();

        StepVerifier.create(service.findByUsername("alice"))
                .assertNext(account -> assertThat(account.role()).isEqualTo("admin"))
                .verifyComplete();
    }

    @Test
    void shouldRejectInvalidRole() {
        service.register("alice", "pass", null).block();

        StepVerifier.create(service.updateRole("alice", "superadmin"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) e).getCode()).isEqualTo("invalid_role");
                    assertThat(((GatewayException) e).getStatus().value()).isEqualTo(400);
                })
                .verify();
    }

    @Test
    void shouldReturn404WhenUpdatingNonexistentUser() {
        StepVerifier.create(service.updateRole("ghost", "admin"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) e).getCode()).isEqualTo("user_not_found");
                    assertThat(((GatewayException) e).getStatus().value()).isEqualTo(404);
                })
                .verify();
    }

    // ─── deleteUser ───

    @Test
    void shouldDeleteUser() {
        UserAccount created = service.register("alice", "pass", null).block();

        StepVerifier.create(service.deleteUser("alice"))
                .verifyComplete();

        StepVerifier.create(service.findByUsername("alice"))
                .verifyComplete();
        assertThat(service.findByUsernameSync("alice")).isNull();
        assertThat(service.findByApiKeySync(created.apiKeys().get(0).apiKey())).isNull();
    }

    @Test
    void shouldReturn404WhenDeletingNonexistentUser() {
        StepVerifier.create(service.deleteUser("ghost"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) e).getCode()).isEqualTo("user_not_found");
                    assertThat(((GatewayException) e).getStatus().value()).isEqualTo(404);
                })
                .verify();
    }

    // ─── password hashing ───

    @Test
    void shouldHashPasswordWithBcryptAndVerify() {
        String hash = passwordService.hashPassword("password");
        assertThat(hash).startsWith("$2");
        assertThat(hash).isNotEqualTo("password");
        assertThat(new BCryptPasswordEncoder().matches("password", hash)).isTrue();
    }

    @Test
    void shouldProduceDifferentHashesForSamePassword() {
        String hash1 = passwordService.hashPassword("pass1");
        String hash2 = passwordService.hashPassword("pass1");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void shouldMigrateLegacySha256HashToBcryptOnAuthenticate() {
        String legacyHash = sha256Hex("legacy-pass");
        UserAccount legacyAccount = UserAccount.create("legacy-user", legacyHash, "user", "gw-legacy");
        configStore.save(UserAccountService.CONFIG_TYPE, "legacy-user", toJson(legacyAccount)).block();

        StepVerifier.create(service.authenticate("legacy-user", "legacy-pass"))
                .assertNext(account -> {
                    assertThat(account.username()).isEqualTo("legacy-user");
                    assertThat(account.passwordHash()).startsWith("$2");
                    assertThat(account.passwordHash()).isNotEqualTo(legacyHash);
                    assertThat(new BCryptPasswordEncoder().matches("legacy-pass", account.passwordHash())).isTrue();
                })
                .verifyComplete();

        UserAccount persisted = service.findByUsername("legacy-user").block();
        assertThat(persisted).isNotNull();
        assertThat(persisted.passwordHash()).startsWith("$2");
    }

    // ─── API key uniqueness ───

    @Test
    void shouldGenerateUniqueApiKeys() {
        UserAccount a1 = service.register("user1", "pass1", null).block();
        UserAccount a2 = service.register("user2", "pass2", null).block();
        assertThat(a1.apiKey()).isNotEqualTo(a2.apiKey());
    }

    @Test
    void shouldStoreApiKeysAsDeterministicPlusBcryptHashes() {
        UserAccount created = service.register("secure-key-user", "pass1", null).block();

        String storedJson = configStore.load(UserAccountService.CONFIG_TYPE, "secure-key-user").block();
        assertThat(storedJson).isNotNull();
        assertThat(storedJson).doesNotContain(created.apiKey());
        assertThat(storedJson).contains(UserAccountCodec.API_KEY_HASH_PREFIX + KeyHashUtil.hash(created.apiKey()) + ":$2");
    }

    @Test
    void shouldFreezeAndUnfreezeUser() {
        service.register("f1", "pass", null).block();

        StepVerifier.create(service.updateFrozen("f1", true))
                .assertNext(account -> assertThat(account.frozen()).isTrue())
                .verifyComplete();

        StepVerifier.create(service.authenticate("f1", "pass"))
                .expectErrorSatisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("account_frozen"))
                .verify();

        StepVerifier.create(service.updateFrozen("f1", false))
                .assertNext(account -> assertThat(account.frozen()).isFalse())
                .verifyComplete();
    }

    @Test
    void shouldUpdateProfileWithNormalizedEmail() {
        service.register("profile-edit", "pass123", null).block();

        StepVerifier.create(service.updateProfile("profile-edit", "  Alice  ", "  ALICE@EXAMPLE.COM  "))
                .assertNext(account -> {
                    assertThat(account.displayName()).isEqualTo("Alice");
                    assertThat(account.email()).isEqualTo("alice@example.com");
                })
                .verifyComplete();

        StepVerifier.create(service.updateProfile("profile-edit", "   ", "   "))
                .assertNext(account -> {
                    assertThat(account.displayName()).isNull();
                    assertThat(account.email()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void shouldCreateApiKeyWithAllowedModels() {
        service.register("models-user", "pass", null).block();

        StepVerifier.create(service.createApiKey("models-user", "scoped", java.util.Set.of("gpt-4o-mini")))
                .assertNext(key -> assertThat(key.allowedModels()).containsExactly("gpt-4o-mini"))
                .verifyComplete();
    }

    // ─── updateLimits ───

    @Test
    void shouldUpdateUserLimits() {
        service.register("limits-user", "pass", null).block();

        UserAccount.UserLimits newLimits = new UserAccount.UserLimits(
                500_000L, 15_000_000L, 50_000L, 4096,
                new java.math.BigDecimal("50.00"),
                new java.math.BigDecimal("1500.00"));

        StepVerifier.create(service.updateLimits("limits-user", newLimits))
                .assertNext(account -> {
                    assertThat(account.limits()).isNotNull();
                    assertThat(account.limits().dailyTokens()).isEqualTo(500_000L);
                    assertThat(account.limits().monthlyTokens()).isEqualTo(15_000_000L);
                    assertThat(account.limits().tokensPerMinute()).isEqualTo(50_000L);
                    assertThat(account.limits().maxTokens()).isEqualTo(4096);
                    assertThat(account.limits().dailyCost()).isEqualByComparingTo("50.00");
                    assertThat(account.limits().monthlyCost()).isEqualByComparingTo("1500.00");
                })
                .verifyComplete();

        StepVerifier.create(service.findByUsername("limits-user"))
                .assertNext(account -> assertThat(account.limits().dailyTokens()).isEqualTo(500_000L))
                .verifyComplete();

        assertThat(service.findByUsernameSync("limits-user")).isNotNull();
        assertThat(service.findByUsernameSync("limits-user").limits().dailyTokens()).isEqualTo(500_000L);
    }

    @Test
    void shouldReturn404WhenUpdatingLimitsForMissingUser() {
        UserAccount.UserLimits limits = UserAccount.UserLimits.highDefaults();

        StepVerifier.create(service.updateLimits("ghost", limits))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) e).getCode()).isEqualTo("user_not_found");
                })
                .verify();
    }

    @Test
    void shouldCreateUserWithDefaultHighLimitsWhenRegistering() {
        StepVerifier.create(service.register("new-limits-user", "pass", "user"))
                .assertNext(account -> {
                    assertThat(account.limits()).isNotNull();
                    assertThat(account.limits().dailyTokens()).isEqualTo(1_000_000_000L);
                    assertThat(account.limits().monthlyTokens()).isEqualTo(30_000_000_000L);
                    assertThat(account.limits().dailyCost()).isEqualByComparingTo("10000");
                })
                .verifyComplete();
    }

    // ─── rotateApiKey ───

    @Test
    void shouldRotateApiKeyAndReturnNewKey() {
        UserAccount created = service.register("rot-user", "pass123", "user").block();
        assertThat(created).isNotNull();
        List<UserAccount.ApiKeyRecord> keys = created.apiKeys();
        assertThat(keys).hasSize(1);
        String oldKeyId = keys.get(0).keyId();
        String oldApiKey = keys.get(0).apiKey();

        UserAccount.ApiKeyRecord rotated = service.rotateApiKey("rot-user", oldKeyId).block();
        assertThat(rotated).isNotNull();
        assertThat(rotated.keyId()).isNotEqualTo(oldKeyId);
        assertThat(rotated.apiKey()).isNotEqualTo(oldApiKey);
        assertThat(rotated.apiKey()).startsWith("gw-");
        assertThat(rotated.enabled()).isTrue();
        assertThat(rotated.name()).isEqualTo("default");

        UserAccount reloaded = service.findByUsername("rot-user").block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.apiKeys()).hasSize(1);
        assertThat(reloaded.apiKeys().get(0).keyId()).isEqualTo(rotated.keyId());
    }

    @Test
    void shouldThrowNotFoundWhenRotatingKeyForNonexistentUser() {
        StepVerifier.create(service.rotateApiKey("no-such-user", "k1"))
                .expectErrorMatches(e -> e instanceof GatewayException && "user_not_found".equals(((GatewayException) e).getCode()))
                .verify();
    }

    @Test
    void shouldThrowNotFoundWhenRotatingNonexistentKey() {
        service.register("rot-missing", "pass123", "user").block();

        StepVerifier.create(service.rotateApiKey("rot-missing", "no-such-key"))
                .expectErrorMatches(e -> e instanceof GatewayException && "key_not_found".equals(((GatewayException) e).getCode()))
                .verify();
    }

    // ─── updateAllowedModels ───

    @Test
    void shouldUpdateAllowedModels() {
        service.register("am-user2", "pass123", "user").block();

        UserAccount updated = service.updateAllowedModels("am-user2", Set.of("gpt-4o-mini", "gpt-4o")).block();
        assertThat(updated).isNotNull();
        assertThat(updated.allowedModels()).containsExactlyInAnyOrder("gpt-4o-mini", "gpt-4o");

        UserAccount reloaded = service.findByUsername("am-user2").block();
        assertThat(reloaded.allowedModels()).containsExactlyInAnyOrder("gpt-4o-mini", "gpt-4o");
        assertThat(service.findByUsernameSync("am-user2").allowedModels()).containsExactlyInAnyOrder("gpt-4o-mini", "gpt-4o");
    }

    @Test
    void shouldUpdateApiKeyUsageInMemoryImmediately() {
        UserAccount created = service.register("usage-user", "pass123", "user").block();
        assertThat(created).isNotNull();

        String apiKey = created.apiKeys().get(0).apiKey();
        service.markApiKeyUsed(apiKey);

        UserAccount cached = service.findByApiKeySync(apiKey);
        assertThat(cached).isNotNull();
        UserAccount.ApiKeyRecord keyRecord = service.findApiKeyRecord(cached, apiKey);
        assertThat(keyRecord).isNotNull();
        assertThat(keyRecord.requestCount()).isEqualTo(1L);
        assertThat(keyRecord.lastUsedAt()).isNotNull();
    }

    @Test
    void shouldMatchHashedApiKeyFromIndex() {
        String apiKey = "gw-hashed-key";
        UserAccount account = new UserAccount("hashed-user", sha256Hex("pass"),
                "user", apiKey, null, null, null, null,
                List.of(new UserAccount.ApiKeyRecord("k1", "name1", KeyHashUtil.hash(apiKey), Set.of(), true,
                        System.currentTimeMillis(), null, 0L, null)),
                System.currentTimeMillis(), 0, false, null);
        configStore.save("users", "hashed-user", toJson(account)).block();
        service.resetForTests();
        service.init();

        UserAccount cached = service.findByApiKeySync(apiKey);
        assertThat(cached).isNotNull();
        assertThat(cached.username()).isEqualTo("hashed-user");
        assertThat(service.findApiKeyRecord(cached, apiKey)).isNotNull();
    }

    @Test
    void shouldClearAllowedModelsWithEmptySet() {
        service.register("am-clear2", "pass123", "user").block();
        service.updateAllowedModels("am-clear2", Set.of("gpt-4o-mini")).block();

        UserAccount cleared = service.updateAllowedModels("am-clear2", Set.of()).block();
        assertThat(cleared).isNotNull();
        assertThat(cleared.allowedModels()).isEmpty();
    }

    @Test
    void shouldThrowNotFoundWhenUpdatingAllowedModelsForMissingUser() {
        StepVerifier.create(service.updateAllowedModels("no-such-user", Set.of("gpt-4o-mini")))
                .expectErrorMatches(e -> e instanceof GatewayException && "user_not_found".equals(((GatewayException) e).getCode()))
                .verify();
    }

    // ─── ApiKeyRecord expiresAt ───

    @Test
    void shouldStoreExpiresAtInApiKeyRecord() {
        UserAccount account = new UserAccount("exp-user", sha256Hex("pass"),
                "user", "gw-test", null, null, null, null,
                List.of(new UserAccount.ApiKeyRecord("k1", "name1", "gw-exp-key", Set.of(), true,
                        System.currentTimeMillis(), null, 0L, 2000000000L)),
                System.currentTimeMillis(), 0, false, null);
        configStore.save("users", "exp-user", toJson(account)).block();

        UserAccount loaded = service.findByUsername("exp-user").block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.apiKeys()).hasSize(1);
        assertThat(loaded.apiKeys().get(0).expiresAt()).isEqualTo(2000000000L);
    }

    @Test
    void shouldAllowNullExpiresAt() {
        UserAccount account = new UserAccount("noexp-user", sha256Hex("pass"),
                "user", "gw-test2", null, null, null, null,
                List.of(new UserAccount.ApiKeyRecord("k1", "name1", "gw-noexp-key", Set.of(), true,
                        System.currentTimeMillis(), null, 0L, null)),
                System.currentTimeMillis(), 0, false, null);
        configStore.save("users", "noexp-user", toJson(account)).block();

        UserAccount loaded = service.findByUsername("noexp-user").block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.apiKeys().get(0).expiresAt()).isNull();
    }

    private String toJson(UserAccount account) {
        try {
            return new ObjectMapper().writeValueAsString(account);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(String password) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
