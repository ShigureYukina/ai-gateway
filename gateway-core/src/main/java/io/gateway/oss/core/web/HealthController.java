package io.gateway.oss.core.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/healthz")
@Tag(name = "System", description = "Health check")
@SecurityRequirements
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final HealthEndpoint healthEndpoint;

    public HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping
    @Operation(summary = "Combined health check endpoint")
    public Mono<Map<String, Object>> health() {
        return buildHealthResponse();
    }

    @GetMapping("/live")
    @Operation(summary = "Liveness probe - process is alive")
    public Map<String, Object> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/ready")
    @Operation(summary = "Readiness probe - process can serve traffic")
    public Mono<Map<String, Object>> ready() {
        return buildHealthResponse();
    }

    private Mono<Map<String, Object>> buildHealthResponse() {
        return Mono.<Map<String, Object>>fromCallable(() -> {
                    HealthComponent component = healthEndpoint.health();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", component.getStatus().getCode());
                    if (component instanceof Health health && !health.getDetails().isEmpty()) {
                        result.put("details", health.getDetails());
                    }
                    return result;
                })
                // HealthEndpoint 聚合会触发阻塞式 JDBC/Redis 健康检查，需避开 WebFlux reactor 请求线程。
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("Health check failed", e);
                    return Mono.just(Map.of("status", "DOWN", "error", "Health check internal error"));
                });
    }
}
