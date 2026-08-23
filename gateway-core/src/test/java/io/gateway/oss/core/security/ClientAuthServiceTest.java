package io.gateway.oss.core.security;

import io.gateway.oss.core.config.AuthConfig;
import io.gateway.oss.core.config.AuthRegistrationConfig;
import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.JwtConfig;
import io.gateway.oss.core.config.UserConfig;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.error.GatewayException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientAuthServiceTest {

    private GatewayProperties properties;
    private JwtService jwtService;
    private UserAccountService userAccountService;
    private ClientAuthService authService;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        jwtService = mock(JwtService.class);
        userAccountService = mock(UserAccountService.class);

        AuthConfig authConfig = new AuthConfig();
        authConfig.setEnabled(true);
        JwtConfig jwtConfig = new JwtConfig();
        jwtConfig.setSecret("super-secret-key-that-is-at-least-32-chars");
        authConfig.setJwt(jwtConfig);

        ClientConfig staticClient = new ClientConfig();
        staticClient.setEnabled(true);
        staticClient.setAllowedModels(Set.of("gpt-4o-mini"));
        staticClient.setAllowedScenes(Set.of("default-chat"));

        properties.setAuth(authConfig);
        properties.setClients(Map.of("demo-client-key", staticClient));

        authService = new ClientAuthService(properties, jwtService, userAccountService);
    }

    // ─── Missing/invalid header ───

    @Test
    void shouldRejectNullAuthorizationHeader() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authenticate(null));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("unauthorized", ex.getCode());
    }

    @Test
    void shouldRejectNonBearerAuthorizationHeader() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authenticate("Basic abc123"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void shouldRejectEmptyBearerToken() {
        when(jwtService.parseToken("")).thenThrow(new JwtValidationException("invalid_token", "empty"));
        when(userAccountService.findByApiKeySync("")).thenReturn(null);

        // "Bearer " → token = "" after trim → JWT fails → static key fallback → not found → 401
        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authenticate("Bearer "));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    // ─── JWT priority over static key ───

    @Test
    void shouldUseJwtWhenTokenIsValidAccessToken() {
        Claims claims = Jwts.claims()
                .subject("demo-client-key")
                .add("scope", List.of("chat"))
                .add("typ", "access")
                .add("role", "admin")
                .add("tokenVersion", 0)
                .build();

        when(jwtService.parseToken("valid-jwt")).thenReturn(claims);
        when(jwtService.isRefreshToken(claims)).thenReturn(false);
        when(jwtService.extractClientId(claims)).thenReturn("demo-client-key");
        when(jwtService.extractRole(claims)).thenReturn("admin");
        when(jwtService.extractTokenVersion(claims)).thenReturn(0);
        UserAccount account = new UserAccount(
                "demo-client-key", "hash", "admin", "api-key",
                UserAccount.UserLimits.highDefaults(), null, null, null,
                List.of(), 0, 0, false, null
        );
        when(userAccountService.findByUsernameSync("demo-client-key")).thenReturn(account);

        ClientPrincipal principal = authService.authenticate("Bearer valid-jwt");

        assertNotNull(principal);
        assertEquals("demo-client-key", principal.clientId());
        assertEquals("admin", principal.role());
        verify(jwtService).parseToken("valid-jwt");
        verify(userAccountService, never()).findByApiKeySync(anyString());
    }

    @Test
    void shouldRejectJwtWhenAccountIsMissing() {
        Claims claims = Jwts.claims()
                .subject("demo-client-key")
                .add("typ", "access")
                .add("role", "admin")
                .add("tokenVersion", 0)
                .build();

        when(jwtService.parseToken("missing-account-jwt")).thenReturn(claims);
        when(jwtService.isRefreshToken(claims)).thenReturn(false);
        when(jwtService.extractClientId(claims)).thenReturn("demo-client-key");
        when(jwtService.extractRole(claims)).thenReturn("admin");
        when(jwtService.extractTokenVersion(claims)).thenReturn(0);
        when(userAccountService.findByUsernameSync("demo-client-key")).thenReturn(null);
        when(userAccountService.isDeletedUserWithOldToken("demo-client-key", 0)).thenReturn(false);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authenticate("Bearer missing-account-jwt"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(userAccountService, never()).findByApiKeySync(anyString());
    }

    @Test
    void shouldAllowJwtForStaticYamlUserWhenDynamicAccountIsMissing() {
        UserConfig adminUser = new UserConfig();
        adminUser.setClientId("demo-client-key");
        adminUser.setRole("admin");
        properties.getAuth().getUsers().put("admin", adminUser);

        Claims claims = Jwts.claims()
                .subject("admin")
                .add("typ", "access")
                .add("role", "admin")
                .add("tokenVersion", 0)
                .build();

        when(jwtService.parseToken("static-admin-jwt")).thenReturn(claims);
        when(jwtService.isRefreshToken(claims)).thenReturn(false);
        when(jwtService.extractClientId(claims)).thenReturn("admin");
        when(jwtService.extractRole(claims)).thenReturn("admin");
        when(jwtService.extractTokenVersion(claims)).thenReturn(0);
        when(userAccountService.findByUsernameSync("admin")).thenReturn(null);

        ClientPrincipal principal = authService.authenticate("Bearer static-admin-jwt");

        assertNotNull(principal);
        assertEquals("demo-client-key", principal.clientId());
        assertEquals("admin", principal.username());
        assertEquals("admin", principal.role());
        verify(userAccountService, never()).isDeletedUserWithOldToken(anyString(), anyInt());
        verify(userAccountService, never()).findByApiKeySync(anyString());
    }

    @Test
    void shouldFallbackToStaticKeyWhenJwtParseFails() {
        when(jwtService.parseToken("invalid-jwt")).thenThrow(new JwtValidationException("invalid_token", "bad"));
        when(userAccountService.findByApiKeySync("invalid-jwt")).thenReturn(null);

        // "invalid-jwt" is not a valid static key either → 401
        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authenticate("Bearer invalid-jwt"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("unauthorized", ex.getCode());
    }

    @Test
    void shouldRejectRefreshTokenWhenUsedForAuthentication() {
        Claims refreshClaims = Jwts.claims()
                .subject("user1")
                .add("typ", "refresh")
                .build();

        when(jwtService.parseToken("refresh-token-as-bearer")).thenReturn(refreshClaims);
        when(jwtService.isRefreshToken(refreshClaims)).thenReturn(true);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authenticate("Bearer refresh-token-as-bearer"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("Refresh token cannot be used for authentication", ex.getMessage());
        verify(userAccountService, never()).findByApiKeySync(anyString());
    }

    @Test
    void shouldResolveStaticKeyWhenJwtFailsAndKeyMatches() {
        when(jwtService.parseToken("demo-client-key")).thenThrow(new JwtValidationException("invalid_token", "bad"));
        when(userAccountService.findByApiKeySync("demo-client-key")).thenReturn(null);

        // "demo-client-key" is a valid static key in properties
        ClientPrincipal principal = authService.authenticate("Bearer demo-client-key");

        assertNotNull(principal);
        assertEquals("demo-client-key", principal.clientId());
    }

    // ─── Frozen account ───

    @Test
    void shouldRejectFrozenAccountApiKey() {
        UserAccount frozenAccount = new UserAccount(
                "frozen-user", "hash", "user", "frozen-api-key",
                UserAccount.UserLimits.highDefaults(), null, null, null,
                List.of(new UserAccount.ApiKeyRecord("primary", "default", "frozen-api-key", Set.of(), true, 0, null, 0L, null)),
                0, 0, true, System.currentTimeMillis()
        );

        when(jwtService.parseToken("frozen-api-key")).thenThrow(new JwtValidationException("invalid", "bad"));
        when(userAccountService.findByApiKeySync("frozen-api-key")).thenReturn(frozenAccount);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authenticate("Bearer frozen-api-key"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("account_frozen", ex.getCode());
    }

    // ─── Static key not found ───

    @Test
    void shouldRejectUnknownApiKey() {
        when(jwtService.parseToken("unknown-key")).thenThrow(new JwtValidationException("invalid", "bad"));
        when(userAccountService.findByApiKeySync("unknown-key")).thenReturn(null);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authenticate("Bearer unknown-key"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("unauthorized", ex.getCode());
    }

    @Test
    void shouldRejectDisabledStaticClient() {
        ClientConfig disabledClient = new ClientConfig();
        disabledClient.setEnabled(false);
        properties.setClients(Map.of("disabled-key", disabledClient));

        when(jwtService.parseToken("disabled-key")).thenThrow(new JwtValidationException("invalid", "bad"));
        when(userAccountService.findByApiKeySync("disabled-key")).thenReturn(null);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authenticate("Bearer disabled-key"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    // ─── Auth disabled mode ───

    @Test
    void shouldSkipJwtWhenAuthDisabled() {
        properties.getAuth().setEnabled(false);

        when(userAccountService.findByApiKeySync("demo-client-key")).thenReturn(null);

        ClientPrincipal principal = authService.authenticate("Bearer demo-client-key");

        assertNotNull(principal);
        assertEquals("demo-client-key", principal.clientId());
        verify(jwtService, never()).parseToken(anyString());
    }

    // ─── Admin check ───

    @Test
    void shouldThrowForbiddenForNonAdminPrincipal() {
        ClientPrincipal userPrincipal = new ClientPrincipal("user1", null, "user");

        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.requireAdmin(userPrincipal));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void shouldPassForAdminPrincipal() {
        ClientPrincipal adminPrincipal = new ClientPrincipal("admin1", null, "admin");
        authService.requireAdmin(adminPrincipal); // should not throw
    }

    // ─── Model authorization ───

    @Test
    void shouldRejectDisallowedModel() {
        ClientConfig limitedClient = new ClientConfig();
        limitedClient.setEnabled(true);
        limitedClient.setAllowedModels(Set.of("gpt-4"));

        ClientPrincipal principal = new ClientPrincipal("user1", limitedClient, "user");

        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authorizeModel(principal, "gpt-4o-mini"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("forbidden_model", ex.getCode());
    }

    @Test
    void shouldAllowModelWhenInAllowedList() {
        ClientConfig client = new ClientConfig();
        client.setEnabled(true);
        client.setAllowedModels(Set.of("gpt-4o-mini"));

        ClientPrincipal principal = new ClientPrincipal("user1", client, "user");
        authService.authorizeModel(principal, "gpt-4o-mini"); // should not throw
    }

    @Test
    void shouldAllowAnyModelForDynamicUser() {
        // Dynamic user has null config (open mode)
        ClientPrincipal principal = new ClientPrincipal("dynamic-user", null, "user");
        authService.authorizeModel(principal, "any-model"); // should not throw
    }

    // ─── Restricted registration mode ───

    @Test
    void restrictedMode_shouldBuildPrincipalWithBootstrapConfig() throws Exception {
        properties.getAuth().setRegistrationMode("restricted");
        AuthRegistrationConfig reg = new AuthRegistrationConfig();
        reg.setAllowedModels(Set.of("gpt-4o-mini"));
        properties.getAuth().setRegistration(reg);

        Claims claims = Jwts.claims()
                .subject("new-user")
                .add("scope", List.of("chat"))
                .add("typ", "access")
                .add("role", "user")
                .add("tokenVersion", 0)
                .build();

        UserAccount account = new UserAccount(
                "new-user", "hash", "user", "new-api-key",
                UserAccount.UserLimits.highDefaults(), Set.of("gpt-4o-mini"), null, null,
                List.of(), 0, 0, false, null);

        when(jwtService.parseToken("valid-jwt-restricted")).thenReturn(claims);
        when(jwtService.isRefreshToken(claims)).thenReturn(false);
        when(jwtService.extractClientId(claims)).thenReturn("new-user");
        when(jwtService.extractRole(claims)).thenReturn("user");
        when(jwtService.extractTokenVersion(claims)).thenReturn(0);
        when(userAccountService.findByUsernameSync("new-user")).thenReturn(account);

        ClientPrincipal principal = authService.authenticate("Bearer valid-jwt-restricted");

        assertNotNull(principal);
        assertNotNull(principal.config(), "Restricted mode must provide bootstrap ClientConfig");
        assertTrue(principal.config().getAllowedModels().contains("gpt-4o-mini"),
                "Bootstrap config should contain allowed model");
        assertNotNull(principal.config().getLimits());
    }

    @Test
    void restrictedMode_shouldRejectUnauthorizedModel() {
        properties.getAuth().setRegistrationMode("restricted");
        AuthRegistrationConfig reg = new AuthRegistrationConfig();
        reg.setAllowedModels(Set.of("gpt-4o-mini"));
        properties.getAuth().setRegistration(reg);

        ClientConfig bootstrapConfig = reg.toClientConfig();
        ClientPrincipal principal = new ClientPrincipal("new-user", bootstrapConfig, "user");

        // Allowed model passes
        authService.authorizeModel(principal, "gpt-4o-mini");

        // Unauthorized model is rejected
        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authorizeModel(principal, "claude-3-opus"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("forbidden_model", ex.getCode());
    }

    @Test
    void openMode_shouldAllowAnyModelForDynamicUser() {
        properties.getAuth().setRegistrationMode("open");

        Claims claims = Jwts.claims()
                .subject("dynamic-user")
                .add("scope", List.of("chat"))
                .add("typ", "access")
                .add("role", "user")
                .add("tokenVersion", 0)
                .build();

        UserAccount account = new UserAccount(
                "dynamic-user", "hash", "user", "dynamic-api-key",
                UserAccount.UserLimits.highDefaults(), null, null, null,
                List.of(), 0, 0, false, null);

        when(jwtService.parseToken("dynamic-jwt")).thenReturn(claims);
        when(jwtService.isRefreshToken(claims)).thenReturn(false);
        when(jwtService.extractClientId(claims)).thenReturn("dynamic-user");
        when(jwtService.extractRole(claims)).thenReturn("user");
        when(jwtService.extractTokenVersion(claims)).thenReturn(0);
        when(userAccountService.findByUsernameSync("dynamic-user")).thenReturn(account);

        ClientPrincipal principal = authService.authenticate("Bearer dynamic-jwt");

        assertNotNull(principal);
        // Open mode: null config is expected for dynamic users without static client
        authService.authorizeModel(principal, "any-model"); // should not throw
    }

    // ─── API Key allowedModels (S010) ───

    @Test
    void keyAllowedModelsShouldRejectDisallowedModel() {
        ClientConfig config = new ClientConfig();
        config.setEnabled(true);
        config.setAllowedModels(Set.of("gpt-4o-mini", "claude-3-sonnet"));

        // Key allows only gpt-4o-mini, even though config allows both
        ClientPrincipal principal = new ClientPrincipal("test-client", config, "user",
                "test-user", false, Set.of("gpt-4o-mini"), UserAccount.UserLimits.highDefaults());

        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authorizeModel(principal, "claude-3-sonnet"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("forbidden_model", ex.getCode());
    }

    @Test
    void keyAllowedModelsShouldAllowModelInSet() {
        ClientConfig config = new ClientConfig();
        config.setEnabled(true);
        config.setAllowedModels(Set.of("gpt-4o-mini", "claude-3-sonnet"));

        ClientPrincipal principal = new ClientPrincipal("test-client", config, "user",
                "test-user", false, Set.of("gpt-4o-mini"), UserAccount.UserLimits.highDefaults());

        // Key allows gpt-4o-mini → should pass through key check and config check
        authService.authorizeModel(principal, "gpt-4o-mini");
    }

    @Test
    void keyAllowedModelsShouldAllowModelWhenBothKeyAndUserAllow() {
        ClientConfig config = new ClientConfig();
        config.setEnabled(true);
        config.setAllowedModels(Set.of("gpt-4o-mini", "claude-3-sonnet", "gemini-pro"));

        ClientPrincipal principal = new ClientPrincipal("test-client", config, "user",
                "test-user", false, Set.of("gpt-4o-mini", "claude-3-sonnet"), UserAccount.UserLimits.highDefaults());

        // Both key and config allow → pass
        authService.authorizeModel(principal, "gpt-4o-mini");
        authService.authorizeModel(principal, "claude-3-sonnet");

        // Config allows gemini-pro but key does not → rejected (stricter wins)
        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authorizeModel(principal, "gemini-pro"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("forbidden_model", ex.getCode());
    }

    @Test
    void emptyKeyAllowedModelsShouldFallThroughToConfigCheck() {
        ClientConfig config = new ClientConfig();
        config.setEnabled(true);
        config.setAllowedModels(Set.of("gpt-4o-mini"));

        ClientPrincipal principal = new ClientPrincipal("empty-key-client", config, "user",
                "test-user", false, Set.of(), UserAccount.UserLimits.highDefaults());

        // keyAllowedModels is empty → only config check matters
        authService.authorizeModel(principal, "gpt-4o-mini"); // should pass

        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authorizeModel(principal, "claude-3-sonnet"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("forbidden_model", ex.getCode());
    }

    // ─── Token version validation ───

    @Test
    void shouldRejectTokenWithMismatchedVersion() {
        Claims claims = Jwts.claims()
                .subject("demo-client-key")
                .add("scope", List.of("chat"))
                .add("typ", "access")
                .add("role", "user")
                .add("tokenVersion", 0)
                .build();

        UserAccount account = new UserAccount(
                "demo-client-key", "hash", "user", "api-key",
                UserAccount.UserLimits.highDefaults(), null, null, null,
                List.of(), 0, 5, false, null // tokenVersion=5 but token has 0
        );

        when(jwtService.parseToken("stale-jwt")).thenReturn(claims);
        when(jwtService.isRefreshToken(claims)).thenReturn(false);
        when(jwtService.extractClientId(claims)).thenReturn("demo-client-key");
        when(jwtService.extractRole(claims)).thenReturn("user");
        when(jwtService.extractTokenVersion(claims)).thenReturn(0);
        when(userAccountService.findByUsernameSync("demo-client-key")).thenReturn(account);

        // JWT token version mismatch → falls through to static key → "stale-jwt" is not a static key → 401
        GatewayException ex = assertThrows(GatewayException.class,
                () -> authService.authenticate("Bearer stale-jwt"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }
}
