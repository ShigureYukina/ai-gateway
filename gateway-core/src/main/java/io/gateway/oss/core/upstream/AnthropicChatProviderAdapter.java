package io.gateway.oss.core.upstream;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@Component
public class AnthropicChatProviderAdapter implements ChatProviderAdapter {

    static final String PROVIDER_TYPE = "anthropic";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, WebClient> clientCache = new ConcurrentHashMap<>();

    public AnthropicChatProviderAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public Mono<Map<String, Object>> complete(ChatCompletionsRequest request, ResolvedRoute route) {
        return webClient(route)
                .post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(h -> AnthropicRequestBuilder.applyRequestHeaders(h, route.providerApiKey(), request))
                .bodyValue(AnthropicRequestBuilder.buildMessagesPayload(request, route, DEFAULT_MAX_TOKENS))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(normalizeUpstreamError(response.statusCode(), body))))
                .bodyToMono(MAP_TYPE)
                .map(body -> AnthropicResponseParser.normalizeToGatewayShape(body, route))
                .timeout(route.timeout())
                .onErrorMap(TimeoutException.class,
                        e -> new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout"));
    }

    @Override
    public Flux<String> stream(ChatCompletionsRequest request, ResolvedRoute route) {
        String syntheticId = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();

        Map<String, Object> payload = AnthropicRequestBuilder.buildStreamingMessagesPayload(request, route,
                DEFAULT_MAX_TOKENS);

        return webClient(route)
                .post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(h -> AnthropicRequestBuilder.applyRequestHeaders(h, route.providerApiKey(), request))
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(normalizeUpstreamError(response.statusCode(), body))))
                .bodyToFlux(String.class)
                .handle((String line, reactor.core.publisher.SynchronousSink<String> sink) -> {
                    String result = AnthropicResponseParser.parseStreamEvent(line, syntheticId, created,
                            route.requestedModel(), objectMapper);
                    if (result != null) {
                        sink.next(result);
                    }
                })
                .timeout(route.timeout(), Flux.error(
                        new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout")));
    }

    private WebClient webClient(ResolvedRoute route) {
        return clientCache.computeIfAbsent(route.baseUrl(),
                k -> webClientBuilder.baseUrl(k)
                        .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                        .build());
    }

    private GatewayException normalizeUpstreamError(HttpStatusCode statusCode, String body) {
        String message = body != null && !body.isEmpty()
                ? "Upstream provider error: " + body
                : "Upstream provider error";
        return new GatewayException(toStatus(statusCode), "upstream_error", message);
    }

    private HttpStatus toStatus(HttpStatusCode statusCode) {
        return HttpStatus.resolve(statusCode.value()) == null ? HttpStatus.BAD_GATEWAY : HttpStatus.valueOf(statusCode.value());
    }
}
