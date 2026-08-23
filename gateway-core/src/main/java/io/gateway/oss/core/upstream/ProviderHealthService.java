package io.gateway.oss.core.upstream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProviderHealthService {

    private final WebClient.Builder webClientBuilder;
    private final ConcurrentHashMap<String, WebClient> clientCache = new ConcurrentHashMap<>();

    public ProviderHealthService(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    public Mono<ProviderTestResult> test(String baseUrl, String apiKey, Duration timeout) {
        long startNanos = System.nanoTime();

        return webClient(baseUrl)
                .get()
                .uri("/v1/models")
                .headers(h -> h.setBearerAuth(apiKey))
                .exchangeToMono(response -> toResult(response.statusCode(), startNanos))
                .timeout(timeout)
                .onErrorResume(throwable -> Mono.just(errorResult(throwable, startNanos)));
    }

    public Mono<List<String>> fetchModels(String baseUrl, String apiKey, Duration timeout) {
        return webClient(baseUrl)
                .get()
                .uri("/v1/models")
                .headers(h -> { if (apiKey != null && !apiKey.isBlank()) h.setBearerAuth(apiKey); })
                .retrieve()
                .bodyToMono(ModelsResponse.class)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(30))
                .map(resp -> {
                    if (resp.data == null) return List.<String>of();
                    List<String> ids = new ArrayList<>();
                    for (ModelEntry entry : resp.data) {
                        if (entry.id != null && !entry.id.isBlank()) ids.add(entry.id);
                    }
                    Collections.sort(ids);
                    return List.copyOf(ids);
                });
    }

    private WebClient webClient(String baseUrl) {
        return clientCache.computeIfAbsent(baseUrl,
                k -> webClientBuilder.baseUrl(k).build());
    }

    private Mono<ProviderTestResult> toResult(HttpStatusCode statusCode, long startNanos) {
        long latencyMs = elapsedMs(startNanos);
        if (statusCode.is2xxSuccessful()) {
            return Mono.just(new ProviderTestResult("ok", latencyMs, statusCode.value(), null));
        }
        return Mono.just(new ProviderTestResult("error", latencyMs, statusCode.value(), "HTTP " + statusCode.value()));
    }

    private ProviderTestResult errorResult(Throwable throwable, long startNanos) {
        long latencyMs = elapsedMs(startNanos);
        if (throwable instanceof WebClientRequestException) {
            return new ProviderTestResult("error", latencyMs, null, safeMessage(throwable));
        }
        return new ProviderTestResult("error", latencyMs, null, safeMessage(throwable));
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    public record ProviderTestResult(String status, long latencyMs, Integer httpStatus, String error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ModelsResponse {
        public List<ModelEntry> data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ModelEntry {
        public String id;
    }
}
