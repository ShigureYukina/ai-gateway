package io.gateway.oss.bootstrap;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压测诊断专用 echo upstream server — 运行在独立端口，
 * 替代 Node.js mock 进程，消除上游 mock 作为瓶颈变量的可能性。
 * <p>
 * 仅在 @Profile("stress-test") 时激活。
 * 端口由 ECHO_DIAGNOSTIC_PORT 环境变量指定，默认 18089。
 */
@Component
@Profile("stress-test")
public class EchoDiagnosticServer {

    private static final Logger log = LoggerFactory.getLogger(EchoDiagnosticServer.class);
    private static final int DEFAULT_PORT = 18089;

    private static final String MODELS_JSON =
            "{\"object\":\"list\",\"data\":["
                    + "{\"id\":\"gpt-4o-mini\",\"object\":\"model\",\"created\":1700000000,\"owned_by\":\"mock\"},"
                    + "{\"id\":\"gpt-4o\",\"object\":\"model\",\"created\":1700000000,\"owned_by\":\"mock\"}"
                    + "]}";

    private final AtomicInteger requestCount = new AtomicInteger(0);
    private DisposableServer server;

    @PostConstruct
    public void start() {
        int port = parsePort();
        log.info("[EchoDiagnostic] Starting in-JVM echo upstream on port {}", port);

        server = HttpServer.create()
                .port(port)
                .route(routes -> routes
                        .get("/v1/models", (req, res) ->
                                res.header("Content-Type", "application/json")
                                        .sendString(Mono.just(MODELS_JSON)))
                        .post("/v1/chat/completions", (req, res) ->
                                req.receive()
                                        .aggregate()
                                        .asString()
                                        .flatMap(body -> {
                                            requestCount.incrementAndGet();
                                            boolean stream = body.contains("\"stream\":true")
                                                    || body.contains("\"stream\": true");
                                            String model = extractModel(body);
                                            long created = System.currentTimeMillis() / 1000;
                                            int pt = 10 + ThreadLocalRandom.current().nextInt(20);
                                            int ct = 5 + ThreadLocalRandom.current().nextInt(10);

                                            if (stream) {
                                                String chunk = "data: {"
                                                        + "\"id\":\"chatcmpl-echo\","
                                                        + "\"object\":\"chat.completion.chunk\","
                                                        + "\"created\":" + created + ','
                                                        + "\"model\":\"" + escapeJson(model) + "\","
                                                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello from echo!\"},\"finish_reason\":\"stop\"}]"
                                                        + "}\n\n"
                                                        + "data: [DONE]\n";
                                                return res
                                                        .header("Content-Type", "text/event-stream")
                                                        .header("Cache-Control", "no-cache")
                                                        .sendString(Mono.just(chunk))
                                                        .then();
                                            }

                                            String json = "{"
                                                    + "\"id\":\"chatcmpl-echo\","
                                                    + "\"object\":\"chat.completion\","
                                                    + "\"created\":" + created + ','
                                                    + "\"model\":\"" + escapeJson(model) + "\","
                                                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello from echo!\"},\"finish_reason\":\"stop\"}],"
                                                    + "\"usage\":{\"prompt_tokens\":" + pt + ",\"completion_tokens\":" + ct + ",\"total_tokens\":" + (pt + ct) + "}"
                                                    + "}";
                                            return res
                                                    .header("Content-Type", "application/json")
                                                    .sendString(Mono.just(json))
                                                    .then();
                                        }))
                )
                .bindNow(Duration.ofSeconds(5));

        log.info("[EchoDiagnostic] In-JVM echo upstream listening on port {}", port);
    }

    @PreDestroy
    public void stop() {
        if (server != null && !server.isDisposed()) {
            log.info("[EchoDiagnostic] Shutting down echo upstream (handled {} requests)", requestCount.get());
            server.disposeNow(Duration.ofSeconds(3));
        }
    }

    public int getPort() {
        return server != null ? server.port() : -1;
    }

    public int getRequestCount() {
        return requestCount.get();
    }

    private static int parsePort() {
        String env = System.getenv("ECHO_DIAGNOSTIC_PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                log.warn("[EchoDiagnostic] Invalid ECHO_DIAGNOSTIC_PORT '{}', using default {}", env, DEFAULT_PORT);
            }
        }
        return DEFAULT_PORT;
    }

    private static String extractModel(String rawBody) {
        int idx = rawBody.indexOf("\"model\"");
        if (idx < 0) return "gpt-4o-mini";
        int colon = rawBody.indexOf(':', idx + 7);
        if (colon < 0) return "gpt-4o-mini";
        int start = rawBody.indexOf('"', colon + 1);
        if (start < 0) return "gpt-4o-mini";
        int end = rawBody.indexOf('"', start + 1);
        if (end < 0) return "gpt-4o-mini";
        return rawBody.substring(start + 1, end);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
