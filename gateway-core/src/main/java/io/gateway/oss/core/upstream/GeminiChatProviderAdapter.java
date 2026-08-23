package io.gateway.oss.core.upstream;

import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
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
public class GeminiChatProviderAdapter implements ChatProviderAdapter {

    static final String PROVIDER_TYPE = "gemini";
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, WebClient> clientCache = new ConcurrentHashMap<>();

    public GeminiChatProviderAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
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
        String uri = "/v1beta/models/" + route.upstreamModel() + ":generateContent";
        return webClient(route)
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    h.set("x-goog-api-key", route.providerApiKey());
                    setRequestIdHeader(h, request);
                })
                .bodyValue(GeminiRequestBuilder.buildPayload(request))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(GeminiResponseParser.normalizeUpstreamError(response.statusCode(), body))))
                .bodyToMono(MAP_TYPE)
                .map(body -> GeminiResponseParser.normalizeToGatewayShape(body, route.requestedModel()))
                .timeout(route.timeout())
                .onErrorMap(TimeoutException.class,
                        e -> new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout"));
    }

    @Override
    public Flux<String> stream(ChatCompletionsRequest request, ResolvedRoute route) {
        String uri = "/v1beta/models/" + route.upstreamModel() + ":streamGenerateContent?alt=sse";
        String syntheticId = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();

        return webClient(route)
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(h -> {
                    h.set("x-goog-api-key", route.providerApiKey());
                    setRequestIdHeader(h, request);
                })
                .bodyValue(GeminiRequestBuilder.buildPayload(request))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(GeminiResponseParser.normalizeUpstreamError(response.statusCode(), body))))
                .bodyToFlux(String.class)
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring("data: ".length()).trim())
                .filter(json -> !json.isEmpty())
                .map(json -> GeminiResponseParser.parseStreamChunk(json, objectMapper))
                .map(geminiChunk -> GeminiResponseParser.toOpenAiStreamChunk(
                        geminiChunk,
                        syntheticId,
                        created,
                        route.requestedModel(),
                        objectMapper))
                .timeout(route.timeout(), Flux.error(
                        new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout")));
    }

    private WebClient webClient(ResolvedRoute route) {
        String baseUrl = route.baseUrl() != null ? route.baseUrl() : DEFAULT_BASE_URL;
        return clientCache.computeIfAbsent(baseUrl,
                k -> webClientBuilder.baseUrl(k).build());
    }

    private void setRequestIdHeader(HttpHeaders headers, ChatCompletionsRequest request) {
        Object requestId = request.extras().get(ChatCompletionsRequest.GATEWAY_REQUEST_ID_EXTRA);
        if (requestId instanceof String text && !text.isBlank()) {
            headers.set("X-Request-Id", text);
        }
    }
}
