package io.gateway.oss.admin.observability;

import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AggregateMetricRecorderImplTest {

    @Mock
    private AggregateReportingService reportingService;

    private AggregateMetricRecorderImpl recorder;

    @BeforeEach
    void setUp() {
        recorder = new AggregateMetricRecorderImpl(reportingService);
    }

    @Test
    void recordSuccessShouldDelegateWithCorrectParamsWhenUsernamePresent() {
        ClientPrincipal principal = new ClientPrincipal("client-1", null, "user", "alice", false, Set.of("gpt-4o"), null, null);
        ResolvedRoute route = new ResolvedRoute(
                "requested-model",
                "route-1",
                "chat",
                "openai",
                "public",
                "upstream-model",
                "https://api.example.com",
                "provider-key",
                Duration.ofSeconds(30),
                2,
                List.of("fallback-1")
        );
        Instant now = Instant.parse("2026-06-04T12:00:00Z");

        recorder.recordSuccess("req-1", principal, route, "gpt-4o", 123L, 0.75d, now);

        ArgumentCaptor<String> requestIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> providerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyRefCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyDisplayNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> clientIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> tokensCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Double> costCaptor = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);

        verify(reportingService).recordSuccess(
                requestIdCaptor.capture(),
                providerCaptor.capture(),
                userCaptor.capture(),
                keyRefCaptor.capture(),
                keyDisplayNameCaptor.capture(),
                clientIdCaptor.capture(),
                modelCaptor.capture(),
                tokensCaptor.capture(),
                costCaptor.capture(),
                nowCaptor.capture()
        );

        assertEquals("req-1", requestIdCaptor.getValue());
        assertEquals("openai", providerCaptor.getValue());
        assertEquals("alice", userCaptor.getValue());
        assertEquals("client-1", keyRefCaptor.getValue());
        assertEquals("client-1", keyDisplayNameCaptor.getValue());
        assertEquals("client-1", clientIdCaptor.getValue());
        assertEquals("gpt-4o", modelCaptor.getValue());
        assertEquals(123L, tokensCaptor.getValue());
        assertEquals(0.75d, costCaptor.getValue());
        assertEquals(now, nowCaptor.getValue());
    }

    @Test
    void recordSuccessShouldUseClientIdWhenUsernameMissing() {
        ClientPrincipal principal = new ClientPrincipal("client-2", null, "user", null, false, Set.of(), null, null);
        ResolvedRoute route = new ResolvedRoute(
                "requested-model",
                "route-2",
                "chat",
                "anthropic",
                "public",
                "claude-upstream",
                "https://api.example.com",
                "provider-key",
                Duration.ofSeconds(20),
                1,
                List.of()
        );
        Instant now = Instant.parse("2026-06-04T12:05:00Z");

        recorder.recordSuccess("req-2", principal, route, "claude-3-5-sonnet", 88L, 0.33d, now);

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> providerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> modelCaptor = ArgumentCaptor.forClass(String.class);

        verify(reportingService).recordSuccess(
                org.mockito.ArgumentMatchers.eq("req-2"),
                providerCaptor.capture(),
                userCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("client-2"),
                org.mockito.ArgumentMatchers.eq("client-2"),
                org.mockito.ArgumentMatchers.eq("client-2"),
                modelCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.eq(0.33d),
                org.mockito.ArgumentMatchers.eq(now)
        );

        assertEquals("anthropic", providerCaptor.getValue());
        assertEquals("client-2", userCaptor.getValue());
        assertEquals("claude-3-5-sonnet", modelCaptor.getValue());
    }

    @Test
    void recordFailureStatusShouldDelegateToReportingService() {
        Instant now = Instant.parse("2026-06-04T12:10:00Z");

        recorder.recordFailureStatus("req-fail", 502, now);

        verify(reportingService).recordFailureStatus("req-fail", 502, now);
    }
}
