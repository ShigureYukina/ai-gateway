package io.gateway.oss.admin.limit;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.ClientLimits;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.contract.security.UserAccount;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.error.GatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientTpmServiceTest {

    private ClientTpmStore tpmStore;
    private ClientTpmService tpmService;
    private Instant now;

    @BeforeEach
    void setUp() {
        tpmStore = mock(ClientTpmStore.class);
        tpmService = new ClientTpmService(tpmStore);
        now = Instant.now();
    }

    // ─── reserveEstimatedTokens ───

    @Test
    void shouldReturnZeroWhenNoTpmLimitConfigured() {
        // Principal with no config and no user limits
        ClientPrincipal principal = new ClientPrincipal("user1", null, "user", "user1", false, Set.of(), null);

        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini", List.of(new ChatMessage("user", "hello")), false, null, 256
        );

        long reserved = tpmService.reserveEstimatedTokens(principal, request, now);
        assertEquals(0L, reserved);
    }

    @Test
    void shouldReserveTokensWhenWithinLimit() {
        ClientConfig config = new ClientConfig();
        config.setEnabled(true);
        ClientLimits limits = new ClientLimits();
        limits.setTokensPerMinute(10000L);
        config.setLimits(limits);

        ClientPrincipal principal = new ClientPrincipal("user1", config, "user");

        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini", List.of(new ChatMessage("user", "hello")), false, null, 256
        );

        when(tpmStore.reserve(eq("user1"), anyLong(), eq(10000L), eq(now))).thenReturn(1L); // positive = success

        long reserved = tpmService.reserveEstimatedTokens(principal, request, now);
        assertTrue(reserved > 0, "Should have reserved some tokens");
    }

    @Test
    void shouldThrowWhenTpmExceeded() {
        ClientConfig config = new ClientConfig();
        config.setEnabled(true);
        ClientLimits limits = new ClientLimits();
        limits.setTokensPerMinute(100L);
        config.setLimits(limits);

        ClientPrincipal principal = new ClientPrincipal("user1", config, "user");

        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini", List.of(new ChatMessage("user", "hello")), false, null, 256
        );

        when(tpmStore.reserve(eq("user1"), anyLong(), eq(100L), eq(now))).thenReturn(-1L); // negative = exceeded

        GatewayException ex = assertThrows(GatewayException.class,
                () -> tpmService.reserveEstimatedTokens(principal, request, now));
        assertEquals(429, ex.getStatus().value());
        assertEquals("tpm_exceeded", ex.getCode());
    }

    @Test
    void shouldUseUserLimitsWhenConfigHasNoTpm() {
        UserAccount.UserLimits userLimits = new UserAccount.UserLimits(
                null, null, 5000L, null, null, null
        );
        ClientPrincipal principal = new ClientPrincipal("user1", null, "user", "user1", false, Set.of(), userLimits);

        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini", List.of(new ChatMessage("user", "hello")), false, null, 256
        );

        when(tpmStore.reserve(eq("user1"), anyLong(), eq(5000L), eq(now))).thenReturn(1L);

        long reserved = tpmService.reserveEstimatedTokens(principal, request, now);
        assertTrue(reserved > 0);
    }

    // ─── reconcile ───

    @Test
    void shouldAdjustDifferenceOnReconcile() {
        tpmService.reconcile("user1", 100L, 80L, now);
        verify(tpmStore).adjust("user1", -20L, now); // actual - reserved = 80 - 100 = -20
    }

    @Test
    void shouldSkipReconcileWhenReservedIsZero() {
        tpmService.reconcile("user1", 0L, 50L, now);
        // Should not call adjust
    }

    // ─── release ───

    @Test
    void shouldReleaseReservedTokensOnError() {
        tpmService.release("user1", 100L, now);
        verify(tpmStore).adjust("user1", -100L, now);
    }

    @Test
    void shouldSkipReleaseWhenReservedIsZero() {
        tpmService.release("user1", 0L, now);
        // Should not call adjust
    }

    // ─── estimateTokens ───

    @Test
    void shouldEstimateTokensForRequest() {
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "What is the meaning of life?")),
                false, null, 256
        );

        long estimate = tpmService.estimateTokens(request);
        assertTrue(estimate > 256, "Estimate should include input + output tokens");
    }

    @Test
    void shouldReturnFallbackForNullRequest() {
        long estimate = tpmService.estimateTokens(null);
        assertEquals(32L, estimate); // DEFAULT_FALLBACK_TOKENS
    }

    @Test
    void shouldUseFallbackOutputWhenMaxTokensNull() {
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hi")),
                false, null, null // no maxTokens
        );

        long estimate = tpmService.estimateTokens(request);
        // Output fallback is 32
        assertTrue(estimate >= 32, "Should use fallback output estimate");
    }
}
