package io.gateway.oss.core.web;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.RequestLogService.RequestLogEntry;
import io.gateway.oss.core.observability.TraceRecord;
import io.gateway.oss.core.observability.TraceStore;
import io.gateway.oss.core.pricing.PricingResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Service
public class CompletionRecorder {

    private static final Logger log = LoggerFactory.getLogger(CompletionRecorder.class);
    private static final List<Pattern> TRACE_REDACTION_PATTERNS = List.of(
            Pattern.compile("(?i)(?:api[-_]?key|apikey|secret|token)\\s*[:=]\\s*['\\\"]?[\\w\\-]{16,}['\\\"]?"),
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
            Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9_\\-]{20,}")
    );

    private final GatewayMetricsRecorder metricsRecorder;
    private final RequestLogService requestLogService;
    private final TraceStore traceStore;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;
    private final PricingResolver pricingResolver;

    @Autowired
    public CompletionRecorder(GatewayMetricsRecorder metricsRecorder,
                              RequestLogService requestLogService,
                              TraceStore traceStore,
                              ObjectMapper objectMapper,
                              GatewayProperties properties,
                              ObjectProvider<PricingResolver> pricingResolverProvider) {
        this.metricsRecorder = metricsRecorder;
        this.requestLogService = requestLogService;
        this.traceStore = traceStore;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.pricingResolver = pricingResolverProvider == null ? null : pricingResolverProvider.getIfAvailable();
    }

    public CompletionRecorder(GatewayMetricsRecorder metricsRecorder,
                              RequestLogService requestLogService,
                              TraceStore traceStore,
                              ObjectMapper objectMapper,
                              GatewayProperties properties) {
        this(metricsRecorder, requestLogService, traceStore, objectMapper, properties, null);
    }

    public void recordSuccessArtifacts(ServerWebExchange exchange,
                                       Object request,
                                       String clientId,
                                       String redactedClient,
                                       String model,
                                       ResolvedRoute route,
                                       Instant start,
                                       String streamMode,
                                       long promptTokens,
                                       long completionTokens,
                                       long usageTokens,
                                       String requestId,
                                       Object responseOrStreamMode) {
        Double costUsd = calculateCostUsd(model, promptTokens, completionTokens);
        recordSuccessArtifacts(exchange, request, clientId, redactedClient, model, route, start,
                streamMode, promptTokens, completionTokens, usageTokens, requestId, responseOrStreamMode, costUsd);
    }

    public void recordSuccessArtifacts(ServerWebExchange exchange,
                                       Object request,
                                       String clientId,
                                       String redactedClient,
                                       String model,
                                       ResolvedRoute route,
                                       Instant start,
                                       String streamMode,
                                       long promptTokens,
                                       long completionTokens,
                                       long usageTokens,
                                       String requestId,
                                       Object responseOrStreamMode,
                                       Double costUsd) {
        Instant completedAt = Instant.now();
        long latencyMs = Duration.between(start, completedAt).toMillis();
        recordRequestLog(redactedClient, model, route, streamMode,
                promptTokens, completionTokens, usageTokens, requestId, clientId, completedAt, latencyMs, costUsd);
        recordSuccessObservability(exchange, request, clientId, redactedClient, model, route,
                streamMode, requestId, responseOrStreamMode, latencyMs, completedAt);
    }

    public void recordSuccessObservability(ServerWebExchange exchange,
                                           Object request,
                                           String clientId,
                                           String model,
                                           ResolvedRoute route,
                                           Instant start,
                                           String streamMode,
                                           long promptTokens,
                                           long completionTokens,
                                           long usageTokens,
                                           String requestId,
                                           Object responseOrStreamMode) {
        Instant completedAt = Instant.now();
        long latencyMs = Duration.between(start, completedAt).toMillis();
        recordSuccessObservability(exchange, request, clientId, redact(clientId), model, route,
                streamMode, requestId, responseOrStreamMode, latencyMs, completedAt);
    }

    private void recordSuccessObservability(ServerWebExchange exchange,
                                            Object request,
                                            String clientId,
                                            String redactedClient,
                                            String model,
                                            ResolvedRoute route,
                                            String streamMode,
                                            String requestId,
                                            Object responseOrStreamMode,
                                            long latencyMs,
                                            Instant completedAt) {
        metricsRecorder.recordSuccess(exchange, 200);
        accessLog(exchange, clientId, model, route.provider(), 200, latencyMs);
        captureTraceSafely(requestId, () -> captureTrace(
                null,
                null,
                null,
                requestId,
                redactedClient,
                model,
                route,
                200,
                streamMode,
                latencyMs,
                completedAt));
    }

    public void recordPreRouteFailureObservability(ServerWebExchange exchange,
                                                   Object request,
                                                   String clientId,
                                                   String model,
                                                   Instant start,
                                                   String requestId,
                                                   int status,
                                                   String errorMessage) {
        String redactedClient = redact(clientId);
        Instant completedAt = Instant.now();
        long latencyMs = Duration.between(start, completedAt).toMillis();
        metricsRecorder.recordFailure(exchange, status);
        accessLog(exchange, clientId, model, "unknown", status, latencyMs);
        captureTraceSafely(requestId, () -> captureTrace(
                request,
                errorMessage,
                errorMessage,
                requestId,
                redactedClient,
                model,
                null,
                status,
                "pre-route",
                latencyMs,
                completedAt));
    }

    public void recordFailureObservability(ServerWebExchange exchange,
                                           Object request,
                                           String clientId,
                                           String model,
                                           ResolvedRoute route,
                                           Instant start,
                                           String streamMode,
                                           String requestId,
                                           int status,
                                           String errorMessage,
                                           Object responseSummary) {
        String redactedClient = redact(clientId);
        Instant completedAt = Instant.now();
        long latencyMs = Duration.between(start, completedAt).toMillis();
        metricsRecorder.recordFailure(exchange, status);
        accessLog(exchange, clientId, model, route.provider(), status, latencyMs);
        captureTraceSafely(requestId, () -> captureTrace(
                request,
                responseSummary,
                errorMessage,
                requestId,
                redactedClient,
                model,
                route,
                status,
                streamMode,
                latencyMs,
                completedAt));
    }

    private void captureTraceSafely(String requestId, Runnable captureAction) {
        try {
            captureAction.run();
        } catch (RuntimeException e) {
            log.warn("trace_capture_failed requestId={} cause={}", requestId, e.toString());
        }
    }

    public void recordRequestLog(ServerWebExchange exchange, String redactedClient, String model,
                                 ResolvedRoute route, Instant start, String streamMode,
                                   long promptTokens, long completionTokens, long usageTokens,
                                   String requestId, String clientKey) {
        Instant now = Instant.now();
        long latencyMs = Duration.between(start, now).toMillis();
        Double costUsd = calculateCostUsd(model, promptTokens, completionTokens);
        recordRequestLog(redactedClient, model, route, streamMode, promptTokens, completionTokens,
                usageTokens, requestId, clientKey, now, latencyMs, costUsd);
    }

    private void recordRequestLog(String redactedClient,
                                  String model,
                                  ResolvedRoute route,
                                  String streamMode,
                                  long promptTokens,
                                  long completionTokens,
                                  long usageTokens,
                                  String requestId,
                                  String clientKey,
                                  Instant timestamp,
                                  long latencyMs,
                                  Double costUsd) {
        requestLogService.record(new RequestLogEntry(
                requestId, redactedClient, clientKey, model, route.provider(), route.routeId(), route.scene(),
                200, latencyMs, timestamp, streamMode,
                usageTokens, promptTokens, completionTokens,
                costUsd, null
        ));
    }

    public void recordRequestFailure(String redactedClient,
                                     String clientKey,
                                     String model,
                                     ResolvedRoute route,
                                     Instant start,
                                     String streamMode,
                                     String requestId,
                                     int status,
                                     String errorCode) {
        Instant now = Instant.now();
        requestLogService.record(new RequestLogEntry(
                requestId, redactedClient, clientKey, model,
                route != null ? route.provider() : "unknown",
                route != null ? route.routeId() : "unknown",
                route != null ? route.scene() : "unknown",
                status, Duration.between(start, now).toMillis(), now, streamMode,
                null, null, null, null, errorCode
        ));
    }

    /**
     * Calculate cost using split prompt/completion tokens.
     */
    public Double calculateCostUsd(String model, long promptTokens, long completionTokens) {
        if (promptTokens <= 0 && completionTokens <= 0) {
            return 0.0;
        }
        if (pricingResolver != null) {
            PricingResolver.ResolvedPricing resolved = pricingResolver.resolve(model, null, null);
            BigDecimal inputPrice = resolved.inputUnitPrice();
            BigDecimal outputPrice = resolved.outputUnitPrice();
            if (inputPrice == null) {
                inputPrice = resolved.unitPrice();
            }
            if (outputPrice == null) {
                outputPrice = inputPrice != null ? inputPrice : resolved.unitPrice();
            }
            if (inputPrice == null || inputPrice.signum() <= 0) {
                return null;
            }
            BigDecimal cost = inputPrice.multiply(BigDecimal.valueOf(promptTokens))
                    .add(outputPrice.multiply(BigDecimal.valueOf(completionTokens)));
            return cost.doubleValue();
        }
        var pricing = properties.getPricing();
        BigDecimal inputPrice = null;
        BigDecimal outputPrice = null;
        if (pricing.getModels() != null && model != null) {
            var byModel = pricing.getModels().get(model);
            if (byModel != null) {
                inputPrice = byModel.getInputUnitPrice() != null ? byModel.getInputUnitPrice() : byModel.getUnitPrice();
                outputPrice = byModel.getOutputUnitPrice() != null ? byModel.getOutputUnitPrice() : byModel.getUnitPrice();
            }
        }
        if (inputPrice == null && pricing.getDefault() != null) {
            inputPrice = pricing.getDefault().getUnitPrice();
            outputPrice = inputPrice;
        }
        if (inputPrice == null || inputPrice.signum() <= 0) {
            return null;
        }
        if (outputPrice == null) {
            outputPrice = inputPrice;
        }
        BigDecimal cost = inputPrice.multiply(BigDecimal.valueOf(promptTokens))
                .add(outputPrice.multiply(BigDecimal.valueOf(completionTokens)));
        return cost.doubleValue();
    }

    /**
     * Backward-compatible overload: treats all tokens as prompt.
     */
    public Double calculateCostUsd(String model, long usageTokens) {
        return calculateCostUsd(model, usageTokens, 0L);
    }

    /**
     * Redact a sensitive value, showing only first 3 and last 2 characters.
     */
    public static String redact(String value) {
        if (value == null || value.length() < 6) return "***";
        return value.substring(0, 3) + "***" + value.substring(value.length() - 2);
    }

    void accessLog(ServerWebExchange exchange, String clientId, String model, String provider, int status, long latencyMs) {
        String requestId = String.valueOf(exchange.getAttributeOrDefault(RequestIdFilter.REQUEST_ID_ATTR, "unknown"));
        if (status >= 400 && log.isInfoEnabled()) {
            // 高频成功请求交由 request log / trace 等结构化通道留痕，
            // 控制台仅保留失败请求摘要，避免 stdout 被正常流量淹没。
            log.info("request_completed requestId={} clientId={} model={} provider={} status={} latencyMs={}",
                    requestId, redact(clientId), model, provider, status, latencyMs);
        }
    }

    private void captureTrace(Object request,
                              Object response,
                              String errorMessage,
                              String requestId,
                              String clientId,
                              String model,
                              ResolvedRoute route,
                              Integer status,
                              String streamMode,
                              Long latencyMs,
                              Instant completedAt) {
        if (!properties.getTracing().isEnabled()) return;
        double sampleRate = properties.getTracing().getSampleRate();
        if (sampleRate < 1.0 && (status == null || status < 400 || status >= 600)) {
            if (ThreadLocalRandom.current().nextDouble() >= sampleRate) return;
        }
        int maxSize = properties.getTracing().getMaxBodySize();
        try {
            String reqBody = request != null ? redactForTrace(truncate(objectMapper.writeValueAsString(request), maxSize)) : null;
            String respBody = response instanceof String s ? redactForTrace(truncate(s, maxSize))
                    : response != null ? redactForTrace(truncate(objectMapper.writeValueAsString(response), maxSize))
                    : errorMessage != null ? redactForTrace(truncate(errorMessage, maxSize)) : null;
            traceStore.save(new TraceRecord(
                    requestId,
                    clientId,
                    model,
                    route != null ? route.provider() : "unknown",
                    route != null ? route.routeId() : "unknown",
                    route != null ? route.scene() : "unknown",
                    status,
                    streamMode,
                    latencyMs,
                    errorMessage != null ? truncate(errorMessage, maxSize) : null,
                    reqBody,
                    respBody,
                    completedAt
            ));
        } catch (JsonProcessingException e) {
            log.warn("trace_serialization_failed requestId={} cause={}", requestId, e.toString());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String redactForTrace(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        String redacted = body;
        for (Pattern pattern : TRACE_REDACTION_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
        }
        return redacted;
    }
}
