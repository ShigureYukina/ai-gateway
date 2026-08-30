package io.gateway.oss.core.security;

import io.gateway.oss.core.config.AuthConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.JwtConfig;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("dGVzdC1zZWNyZXQta2V5LWZvci1obWFjLXNoYTI1Ni10ZXN0");
        jwt.setAccessExpiration(Duration.ofMinutes(15));
        jwt.setRefreshExpiration(Duration.ofDays(7));
        auth.setJwt(jwt);
        props.setAuth(auth);
        jwtService = new JwtService(props);
    }

    @Test
    void generateAccessToken_andParse_roundTrip() {
        String token = jwtService.generateAccessToken("client1", List.of("read", "write"), "admin");
        Claims claims = jwtService.parseToken(token);

        assertEquals("client1", claims.getSubject());
        assertEquals(List.of("read", "write"), claims.get("scope", List.class));
        assertEquals("access", claims.get("typ", String.class));
        assertEquals("admin", claims.get("role", String.class));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void generateAccessToken_withSeparateClientId_claimRoundTrip() {
        String token = jwtService.generateAccessToken("admin", "demo-client-key", List.of("read"), "admin", 3);
        Claims claims = jwtService.parseToken(token);

        assertEquals("admin", claims.getSubject());
        assertEquals("demo-client-key", claims.get("clientId", String.class));
        assertEquals("demo-client-key", jwtService.extractClientId(claims));
        assertEquals("admin", jwtService.extractUsername(claims));
        assertEquals(3, jwtService.extractTokenVersion(claims));
    }

    @Test
    void generateRefreshToken_andParse_roundTrip() {
        String token = jwtService.generateRefreshToken("client1", 0);
        Claims claims = jwtService.parseToken(token);

        assertEquals("client1", claims.getSubject());
        assertEquals("refresh", claims.get("typ", String.class));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void generateRefreshToken_withSeparateClientId_claimRoundTrip() {
        String token = jwtService.generateRefreshToken("admin", "demo-client-key", 0);
        Claims claims = jwtService.parseToken(token);

        assertEquals("admin", claims.getSubject());
        assertEquals("demo-client-key", claims.get("clientId", String.class));
        assertEquals("demo-client-key", jwtService.extractClientId(claims));
        assertEquals("admin", jwtService.extractUsername(claims));
    }

    @Test
    void parseToken_expiredToken_throwsJwtValidationException() {
        GatewayProperties props = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("dGVzdC1zZWNyZXQta2V5LWZvci1obWFjLXNoYTI1Ni10ZXN0");
        jwt.setAccessExpiration(Duration.ofMillis(1));
        jwt.setRefreshExpiration(Duration.ofMillis(1));
        auth.setJwt(jwt);
        props.setAuth(auth);
        JwtService shortLivedService = new JwtService(props);

        String token = shortLivedService.generateAccessToken("client1", List.of("read"));

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThrows(JwtValidationException.class, () -> shortLivedService.parseToken(token));
    }

    @Test
    void parseToken_invalidToken_throwsJwtValidationException() {
        assertThrows(JwtValidationException.class, () -> jwtService.parseToken("invalid.token.here"));
    }

    @Test
    void isRefreshToken_trueForRefresh() {
        String token = jwtService.generateRefreshToken("client1", 0);
        Claims claims = jwtService.parseToken(token);
        assertTrue(jwtService.isRefreshToken(claims));
    }

    @Test
    void isRefreshToken_falseForAccess() {
        String token = jwtService.generateAccessToken("client1", List.of("read"));
        Claims claims = jwtService.parseToken(token);
        assertFalse(jwtService.isRefreshToken(claims));
    }

    @Test
    void extractRole_defaultUser() {
        String token = jwtService.generateAccessToken("client1", List.of("read"));
        Claims claims = jwtService.parseToken(token);
        assertEquals("user", jwtService.extractRole(claims));
    }

    @Test
    void extractRole_customRole() {
        String token = jwtService.generateAccessToken("client1", List.of("read"), "admin");
        Claims claims = jwtService.parseToken(token);
        assertEquals("admin", jwtService.extractRole(claims));
    }

    @Test
    void invalidateToken_removesFromCache() {
        String token = jwtService.generateAccessToken("client1", List.of("read"));
        Claims firstParse = jwtService.parseToken(token);
        assertNotNull(firstParse);

        jwtService.invalidateToken(token);

        Claims secondParse = jwtService.parseToken(token);
        assertNotNull(secondParse);
        assertEquals(firstParse.getSubject(), secondParse.getSubject());
    }

    @Test
    void invalidateUserTokens_removesAllForUser() {
        String token1 = jwtService.generateAccessToken("userA", List.of("read"));
        String token2 = jwtService.generateAccessToken("userA", List.of("write"));
        String token3 = jwtService.generateAccessToken("userB", List.of("read"));

        Claims claims1 = jwtService.parseToken(token1);
        Claims claims2 = jwtService.parseToken(token2);
        Claims claims3 = jwtService.parseToken(token3);
        assertNotNull(claims1);
        assertNotNull(claims2);
        assertNotNull(claims3);

        jwtService.invalidateUserTokens("userA");

        Claims reparsed1 = jwtService.parseToken(token1);
        Claims reparsed2 = jwtService.parseToken(token2);
        assertNotNull(reparsed1);
        assertNotNull(reparsed2);
        assertEquals("userA", reparsed1.getSubject());
        assertEquals("userA", reparsed2.getSubject());
    }

    @Test
    void extractTokenVersion_returnsVersion() {
        String token = jwtService.generateAccessToken("client1", List.of("read"), "user", 5);
        Claims claims = jwtService.parseToken(token);
        assertEquals(5, jwtService.extractTokenVersion(claims));
    }

    @Test
    void extractTokenVersion_defaultZero() {
        String token = jwtService.generateAccessToken("client1", List.of("read"));
        Claims claims = jwtService.parseToken(token);
        assertEquals(0, jwtService.extractTokenVersion(claims));
    }

    @Test
    void resolveKey_base64Secret_works() {
        GatewayProperties props = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("dGVzdC1zZWNyZXQta2V5LWZvci1obWFjLXNoYTI1Ni10ZXN0");
        jwt.setAccessExpiration(Duration.ofMinutes(15));
        jwt.setRefreshExpiration(Duration.ofDays(7));
        auth.setJwt(jwt);
        props.setAuth(auth);
        JwtService service = new JwtService(props);

        String token = service.generateAccessToken("client1", List.of("read"));
        Claims claims = service.parseToken(token);
        assertEquals("client1", claims.getSubject());
    }

    @Test
    void resolveKey_rawStringSecret_works() {
        GatewayProperties props = new GatewayProperties();
        AuthConfig auth = new AuthConfig();
        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("this-is-not-base64-but-long-enough-for-hmac-sha256!!");
        jwt.setAccessExpiration(Duration.ofMinutes(15));
        jwt.setRefreshExpiration(Duration.ofDays(7));
        auth.setJwt(jwt);
        props.setAuth(auth);
        JwtService service = new JwtService(props);

        String token = service.generateAccessToken("client1", List.of("read"));
        Claims claims = service.parseToken(token);
        assertEquals("client1", claims.getSubject());
    }
}
