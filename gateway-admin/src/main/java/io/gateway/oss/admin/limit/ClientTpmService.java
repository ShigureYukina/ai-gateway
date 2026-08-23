package io.gateway.oss.admin.limit;

import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.contract.security.UserAccount;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.stream.Collectors;

@Service
public class ClientTpmService implements io.gateway.oss.core.contract.TpmService {

    private static final long DEFAULT_FALLBACK_TOKENS = 32L;

    /**
     * Cached encoding instance — thread-safe, created once at class load.
     * Avoids per-request allocation overhead on the hot path.
     */
    private static final Encoding ENCODING;
    static {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        ENCODING = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    private final ClientTpmStore tpmStore;

    public ClientTpmService(ClientTpmStore tpmStore) {
        this.tpmStore = tpmStore;
    }

    public long reserveEstimatedTokens(ClientPrincipal principal, ChatCompletionsRequest request, Instant now) {
        Long limit = getEffectiveTokensPerMinute(principal);
        if (limit == null) {
            return 0L;
        }
        long estimated = estimateTokens(request);
        long reserved = tpmStore.reserve(principal.clientId(), estimated, limit, now);
        if (reserved < 0) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "tpm_exceeded", "Tokens-per-minute limit exceeded");
        }
        return estimated;
    }

    public void reconcile(String clientId, long reservedTokens, long actualTokens, Instant now) {
        if (reservedTokens <= 0) {
            return;
        }
        tpmStore.adjust(clientId, actualTokens - reservedTokens, now);
    }

    public void release(String clientId, long reservedTokens, Instant now) {
        if (reservedTokens <= 0) {
            return;
        }
        tpmStore.adjust(clientId, -reservedTokens, now);
    }

    /**
     * Estimate total tokens for the request using tiktoken for input and maxTokens for output.
     * reserve = inputEstimate + maxTokens (or fallback 32 for output).
     */
    long estimateTokens(ChatCompletionsRequest request) {
        if (request == null) {
            return DEFAULT_FALLBACK_TOKENS;
        }
        long inputEstimate = estimateInputTokens(request);
        long outputEstimate = (request.maxTokens() != null && request.maxTokens() > 0)
                ? request.maxTokens().longValue()
                : DEFAULT_FALLBACK_TOKENS;
        return inputEstimate + outputEstimate;
    }

    /**
     * Estimate input tokens by concatenating message text content and encoding with tiktoken.
     * Uses cached cl100k_base encoding for performance.
     */
    private long estimateInputTokens(ChatCompletionsRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return 0L;
        }
        String concatenated = request.messages().stream()
                .map(ChatMessage::textContent)
                .filter(s -> s != null && !s.isEmpty())
                .collect(Collectors.joining("\n"));
        if (concatenated.isEmpty()) {
            return 0L;
        }
        IntArrayList tokens = ENCODING.encode(concatenated);
        return tokens.size();
    }

    private Long getEffectiveTokensPerMinute(ClientPrincipal principal) {
        if (principal.config() != null) {
            Long v = principal.config().getLimits().getTokensPerMinute();
            if (v != null) return v;
        }
        UserAccount.UserLimits ul = principal.userLimits();
        return ul != null ? ul.tokensPerMinute() : null;
    }
}
