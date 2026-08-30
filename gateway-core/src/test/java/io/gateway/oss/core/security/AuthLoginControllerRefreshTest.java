package io.gateway.oss.core.security;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.error.GatewayException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * /auth/refresh 的版本校验语义（审查 F4：refresh token 绑定签发时的 tokenVersion，
 * 改密/重置/封禁后旧 refresh token 立即失效）与吊销语义（审查 F3）。
 */
@ExtendWith(MockitoExtension.class)
class AuthLoginControllerRefreshTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private UserAccountService userAccountService;
    @Mock
    private RefreshTokenBlacklistService blacklistService;
    @Mock
    private LoginRateLimiter loginRateLimiter;
    @Mock
    private AuthSupport authSupport;
    @Mock
    private ServerWebExchange exchange;

    private AuthLoginController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthLoginController(jwtService, new GatewayProperties(),
                userAccountService, blacklistService, loginRateLimiter, authSupport);
    }

    private Claims stubRefreshToken(String username, String clientId, int tokenVersion) {
        Claims claims = mock(Claims.class);
        when(jwtService.parseToken("refresh-token")).thenReturn(claims);
        when(jwtService.isRefreshToken(claims)).thenReturn(true);
        when(jwtService.extractUsername(claims)).thenReturn(username);
        when(jwtService.extractClientId(claims)).thenReturn(clientId);
        when(jwtService.extractTokenVersion(claims)).thenReturn(tokenVersion);
        return claims;
    }

    private UserAccount accountWithVersion(int tokenVersion) {
        return new UserAccount("alice", "hash", "user", null,
                UserAccount.UserLimits.highDefaults(), null, null, null,
                List.of(), 0L, tokenVersion, false, null);
    }

    @Test
    void refresh_rejectsTokenWhenVersionMismatch() {
        stubRefreshToken("alice", "alice", 0);
        when(blacklistService.consumeOnce(eq("refresh-token"), any(Claims.class))).thenReturn(Mono.just(true));
        when(userAccountService.findByUsername("alice")).thenReturn(Mono.just(accountWithVersion(1)));

        StepVerifier.create(controller.refresh(new AuthLoginController.RefreshRequest("refresh-token"), exchange))
                .expectErrorSatisfies(error -> {
                    GatewayException ex = (GatewayException) error;
                    assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
                    assertEquals("token_revoked", ex.getCode());
                })
                .verify();
    }

    @Test
    void refresh_issuesRotatedTokensBoundToCurrentVersion() {
        stubRefreshToken("alice", "alice", 3);
        when(blacklistService.consumeOnce(eq("refresh-token"), any(Claims.class))).thenReturn(Mono.just(true));
        when(userAccountService.findByUsername("alice")).thenReturn(Mono.just(accountWithVersion(3)));
        when(authSupport.resolveScopes("alice")).thenReturn(List.of("chat"));
        when(jwtService.generateAccessToken("alice", "alice", List.of("chat"), "user", 3)).thenReturn("new-access");
        when(jwtService.generateRefreshToken("alice", "alice", 3)).thenReturn("new-refresh");

        StepVerifier.create(controller.refresh(new AuthLoginController.RefreshRequest("refresh-token"), exchange))
                .assertNext((ResponseEntity<AuthLoginController.LoginResponse> response) -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("new-access", response.getBody().accessToken());
                    assertEquals("new-refresh", response.getBody().refreshToken());
                })
                .verifyComplete();

        // 新 refresh token 必须绑定账户当前 tokenVersion，而非沿用旧 claims
        verify(jwtService).generateRefreshToken("alice", "alice", 3);
    }

    @Test
    void refresh_rejectsRevokedTokenBeforeAccountLookup() {
        Claims claims = mock(Claims.class);
        when(jwtService.parseToken("refresh-token")).thenReturn(claims);
        when(jwtService.isRefreshToken(claims)).thenReturn(true);
        when(blacklistService.consumeOnce(eq("refresh-token"), any(Claims.class))).thenReturn(Mono.just(false));

        StepVerifier.create(controller.refresh(new AuthLoginController.RefreshRequest("refresh-token"), exchange))
                .expectErrorSatisfies(error -> {
                    GatewayException ex = (GatewayException) error;
                    assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
                    assertEquals("token_revoked", ex.getCode());
                })
                .verify();

        verifyNoInteractions(userAccountService);
    }
}
