package io.gateway.oss.core.web;

import io.gateway.oss.core.config.Backend;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.limit.ClientRateLimiter;
import io.gateway.oss.core.limit.ConcurrentRequestLimiter;
import io.gateway.oss.core.limit.RateLimitStatus;
import io.gateway.oss.core.routing.ModelRouteResolver;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.upstream.UpstreamChatClient;
import io.gateway.oss.core.util.BatchFlusher;
import io.gateway.oss.core.contract.AggregateMetricRecorder;
import io.gateway.oss.core.contract.QuotaService;
import io.gateway.oss.core.contract.BudgetService;
import io.gateway.oss.core.contract.TpmService;
import io.gateway.oss.core.web.support.ConcurrencyLimitHelper;
import io.gateway.oss.core.web.support.ErrorResponseMapper;
import io.gateway.oss.core.web.support.TokenExtractionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ChatCompletionsOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsOrchestrator.class);

    private final ClientAuthService clientAuthService;
    private final ModelRouteResolver routeResolver;
    private final ClientRateLimiter rateLimiter;
    private final UpstreamChatClient upstreamClient;
    private final OperationalGateService operationalGateService;
    private final ConcurrentRequestLimiter concurrentRequestLimiter;
    private final CompletionRecorder completionRecorder;
    private final BatchFlusher batchFlusher;
    private final AdminRuntimeServices adminRuntimeServices;
    private final Backend sharedBackend;
    private final Scheduler boundedElasticScheduler;

    @Autowired
    public ChatCompletionsOrchestrator(ClientAuthService clientAuthService,
                                       ModelRouteResolver routeResolver,
                                       ClientRateLimiter rateLimiter,
                                       UpstreamChatClient upstreamClient,
                                       GatewayProperties properties,
                                       OperationalGateService operationalGateService,
                                       ConcurrentRequestLimiter concurrentRequestLimiter,
                                       CompletionRecorder completionRecorder,
                                       BatchFlusher batchFlusher,
                                       @Qualifier("boundedElasticScheduler") Scheduler boundedElasticScheduler,
                                       ObjectProvider<QuotaService> quotaServiceProvider,
                                       ObjectProvider<BudgetService> budgetServiceProvider,
                                       ObjectProvider<TpmService> tpmServiceProvider,
                                       ObjectProvider<AggregateMetricRecorder> aggregateMetricRecorderProvider) {
        this(clientAuthService, routeResolver, rateLimiter, upstreamClient, properties,
                operationalGateService, concurrentRequestLimiter, completionRecorder, batchFlusher,
                boundedElasticScheduler,
                AdminRuntimeServices.fromProviders(
                        quotaServiceProvider,
                        budgetServiceProvider,
                        tpmServiceProvider,
                        aggregateMetricRecorderProvider));
    }

    ChatCompletionsOrchestrator(ClientAuthService clientAuthService,
                                ModelRouteResolver routeResolver,
                                ClientRateLimiter rateLimiter,
                                UpstreamChatClient upstreamClient,
                                GatewayProperties properties,
                                OperationalGateService operationalGateService,
                                ConcurrentRequestLimiter concurrentRequestLimiter,
                                CompletionRecorder completionRecorder,
                                BatchFlusher batchFlusher,
                                Scheduler boundedElasticScheduler,
                                AdminRuntimeServices adminRuntimeServices) {
        this.clientAuthService = clientAuthService;
        this.routeResolver = routeResolver;
        this.rateLimiter = rateLimiter;
        this.upstreamClient = upstreamClient;
        this.operationalGateService = operationalGateService;
        this.concurrentRequestLimiter = concurrentRequestLimiter;
        this.completionRecorder = completionRecorder;
        this.batchFlusher = batchFlusher;
        this.adminRuntimeServices = adminRuntimeServices;
        this.sharedBackend = properties.getSharedState().getBackend();
        this.boundedElasticScheduler = boundedElasticScheduler;
    }

    public Mono<ResponseEntity<?>> orchestrate(ChatCompletionsRequest request,
                                                  String authorization,
                                                  ServerWebExchange exchange) {
        String requestId = exchange.getAttributeOrDefault(RequestIdFilter.REQUEST_ID_ATTR, "unknown");
        Instant start = Instant.now();
        // Phase 1: Non-blocking pre-route (stays on event loop — no subscribeOn)
        return Mono.fromCallable(() -> {
            String rawToken = extractBearerToken(authorization);
            operationalGateService.preCheck(rawToken);
            ClientPrincipal principal = clientAuthService.authenticate(authorization);
            clientAuthService.authorizeModel(principal, request.model());
            ChatCompletionsRequest effectiveRequest = applyClientDefaults(request, principal);
            clientAuthService.validateRequestCapabilities(principal, effectiveRequest.streamEnabled(), effectiveRequest.maxTokens());
            ResolvedRoute route = routeResolver.resolve(request.model(), principal);
            clientAuthService.authorizeScene(principal, route.scene());
            return new PreRouteData(effectiveRequest, principal, route);
        })
        // Phase 2: Blocking pre-route (PG/Redis quota, budget, TPM reserve → boundedElastic)
        .flatMap(pr -> Mono.fromCallable(() -> {
            rateLimiter.check(pr.principal.clientId());
            setRateLimitHeaders(exchange, pr.principal.clientId());
            Instant now = Instant.now();
            if (adminRuntimeServices.quotaService() != null) {
                adminRuntimeServices.quotaService().checkDailyQuota(pr.principal, now);
                adminRuntimeServices.quotaService().checkMonthlyQuota(pr.principal, now);
            }
            if (adminRuntimeServices.budgetService() != null) {
                adminRuntimeServices.budgetService().checkDailyBudget(pr.principal, now);
                adminRuntimeServices.budgetService().checkMonthlyBudget(pr.principal, now);
            }
            long reservedTpmTokens = adminRuntimeServices.tpmService() != null
                    ? adminRuntimeServices.tpmService().reserveEstimatedTokens(pr.principal, pr.effectiveRequest, now) : 0L;
            return new WarmupResult(pr.effectiveRequest, pr.principal, pr.route, start, reservedTpmTokens);
        }).subscribeOn(sharedBackend == Backend.IN_MEMORY
                ? Schedulers.parallel() : boundedElasticScheduler))
        .onErrorResume(GatewayException.class, error -> {
            batchFlusher.submitBestEffort(() -> completionRecorder.recordRequestFailure(
                    CompletionRecorder.redact(null),
                    null,
                    request.model(),
                    null,
                    start,
                    "pre-route",
                    requestId,
                    error.getStatus().value(),
                    error.getCode()
            ));
            batchFlusher.submitBestEffort(() -> completionRecorder.recordPreRouteFailureObservability(
                    exchange,
                    request,
                    null,
                    request.model(),
                    start,
                    requestId,
                    error.getStatus().value(),
                    error.getMessage()
            ));
            batchFlusher.submitBestEffort(() -> {
                if (adminRuntimeServices.aggregateMetricRecorder() != null) {
                    adminRuntimeServices.aggregateMetricRecorder().recordFailureStatus(
                            requestId, error.getStatus().value(), Instant.now());
                }
            });
            return Mono.error(error);
        })
        .flatMap(wr -> {
            if (wr.effectiveRequest.streamEnabled()) {
                return orchestrateStream(wr.effectiveRequest, wr.principal, wr.route, exchange, wr.start, wr.reservedTpmTokens);
            }
            return orchestrateNonStream(wr.effectiveRequest, wr.principal, wr.route, exchange, wr.start, wr.reservedTpmTokens);
        });
    }

    /** Phase 1 result: non-blocking pre-route data (computed on event loop). */
    private record PreRouteData(
            ChatCompletionsRequest effectiveRequest,
            ClientPrincipal principal,
            ResolvedRoute route
    ) {}

    private record WarmupResult(
            ChatCompletionsRequest effectiveRequest,
            ClientPrincipal principal,
            ResolvedRoute route,
            Instant start,
            long reservedTpmTokens
    ) {}

    private Mono<ResponseEntity<?>> orchestrateStream(ChatCompletionsRequest effectiveRequest,
                                                       ClientPrincipal principal,
                                                       ResolvedRoute route,
                                                       ServerWebExchange exchange,
                                                       Instant start,
                                                       long reservedTpmTokens) {
        String requestId = exchange.getAttributeOrDefault(RequestIdFilter.REQUEST_ID_ATTR, "unknown");
        ChatCompletionsRequest correlatedRequest = withRequestId(effectiveRequest, requestId);
        AtomicLong streamPromptTokens = new AtomicLong(0L);
        AtomicLong streamCompletionTokens = new AtomicLong(0L);
        AtomicLong streamTotalTokens = new AtomicLong(0L);
        AtomicBoolean seenDone = new AtomicBoolean(false);
        AtomicBoolean seenBusinessChunk = new AtomicBoolean(false);
        AtomicBoolean interruptedAfterBusinessChunk = new AtomicBoolean(false);
        Flux<String> body = ConcurrencyLimitHelper.withConcurrencyLimitFlux(
                concurrentRequestLimiter,
                principal.clientId(),
                upstreamClient.streamWithFallback(
                        correlatedRequest,
                        route,
                        fallbackRouteId -> routeResolver.resolveFallback(correlatedRequest.model(), fallbackRouteId)
                ))
                .doOnNext(chunk -> {
                    String c = chunk;
                    if (c != null) {
                        if (c.contains("data: [DONE]")) {
                            seenDone.set(true);
                        } else if (c.contains("\"usage\"")) {
                            TokenExtractionHelper.captureStreamingUsage(
                                    c,
                                    streamPromptTokens,
                                    streamCompletionTokens,
                                    streamTotalTokens,
                                    correlatedRequest.model(),
                                    requestId,
                                    principal.clientId(),
                                    batchFlusher);
                        } else {
                            seenBusinessChunk.set(true);
                        }
                    }
                })
                .onErrorResume(error -> {
                    if (seenBusinessChunk.get() && !seenDone.get()) {
                        interruptedAfterBusinessChunk.set(true);
                        batchFlusher.submitBestEffort(() -> completionRecorder.recordRequestFailure(
                                CompletionRecorder.redact(principal.clientId()),
                                principal.clientId(),
                                correlatedRequest.model(),
                                route,
                                start,
                                "streaming",
                                requestId,
                                ErrorResponseMapper.statusForError(error),
                                ErrorResponseMapper.codeForError(error)
                        ));
                        batchFlusher.submitBestEffort(() -> completionRecorder.recordFailureObservability(
                                exchange,
                                correlatedRequest,
                                principal.clientId(),
                                correlatedRequest.model(),
                                route,
                                start,
                                "streaming",
                                requestId,
                                ErrorResponseMapper.statusForError(error),
                                ErrorResponseMapper.errorMessageForError(error),
                                buildStreamingErrorTraceSummary(seenDone.get(), streamPromptTokens.get(), streamCompletionTokens.get(), streamTotalTokens.get())
                        ));
                        if (adminRuntimeServices.tpmService() != null) adminRuntimeServices.tpmService().release(principal.clientId(), reservedTpmTokens, Instant.now());
                        return Flux.empty();
                    }
                    return Flux.error(error);
                })
                .concatWith(Flux.defer(() ->
                        seenDone.get() || interruptedAfterBusinessChunk.get() ? Flux.empty() : Flux.just("data: [DONE]\n\n")))
                .doOnComplete(() -> {
                    if (interruptedAfterBusinessChunk.get()) {
                        return;
                    }
                    long totalTokens = streamTotalTokens.get();
                    long promptTokens = streamPromptTokens.get();
                    long completionTokens = streamCompletionTokens.get();
                    long usageTokens = adminRuntimeServices.quotaService() != null
                            ? adminRuntimeServices.quotaService().resolveUsageTokensForStreaming(
                                totalTokens > 0 ? totalTokens : null,
                                correlatedRequest)
                            : totalTokens;
                    if (adminRuntimeServices.tpmService() != null) adminRuntimeServices.tpmService().reconcile(principal.clientId(), reservedTpmTokens, usageTokens, Instant.now());
                    long finalUsageTokens = usageTokens;
                    long finalPromptTokens = promptTokens;
                    long finalCompletionTokens = completionTokens;
                    String redactedClient = CompletionRecorder.redact(principal.clientId());
                    String clientId = principal.clientId();
                    Double costUsd = completionRecorder.calculateCostUsd(correlatedRequest.model(), finalPromptTokens, finalCompletionTokens);
                    String streamingTraceSummary = buildStreamingTraceSummary(
                            seenDone.get(),
                            promptTokens,
                            completionTokens,
                            totalTokens,
                            finalUsageTokens
                    );
                    batchFlusher.submitCritical(() -> {
                        recordStreamingUsageOnSuccess(principal, correlatedRequest, totalTokens, redactedClient);
                        recordCostOnSuccess(principal, correlatedRequest, route,
                                finalPromptTokens, finalCompletionTokens, redactedClient, "stream_cost_record_failed");
                    });
                    batchFlusher.submitBestEffort(() -> {
                        try {
                            completionRecorder.recordSuccessArtifacts(
                                    exchange,
                                    correlatedRequest,
                                    clientId,
                                    redactedClient,
                                    correlatedRequest.model(),
                                    route,
                                    start,
                                    "streaming",
                                    finalPromptTokens,
                                    finalCompletionTokens,
                                    finalUsageTokens,
                                    requestId,
                                    streamingTraceSummary,
                                    costUsd
                            );
                        } catch (RuntimeException e) {
                            log.warn("success_artifacts_record_failed requestId={} clientId={} model={} cause={}",
                                    requestId, redactedClient, correlatedRequest.model(), e.toString());
                        }
                        try {
                            recordAggregateSuccess(
                                    requestId,
                                    principal,
                                    route,
                                    correlatedRequest.model(),
                                    finalUsageTokens,
                                    costUsd);
                        } catch (RuntimeException e) {
                            log.warn("aggregate_success_record_failed requestId={} clientId={} model={} cause={}",
                                    requestId, redactedClient, correlatedRequest.model(), e.toString());
                        }
                    });
                })
                .doOnError(error -> {
                    batchFlusher.submitBestEffort(() -> completionRecorder.recordRequestFailure(
                            CompletionRecorder.redact(principal.clientId()),
                            principal.clientId(),
                            correlatedRequest.model(),
                            route,
                            start,
                            "streaming",
                            requestId,
                            ErrorResponseMapper.statusForError(error),
                            ErrorResponseMapper.codeForError(error)
                    ));
                    int stStatus = ErrorResponseMapper.statusForError(error);
                    batchFlusher.submitBestEffort(() -> completionRecorder.recordFailureObservability(
                            exchange,
                            correlatedRequest,
                            principal.clientId(),
                            correlatedRequest.model(),
                            route,
                            start,
                            "streaming",
                            requestId,
                            stStatus,
                            ErrorResponseMapper.errorMessageForError(error),
                            buildStreamingErrorTraceSummary(seenDone.get(), streamPromptTokens.get(), streamCompletionTokens.get(), streamTotalTokens.get())
                    ));
                    int stFailStatus = stStatus;
                    batchFlusher.submitBestEffort(() -> recordAggregateFailure(requestId, stFailStatus));
                    if (adminRuntimeServices.tpmService() != null) adminRuntimeServices.tpmService().release(principal.clientId(), reservedTpmTokens, Instant.now());
                })
                .doOnCancel(() -> {
                    if (adminRuntimeServices.tpmService() != null) adminRuntimeServices.tpmService().release(principal.clientId(), reservedTpmTokens, Instant.now());
                });
        return Mono.just(ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body((Object) body));
    }

    private Mono<ResponseEntity<?>> orchestrateNonStream(ChatCompletionsRequest effectiveRequest,
                                                           ClientPrincipal principal,
                                                           ResolvedRoute route,
                                                           ServerWebExchange exchange,
                                                           Instant start,
                                                           long reservedTpmTokens) {
        String requestId = exchange.getAttributeOrDefault(RequestIdFilter.REQUEST_ID_ATTR, "unknown");
        ChatCompletionsRequest correlatedRequest = withRequestId(effectiveRequest, requestId);
        return ConcurrencyLimitHelper.withConcurrencyLimitMono(
                concurrentRequestLimiter,
                principal.clientId(),
                upstreamClient.completeWithFallback(correlatedRequest, route,
                        fallbackRouteId -> routeResolver.resolveFallback(correlatedRequest.model(), fallbackRouteId))
        )
                .flatMap(resp -> {
                    long usageTokens = adminRuntimeServices.quotaService() != null
                            ? adminRuntimeServices.quotaService().resolveUsageTokensForResponse(resp, correlatedRequest)
                            : TokenExtractionHelper.extractTotalTokens(resp);
                    long rawPrompt = TokenExtractionHelper.extractPromptTokens(resp);
                    long rawCompletion = TokenExtractionHelper.extractCompletionTokens(resp, usageTokens);
                    // Backward compat: when upstream only reports total_tokens (no split),
                    // attribute all to prompt so cost is not zero
                    final long promptTokens;
                    final long completionTokens;
                    if (rawPrompt == 0 && rawCompletion == 0 && usageTokens > 0) {
                        promptTokens = usageTokens;
                        completionTokens = 0L;
                    } else {
                        promptTokens = rawPrompt;
                        completionTokens = rawCompletion;
                    }
                    if (adminRuntimeServices.tpmService() != null) adminRuntimeServices.tpmService().reconcile(principal.clientId(), reservedTpmTokens, usageTokens, Instant.now());
                    long finalUsageTokens = usageTokens;
                    long finalPromptTokens = promptTokens;
                    long finalCompletionTokens = completionTokens;
                    String clientId = principal.clientId();
                    String redactedClient = CompletionRecorder.redact(clientId);
                    Double costUsd = completionRecorder.calculateCostUsd(correlatedRequest.model(), finalPromptTokens, finalCompletionTokens);
                    batchFlusher.submitCritical(() -> {
                        recordUsageOnSuccess(principal, asResponseMap(resp), correlatedRequest, redactedClient);
                        recordCostOnSuccess(principal, correlatedRequest, route,
                                finalPromptTokens, finalCompletionTokens, redactedClient, "cost_record_failed");
                    });
                    batchFlusher.submitBestEffort(() -> {
                        try {
                            completionRecorder.recordSuccessArtifacts(
                                    exchange,
                                    correlatedRequest,
                                    clientId,
                                    redactedClient,
                                    correlatedRequest.model(),
                                    route,
                                    start,
                                    "non-streaming",
                                    finalPromptTokens,
                                    finalCompletionTokens,
                                    finalUsageTokens,
                                    requestId,
                                    resp,
                                    costUsd
                            );
                        } catch (RuntimeException e) {
                            log.warn("success_artifacts_record_failed requestId={} clientId={} model={} cause={}",
                                    requestId, redactedClient, correlatedRequest.model(), e.toString());
                        }
                        try {
                            recordAggregateSuccess(
                                    requestId,
                                    principal,
                                    route,
                                    correlatedRequest.model(),
                                    finalUsageTokens,
                                    costUsd);
                        } catch (RuntimeException e) {
                            log.warn("aggregate_success_record_failed requestId={} clientId={} model={} cause={}",
                                    requestId, redactedClient, correlatedRequest.model(), e.toString());
                        }
                    });
                    return Mono.<ResponseEntity<?>>just(ResponseEntity.ok((Object) resp));
                })
                .doOnError(error -> {
                    int nsStatus = ErrorResponseMapper.statusForError(error);
                    batchFlusher.submitBestEffort(() -> completionRecorder.recordRequestFailure(
                            CompletionRecorder.redact(principal.clientId()),
                            principal.clientId(),
                            correlatedRequest.model(),
                            route,
                            start,
                            "non-streaming",
                            requestId,
                            nsStatus,
                            ErrorResponseMapper.codeForError(error)
                    ));
                    batchFlusher.submitBestEffort(() -> completionRecorder.recordFailureObservability(
                            exchange,
                            correlatedRequest,
                            principal.clientId(),
                            correlatedRequest.model(),
                            route,
                            start,
                            "non-streaming",
                            requestId,
                            nsStatus,
                            ErrorResponseMapper.errorMessageForError(error),
                            null
                    ));
                    batchFlusher.submitBestEffort(() -> recordAggregateFailure(requestId, nsStatus));
                    if (adminRuntimeServices.tpmService() != null) adminRuntimeServices.tpmService().release(principal.clientId(), reservedTpmTokens, Instant.now());
                });
    }

    private ChatCompletionsRequest applyClientDefaults(ChatCompletionsRequest request, ClientPrincipal principal) {
        if (principal.config() == null) {
            return request;
        }
        var defaults = principal.config().getDefaults();
        Double temperature = request.temperature() != null ? request.temperature() : defaults.getTemperature();
        Integer maxTokens = request.maxTokens() != null ? request.maxTokens() : defaults.getMaxTokens();
        return new ChatCompletionsRequest(
                request.model(),
                request.messages(),
                request.stream(),
                temperature,
                maxTokens,
                request.tools(),
                request.toolChoice(),
                request.responseFormat(),
                request.extras()
        );
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asResponseMap(Object response) {
        if (response instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private void recordStreamingUsageOnSuccess(ClientPrincipal principal,
                                               ChatCompletionsRequest request,
                                               long totalTokens,
                                               String redactedClient) {
        if (adminRuntimeServices.quotaService() == null) {
            return;
        }
        try {
            adminRuntimeServices.quotaService().recordStreamingUsageOnSuccess(
                    principal,
                    request,
                    totalTokens > 0 ? totalTokens : null,
                    Instant.now());
        } catch (RuntimeException e) {
            log.warn("stream_usage_record_failed clientId={} model={} cause={}",
                    redactedClient, request.model(), e.toString());
        }
    }

    private void recordUsageOnSuccess(ClientPrincipal principal,
                                      Map<String, Object> response,
                                      ChatCompletionsRequest request,
                                      String redactedClient) {
        if (adminRuntimeServices.quotaService() == null) {
            return;
        }
        try {
            adminRuntimeServices.quotaService().recordUsage(principal, response, request, Instant.now());
        } catch (RuntimeException e) {
            log.warn("usage_record_failed clientId={} model={} cause={}",
                    redactedClient, request.model(), e.toString());
        }
    }

    private void recordCostOnSuccess(ClientPrincipal principal,
                                     ChatCompletionsRequest request,
                                     ResolvedRoute route,
                                     long promptTokens,
                                     long completionTokens,
                                     String redactedClient,
                                     String logKey) {
        if (adminRuntimeServices.budgetService() == null) {
            return;
        }
        try {
            adminRuntimeServices.budgetService().recordCostOnSuccess(
                    principal,
                    request,
                    route,
                    promptTokens,
                    completionTokens,
                    Instant.now());
        } catch (RuntimeException e) {
            log.warn("{} clientId={} model={} cause={}", logKey, redactedClient, request.model(), e.toString());
        }
    }

    private void recordAggregateSuccess(String requestId,
                                        ClientPrincipal principal,
                                        ResolvedRoute route,
                                        String model,
                                        long usageTokens,
                                        Double costUsd) {
        if (adminRuntimeServices.aggregateMetricRecorder() == null) {
            return;
        }
        adminRuntimeServices.aggregateMetricRecorder().recordSuccess(
                requestId,
                principal,
                route,
                model,
                usageTokens,
                costUsd,
                Instant.now());
    }

    private void recordAggregateFailure(String requestId, int status) {
        if (adminRuntimeServices.aggregateMetricRecorder() == null) {
            return;
        }
        adminRuntimeServices.aggregateMetricRecorder().recordFailureStatus(requestId, status, Instant.now());
    }

    private String buildStreamingTraceSummary(boolean doneSeen,
                                              long promptTokens,
                                              long completionTokens,
                                              long totalTokens,
                                              long usageTokens) {
        return "{" +
                "\"streaming\":true," +
                "\"doneSeen\":" + doneSeen + "," +
                "\"promptTokens\":" + promptTokens + "," +
                "\"completionTokens\":" + completionTokens + "," +
                "\"totalTokens\":" + totalTokens + "," +
                "\"usageTokens\":" + usageTokens +
                "}";
    }

    private String buildStreamingErrorTraceSummary(boolean doneSeen,
                                                   long promptTokens,
                                                   long completionTokens,
                                                   long totalTokens) {
        return "{" +
                "\"streaming\":true," +
                "\"doneSeen\":" + doneSeen + "," +
                "\"promptTokens\":" + promptTokens + "," +
                "\"completionTokens\":" + completionTokens + "," +
                "\"totalTokens\":" + totalTokens +
                "}";
    }

    private ChatCompletionsRequest withRequestId(ChatCompletionsRequest request, String requestId) {
        Map<String, Object> enrichedExtras = new java.util.LinkedHashMap<>(request.extras());
        enrichedExtras.put(ChatCompletionsRequest.GATEWAY_REQUEST_ID_EXTRA, requestId);
        return new ChatCompletionsRequest(
                request.model(),
                request.messages(),
                request.stream(),
                request.temperature(),
                request.maxTokens(),
                request.tools(),
                request.toolChoice(),
                request.responseFormat(),
                enrichedExtras
        );
    }

    // ===== Rate limit response headers =====

    private void setRateLimitHeaders(ServerWebExchange exchange, String clientId) {
        try {
            RateLimitStatus status = rateLimiter.getCurrentStatus(clientId);
            if (status != null) {
                exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(status.limit()));
                exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(status.remaining()));
                exchange.getResponse().getHeaders().set("X-RateLimit-Reset", String.valueOf(status.resetEpochSeconds()));
            }
        } catch (Exception e) {
            // Non-critical path: header setting should never fail the request
            log.warn("rate_limit_headers_failed clientId={} cause={}", CompletionRecorder.redact(clientId), e.toString());
        }
    }

}
