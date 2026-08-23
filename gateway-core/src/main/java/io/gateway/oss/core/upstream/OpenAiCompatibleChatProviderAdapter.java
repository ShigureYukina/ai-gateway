package io.gateway.oss.core.upstream;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@Component
public class OpenAiCompatibleChatProviderAdapter implements ChatProviderAdapter {

    static final String PROVIDER_TYPE = "openai-compatible";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebClient.Builder webClientBuilder;
    private final ConcurrentHashMap<String, WebClient> clientCache = new ConcurrentHashMap<>();

    public OpenAiCompatibleChatProviderAdapter(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Mono<Map<String, Object>> complete(ChatCompletionsRequest request, ResolvedRoute route) {
        return webClient(route)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    h.setBearerAuth(route.providerApiKey());
                    setRequestIdHeader(h, request);
                })
                .bodyValue(upstreamPayload(request, route))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(normalizeUpstreamError(response.statusCode(), body))))
                .bodyToMono(MAP_TYPE)
                .map(body -> normalizeToGatewayShape(body, route))
                .timeout(route.timeout())
                .onErrorMap(TimeoutException.class, e -> new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout"));
    }

    @Override
    public Flux<String> stream(ChatCompletionsRequest request, ResolvedRoute route) {
        return webClient(route)
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(h -> {
                    h.setBearerAuth(route.providerApiKey());
                    setRequestIdHeader(h, request);
                })
                .bodyValue(upstreamPayload(request, route))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(normalizeUpstreamError(response.statusCode(), body))))
                .bodyToFlux(String.class)
                .timeout(route.timeout(), Flux.error(new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout")));
    }

    private WebClient webClient(ResolvedRoute route) {
        return clientCache.computeIfAbsent(route.baseUrl(),
                k -> webClientBuilder.baseUrl(k).build());
    }

    private Map<String, Object> upstreamPayload(ChatCompletionsRequest request, ResolvedRoute route) {
        Map<String, Object> payload = request.toMap();
        payload.remove(ChatCompletionsRequest.GATEWAY_REQUEST_ID_EXTRA);
        payload.put("model", route.upstreamModel());
        return payload;
    }

    private void setRequestIdHeader(HttpHeaders headers, ChatCompletionsRequest request) {
        Object requestId = request.extras().get(ChatCompletionsRequest.GATEWAY_REQUEST_ID_EXTRA);
        if (requestId instanceof String text && !text.isBlank()) {
            headers.set("X-Request-Id", text);
        }
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

    private Map<String, Object> normalizeToGatewayShape(Map<String, Object> body, ResolvedRoute route) {
        Map<String, Object> normalized = new HashMap<>(body);
        if (!normalized.containsKey("model") || normalized.get("model") == null) {
            normalized.put("model", route.requestedModel());
        }
        if (!normalized.containsKey("object") || normalized.get("object") == null) {
            normalized.put("object", "chat.completion");
        }
        ensureUsageTotalTokens(normalized);
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private void ensureUsageTotalTokens(Map<String, Object> body) {
        Object usageRaw = body.get("usage");
        if (!(usageRaw instanceof Map<?, ?> usage)) {
            return;
        }
        Map<String, Object> usageMap = new HashMap<>((Map<String, Object>) usage);
        if (!usageMap.containsKey("total_tokens") || usageMap.get("total_tokens") == null) {
            Object prompt = usageMap.get("prompt_tokens");
            Object completion = usageMap.get("completion_tokens");
            if (prompt instanceof Number pNum && completion instanceof Number cNum) {
                usageMap.put("total_tokens", pNum.intValue() + cNum.intValue());
            }
        }
        body.put("usage", usageMap);
    }
}
