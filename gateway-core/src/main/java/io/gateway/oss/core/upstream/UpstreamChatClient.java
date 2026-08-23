package io.gateway.oss.core.upstream;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.routing.RouteSelector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;

@Service
public class UpstreamChatClient {

    private static final Pattern NON_EMPTY_CONTENT_PATTERN = Pattern.compile("\\\"content\\\"\\s*:\\s*\\\"(?:\\\\.|[^\\\"\\\\])+\\\"");
    private static final Pattern TOOL_CALLS_PATTERN = Pattern.compile("\\\"tool_calls\\\"\\s*:\\s*\\[");

    private final Map<String, ChatProviderAdapter> adaptersByType;
    private final RouteResilienceTracker resilienceTracker;
    private final ProviderKeySelector providerKeySelector;
    private final ProviderKeyResilienceTracker providerKeyResilienceTracker;
    private final RouteSelector routeSelector;
    private final Resilience4jCircuitBreakerService resilience4jService;

    @Autowired
    public UpstreamChatClient(List<ChatProviderAdapter> adapters,
                              RouteResilienceTracker resilienceTracker,
                              ProviderKeySelector providerKeySelector,
                              ProviderKeyResilienceTracker providerKeyResilienceTracker,
                              RouteSelector routeSelector,
                              Resilience4jCircuitBreakerService resilience4jService) {
        this.adaptersByType = adapters.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        ChatProviderAdapter::providerType,
                        Function.identity()
                ));
        this.resilienceTracker = resilienceTracker;
        this.providerKeySelector = providerKeySelector;
        this.providerKeyResilienceTracker = providerKeyResilienceTracker;
        this.routeSelector = routeSelector;
        this.resilience4jService = resilience4jService;
    }

    public UpstreamChatClient(List<ChatProviderAdapter> adapters,
                              RouteResilienceTracker resilienceTracker,
                              ProviderKeySelector providerKeySelector,
                              ProviderKeyResilienceTracker providerKeyResilienceTracker) {
        this(adapters,
                resilienceTracker,
                providerKeySelector,
                providerKeyResilienceTracker,
                new io.gateway.oss.core.routing.RouteLoadBalancer(new io.gateway.oss.core.config.GatewayProperties(), resilienceTracker),
                new Resilience4jCircuitBreakerService(new io.gateway.oss.core.config.GatewayProperties(),
                        new io.gateway.oss.core.observability.GatewayMetricsRecorder(io.micrometer.core.instrument.Metrics.globalRegistry)));
    }

    public UpstreamChatClient(List<ChatProviderAdapter> adapters,
                              RouteResilienceTracker resilienceTracker,
                              ProviderKeySelector providerKeySelector,
                              ProviderKeyResilienceTracker providerKeyResilienceTracker,
                              RouteSelector routeSelector) {
        this(adapters,
                resilienceTracker,
                providerKeySelector,
                providerKeyResilienceTracker,
                routeSelector,
                new Resilience4jCircuitBreakerService(new io.gateway.oss.core.config.GatewayProperties(),
                        new io.gateway.oss.core.observability.GatewayMetricsRecorder(io.micrometer.core.instrument.Metrics.globalRegistry)));
    }

    public Mono<Map<String, Object>> complete(ChatCompletionsRequest request, ResolvedRoute route) {
        ProviderKeySelector.SelectedProviderKey selectedKey = providerKeySelector.select(route);
        ResolvedRoute routeWithKey = route.withProviderApiKey(selectedKey.keyValue());
        Mono<Map<String, Object>> upstreamMono = adapterFor(routeWithKey).complete(request, routeWithKey)
                // Wrap non-GatewayException (e.g. ConnectException) into upstream_error
                // so fallback logic and error mapping can process them.
                .onErrorMap(e -> !(e instanceof GatewayException), e -> {
                    if (e instanceof TimeoutException) {
                        return new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout",
                                "Upstream timeout", e);
                    }
                    String msg = e.getMessage() != null ? e.getMessage() : "Upstream provider error";
                    return new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", msg, e);
                })
                .doOnSuccess(ignored -> {
                    resilienceTracker.recordSuccess(route);
                    providerKeyResilienceTracker.recordSuccess(selectedKey.keySlotId());
                })
                .doOnError(throwable -> {
                    GatewayException gatewayException = unwrapGatewayException(throwable);
                    if (gatewayException != null && shouldFallback(gatewayException)) {
                        resilienceTracker.recordRetryableFailure(route);
                        providerKeyResilienceTracker.recordRetryableFailure(selectedKey.keySlotId());
                    }
                });
        return resilience4jService.decorateMono(route.routeId(), upstreamMono);
    }

    public Mono<Map<String, Object>> completeWithFallback(ChatCompletionsRequest request,
                                                          ResolvedRoute primaryRoute,
                                                          java.util.function.Function<String, ResolvedRoute> fallbackResolver) {
        if (routeSelector.isEnabled()) {
            return completeWithLoadBalancing(request, primaryRoute, fallbackResolver);
        }
        return complete(request, primaryRoute)
                .onErrorResume(throwable -> tryFallback(request, primaryRoute, throwable, fallbackResolver));
    }

    public Flux<String> stream(ChatCompletionsRequest request, ResolvedRoute route) {
        ChatProviderAdapter adapter = adapterFor(route);
        if (!adapter.supportsStreaming()) {
            return Flux.error(new GatewayException(HttpStatus.NOT_IMPLEMENTED, "stream_not_supported",
                    "Streaming is not supported for provider type: " + route.providerType()));
        }
        ProviderKeySelector.SelectedProviderKey selectedKey = providerKeySelector.select(route);
        ResolvedRoute routeWithKey = route.withProviderApiKey(selectedKey.keyValue());
        Flux<String> upstreamFlux = adapter.stream(request, routeWithKey)
                .doOnComplete(() -> {
                    resilienceTracker.recordSuccess(route);
                    providerKeyResilienceTracker.recordSuccess(selectedKey.keySlotId());
                })
                .doOnError(throwable -> {
                    GatewayException gatewayException = unwrapGatewayException(throwable);
                    if (gatewayException != null && shouldFallback(gatewayException)) {
                        resilienceTracker.recordRetryableFailure(route);
                        providerKeyResilienceTracker.recordRetryableFailure(selectedKey.keySlotId());
                    }
                });
        return resilience4jService.decorateStreamFlux(route.routeId(), upstreamFlux);
    }

    public Flux<String> streamWithSelection(ChatCompletionsRequest request,
                                            ResolvedRoute primaryRoute,
                                            java.util.function.Function<String, ResolvedRoute> fallbackResolver) {
        if (!adapterFor(primaryRoute).supportsStreaming()) {
            return stream(request, primaryRoute);
        }

        if (!routeSelector.isEnabled()) {
            return stream(request, primaryRoute);
        }

        List<ResolvedRoute> candidates = resolveCandidates(primaryRoute, fallbackResolver).stream()
                .filter(this::supportsStreaming)
                .toList();
        ResolvedRoute selectedRoute = routeSelector.select(candidates);
        if (selectedRoute == null) {
            selectedRoute = primaryRoute;
        }
        return stream(request, selectedRoute);
    }

    public Flux<String> streamWithFallback(ChatCompletionsRequest request,
                                           ResolvedRoute primaryRoute,
                                           java.util.function.Function<String, ResolvedRoute> fallbackResolver) {
        return Flux.defer(() -> {
            AtomicBoolean businessOutputStarted = new AtomicBoolean(false);
            AtomicBoolean terminalDoneSeen = new AtomicBoolean(false);
            return streamWithSelection(request, primaryRoute, fallbackResolver)
                    .doOnNext(chunk -> {
                        if (containsTerminalDone(chunk)) {
                            terminalDoneSeen.set(true);
                        }
                        if (containsBusinessOutput(chunk)) {
                            businessOutputStarted.set(true);
                        }
                    })
                    .onErrorResume(throwable -> {
                        GatewayException gatewayException = unwrapGatewayException(throwable);
                        if (gatewayException == null
                                || !shouldFallback(gatewayException)
                                || businessOutputStarted.get()
                                || terminalDoneSeen.get()) {
                            return Flux.error(throwable);
                        }
                        return tryFallbackStream(request, primaryRoute, fallbackResolver, throwable);
                    });
        });
    }

    private Flux<String> tryFallbackStream(ChatCompletionsRequest request,
                                           ResolvedRoute failedRoute,
                                           java.util.function.Function<String, ResolvedRoute> fallbackResolver,
                                           Throwable originalError) {
        int retriesAllowed = Math.max(0, failedRoute.maxAttempts() - 1);
        if (retriesAllowed <= 0) {
            return Flux.error(originalError);
        }

        List<ResolvedRoute> candidates = healthyFallbackCandidates(failedRoute, fallbackResolver, retriesAllowed)
                .stream()
                .filter(this::supportsStreaming)
                .toList();
        if (candidates.isEmpty()) {
            return Flux.error(originalError);
        }

        return tryFallbackStreamCandidate(request, originalError, candidates, 0);
    }

    private Flux<String> tryFallbackStreamCandidate(ChatCompletionsRequest request,
                                                     Throwable lastThrowable,
                                                     List<ResolvedRoute> candidates,
                                                     int index) {
        if (index >= candidates.size()) {
            return Flux.error(lastThrowable);
        }

        ResolvedRoute fallbackRoute = candidates.get(index);
        return stream(request, fallbackRoute)
                .onErrorResume(next -> {
                    GatewayException nextGatewayException = unwrapGatewayException(next);
                    if (nextGatewayException == null || !shouldFallback(nextGatewayException)) {
                        return Flux.error(next);
                    }
                    return tryFallbackStreamCandidate(request, next, candidates, index + 1);
                });
    }

    private Mono<Map<String, Object>> completeWithLoadBalancing(ChatCompletionsRequest request,
                                                                 ResolvedRoute primaryRoute,
                                                                 java.util.function.Function<String, ResolvedRoute> fallbackResolver) {
        int maxAttempts = Math.max(1, primaryRoute.maxAttempts());
        List<ResolvedRoute> candidates = resolveCandidates(primaryRoute, fallbackResolver);

        ResolvedRoute selected = routeSelector.select(candidates);
        if (selected == null) {
            return Mono.error(new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "No healthy route available"));
        }

        List<ResolvedRoute> attemptPlan = new ArrayList<>();
        attemptPlan.add(selected);
        candidates.stream()
                .filter(candidate -> resilienceTracker.isAvailable(candidate.routeId()))
                .filter(candidate -> !Objects.equals(candidate.routeId(), selected.routeId()))
                .forEach(attemptPlan::add);

        if (attemptPlan.size() > maxAttempts) {
            attemptPlan = new ArrayList<>(attemptPlan.subList(0, maxAttempts));
        }

        return tryCandidates(request, attemptPlan, 0, null);
    }

    private Mono<Map<String, Object>> tryCandidates(ChatCompletionsRequest request,
                                                    List<ResolvedRoute> attemptPlan,
                                                    int index,
                                                    Throwable lastThrowable) {
        if (index >= attemptPlan.size()) {
            if (lastThrowable == null) {
                return Mono.error(new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "No healthy route available"));
            }
            return Mono.error(lastThrowable);
        }

        ResolvedRoute candidate = attemptPlan.get(index);
        return complete(request, candidate)
                .onErrorResume(throwable -> {
                    GatewayException gatewayException = unwrapGatewayException(throwable);
                    if (gatewayException == null || !shouldFallback(gatewayException)) {
                        return Mono.error(throwable);
                    }
                    return tryCandidates(request, attemptPlan, index + 1, throwable);
                });
    }

    private List<ResolvedRoute> resolveCandidates(ResolvedRoute primaryRoute,
                                                  java.util.function.Function<String, ResolvedRoute> fallbackResolver) {
        List<ResolvedRoute> candidates = new ArrayList<>();
        candidates.add(primaryRoute);
        for (String fallbackRouteId : primaryRoute.fallbackRouteIds()) {
            ResolvedRoute fallbackRoute = fallbackResolver.apply(fallbackRouteId);
            if (fallbackRoute != null) {
                candidates.add(fallbackRoute);
            }
        }
        return candidates;
    }

    private Mono<Map<String, Object>> tryFallback(ChatCompletionsRequest request,
                                                  ResolvedRoute currentRoute,
                                                  Throwable throwable,
                                                  java.util.function.Function<String, ResolvedRoute> fallbackResolver) {
        GatewayException gatewayException = unwrapGatewayException(throwable);
        if (gatewayException == null || !shouldFallback(gatewayException)) {
            return Mono.error(throwable);
        }

        int retriesAllowed = Math.max(0, currentRoute.maxAttempts() - 1);
        if (retriesAllowed <= 0) {
            return Mono.error(throwable);
        }

        List<ResolvedRoute> candidates = healthyFallbackCandidates(currentRoute, fallbackResolver, retriesAllowed);
        int attempts = candidates.size();
        if (attempts <= 0) {
            return Mono.error(throwable);
        }

        return tryCandidate(request, currentRoute, throwable, candidates, 0, attempts);
    }

    private Mono<Map<String, Object>> tryCandidate(ChatCompletionsRequest request,
                                                   ResolvedRoute currentRoute,
                                                   Throwable lastThrowable,
                                                   List<ResolvedRoute> candidates,
                                                   int index,
                                                   int attemptsRemaining) {
        if (attemptsRemaining <= 0 || index >= candidates.size()) {
            return Mono.error(lastThrowable);
        }

        ResolvedRoute fallbackRoute = candidates.get(index);
        return complete(request, fallbackRoute)
                .onErrorResume(next -> {
                    GatewayException nextGatewayException = unwrapGatewayException(next);
                    if (nextGatewayException == null || !shouldFallback(nextGatewayException)) {
                        return Mono.error(next);
                    }
                    return tryCandidate(request, currentRoute, next, candidates, index + 1, attemptsRemaining - 1);
                });
    }

    private List<ResolvedRoute> healthyFallbackCandidates(ResolvedRoute currentRoute,
                                                          java.util.function.Function<String, ResolvedRoute> fallbackResolver,
                                                          int maxCandidates) {
        List<ResolvedRoute> candidates = new ArrayList<>();
        for (String fallbackRouteId : currentRoute.fallbackRouteIds()) {
            if (candidates.size() >= maxCandidates) {
                break;
            }
            if (!resilienceTracker.isAvailable(fallbackRouteId)) {
                continue;
            }
            ResolvedRoute fallbackRoute = fallbackResolver.apply(fallbackRouteId);
            if (resilienceTracker.isAvailable(fallbackRoute)) {
                candidates.add(fallbackRoute);
            }
        }
        return candidates;
    }

    private GatewayException unwrapGatewayException(Throwable throwable) {
        Throwable current = Exceptions.unwrap(throwable);
        Set<Throwable> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (current instanceof GatewayException gatewayException) {
                return gatewayException;
            }
            current = Exceptions.unwrap(current.getCause());
        }
        return null;
    }

    private boolean shouldFallback(GatewayException exception) {
        if (exception.getStatus() == HttpStatus.GATEWAY_TIMEOUT) {
            return "upstream_timeout".equals(exception.getCode());
        }
        if (exception.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
            return "circuit_breaker_open".equals(exception.getCode());
        }
        return exception.getStatus().is5xxServerError() && "upstream_error".equals(exception.getCode());
    }

    private boolean containsTerminalDone(String chunk) {
        return chunk != null && chunk.contains("data: [DONE]");
    }

    private boolean containsBusinessOutput(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return false;
        }
        if (TOOL_CALLS_PATTERN.matcher(chunk).find()) {
            return true;
        }
        return NON_EMPTY_CONTENT_PATTERN.matcher(chunk).find();
    }

    private ChatProviderAdapter adapterFor(ResolvedRoute route) {
        ChatProviderAdapter adapter = adaptersByType.get(route.providerType());
        if (adapter == null) {
            throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "config_error", "Unsupported provider type: " + route.providerType());
        }
        return adapter;
    }

    private boolean supportsStreaming(ResolvedRoute route) {
        return adapterFor(route).supportsStreaming();
    }
}
