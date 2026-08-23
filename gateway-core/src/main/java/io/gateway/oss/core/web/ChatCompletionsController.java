package io.gateway.oss.core.web;

import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatCompletionsResponse;
import io.gateway.oss.core.dto.GatewayErrorResponse;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/chat/completions")
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final ChatCompletionsOrchestrator orchestrator;
    private final GatewayMetricsRecorder metricsRecorder;

    public ChatCompletionsController(ChatCompletionsOrchestrator orchestrator,
                                     GatewayMetricsRecorder metricsRecorder) {
        this.orchestrator = orchestrator;
        this.metricsRecorder = metricsRecorder;
    }

    @Operation(summary = "Proxy OpenAI-compatible chat completions")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChatCompletionsResponse.class))),
            @ApiResponse(responseCode = "200", description = "SSE Stream", content = @Content(mediaType = "text/event-stream")),
            @ApiResponse(responseCode = "400", description = "Bad request", content = @Content(schema = @Schema(implementation = GatewayErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = GatewayErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = GatewayErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content(schema = @Schema(implementation = GatewayErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Maintenance mode", content = @Content(schema = @Schema(implementation = GatewayErrorResponse.class))),
            @ApiResponse(responseCode = "504", description = "Upstream timeout", content = @Content(schema = @Schema(implementation = GatewayErrorResponse.class)))
    })
    @PostMapping(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Mono<ResponseEntity<?>> completions(@RequestHeader(name = "Authorization", required = false) String authorization,
                                                 @Valid @RequestBody ChatCompletionsRequest request,
                                                 ServerWebExchange exchange) {
        metricsRecorder.markRequestStart(exchange);
        return orchestrator.orchestrate(request, authorization, exchange);
    }
}
