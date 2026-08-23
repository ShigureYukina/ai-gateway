package io.gateway.oss.core.contract;

import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.dto.ChatCompletionsRequest;

import java.time.Instant;

/**
 * Optional TPM service — implemented by gateway-admin's ClientTpmService.
 * When absent from the classpath, all tokens-per-minute limits are bypassed.
 */
public interface TpmService {

    long reserveEstimatedTokens(ClientPrincipal principal, ChatCompletionsRequest request, Instant now);

    void reconcile(String clientId, long reservedTokens, long actualTokens, Instant now);

    void release(String clientId, long reservedTokens, Instant now);
}
