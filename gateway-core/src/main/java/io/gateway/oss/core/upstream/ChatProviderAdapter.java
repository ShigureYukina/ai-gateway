package io.gateway.oss.core.upstream;

import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface ChatProviderAdapter {

    String providerType();

    default boolean supportsStreaming() {
        return true;
    }

    Mono<Map<String, Object>> complete(ChatCompletionsRequest request, ResolvedRoute route);

    Flux<String> stream(ChatCompletionsRequest request, ResolvedRoute route);
}
