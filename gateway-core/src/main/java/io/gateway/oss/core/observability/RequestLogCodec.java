package io.gateway.oss.core.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;

/**
 * 请求日志 JSON 编解码协作者。
 */
final class RequestLogCodec {

    private static final Logger log = LoggerFactory.getLogger(RequestLogCodec.class);

    private final ObjectMapper objectMapper;

    RequestLogCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    Mono<String> serialize(RequestLogService.RequestLogEntry entry) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(PersistedRequestLogEntry.from(entry)));
    }

    Mono<RequestLogService.RequestLogEntry> deserialize(String json) {
        return Mono.fromCallable(() -> deserializeNode(objectMapper.readTree(json)))
                .doOnError(error -> log.warn("request_log_deserialize_failed reason={}", error.getMessage()))
                .onErrorResume(JsonProcessingException.class, error -> Mono.empty());
    }

    private RequestLogService.RequestLogEntry deserializeNode(JsonNode node) throws JsonProcessingException {
        if (node.has("requestId")) {
            return objectMapper.treeToValue(node, RequestLogService.RequestLogEntry.class);
        }
        return PersistedRequestLogEntry.toDomain(node);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PersistedRequestLogEntry(
            String r,
            String c,
            String k,
            String m,
            String p,
            String rt,
            String s,
            Integer st,
            Long l,
            Instant t,
            String sm,
            Long u,
            Long pt,
            Long ct,
            Double usd,
            String e
    ) {
        private static PersistedRequestLogEntry from(RequestLogService.RequestLogEntry entry) {
            return new PersistedRequestLogEntry(
                    entry.requestId(),
                    entry.clientId(),
                    normalizeClientKey(entry),
                    entry.model(),
                    entry.provider(),
                    entry.routeId(),
                    entry.scene(),
                    entry.status(),
                    entry.latencyMs(),
                    entry.timestamp(),
                    entry.streamMode(),
                    entry.usageTokens(),
                    entry.promptTokens(),
                    entry.completionTokens(),
                    entry.costUsd(),
                    entry.errorMessage()
            );
        }

        private static RequestLogService.RequestLogEntry toDomain(JsonNode node) {
            return new RequestLogService.RequestLogEntry(
                    text(node, "r"),
                    text(node, "c"),
                    text(node, "k"),
                    text(node, "m"),
                    text(node, "p"),
                    text(node, "rt"),
                    text(node, "s"),
                    intValue(node, "st", 0),
                    longValue(node, "l", 0L),
                    instant(node, "t"),
                    text(node, "sm"),
                    longObject(node, "u"),
                    longObject(node, "pt"),
                    longObject(node, "ct"),
                    doubleObject(node, "usd"),
                    text(node, "e")
            );
        }

        private static String normalizeClientKey(RequestLogService.RequestLogEntry entry) {
            if (entry.clientKey() == null || entry.clientKey().equals(entry.clientId())) {
                return null;
            }
            return entry.clientKey();
        }

        private static String text(JsonNode node, String fieldName) {
            JsonNode value = node.get(fieldName);
            return value == null || value.isNull() ? null : value.asText();
        }

        private static Integer intValue(JsonNode node, String fieldName, int defaultValue) {
            JsonNode value = node.get(fieldName);
            return value == null || value.isNull() ? defaultValue : value.asInt(defaultValue);
        }

        private static long longValue(JsonNode node, String fieldName, long defaultValue) {
            JsonNode value = node.get(fieldName);
            return value == null || value.isNull() ? defaultValue : value.asLong(defaultValue);
        }

        private static Long longObject(JsonNode node, String fieldName) {
            JsonNode value = node.get(fieldName);
            return value == null || value.isNull() ? null : value.asLong();
        }

        private static Double doubleObject(JsonNode node, String fieldName) {
            JsonNode value = node.get(fieldName);
            return value == null || value.isNull() ? null : value.asDouble();
        }

        private static Instant instant(JsonNode node, String fieldName) {
            String value = text(node, fieldName);
            return value == null ? null : Instant.parse(value);
        }
    }
}
