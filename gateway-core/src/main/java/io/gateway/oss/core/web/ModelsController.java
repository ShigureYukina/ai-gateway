package io.gateway.oss.core.web;

import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.security.UserAccountService;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v1/models")
public class ModelsController {

    private static final Logger log = LoggerFactory.getLogger(ModelsController.class);

    private final UserAccountService userAccountService;
    private final ClientAuthService clientAuthService;
    private final ModelListProvider modelListProvider;

    public ModelsController(ObjectProvider<UserAccountService> userAccountServiceProvider,
                            ObjectProvider<ClientAuthService> clientAuthServiceProvider,
                            ObjectProvider<ModelListProvider> modelListProviderProvider) {
        this.userAccountService = userAccountServiceProvider.getIfAvailable();
        this.clientAuthService = clientAuthServiceProvider.getIfAvailable();
        this.modelListProvider = modelListProviderProvider.getIfAvailable();
    }

    @Operation(summary = "List available models (OpenAI-compatible)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Model list",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ModelsListResponse.class)))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ModelsListResponse> listModels(
            @RequestParam(name = "provider", required = false) String provider,
            @RequestParam(name = "model", required = false) String model,
            ServerWebExchange exchange) {

        Set<String> keyAllowedModels = resolveKeyAllowedModels(exchange);

        // ModelListProvider is optional (from gateway-admin); without it return empty list
        List<ModelObject> data;
        boolean adminDegraded = false;
        if (modelListProvider != null && modelListProvider.hasData()) {
            data = modelListProvider.buildModels(provider, model);
        } else {
            data = List.of();
            adminDegraded = (modelListProvider == null);
        }
        if (keyAllowedModels != null && !keyAllowedModels.isEmpty()) {
            data = data.stream()
                    .filter(m -> keyAllowedModels.contains(m.executionId() != null ? m.executionId() : m.id()))
                    .toList();
        }
        if (adminDegraded) {
            return ResponseEntity.ok()
                    .header("X-Degraded", "admin-unavailable")
                    .body(new ModelsListResponse("list", data));
        }
        return ResponseEntity.ok(new ModelsListResponse("list", data));
    }

    private Set<String> resolveKeyAllowedModels(ServerWebExchange exchange) {
        if (userAccountService == null || clientAuthService == null) return null;
        if (exchange == null) return null;
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null) return null;
        try {
            ClientPrincipal principal = clientAuthService.authenticate(authHeader);
            if (principal == null || principal.username() == null) {
                throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication failed");
            }
            return principal.keyAllowedModels();
        } catch (GatewayException e) {
            log.debug("models_list_auth_fallback code={} message={}", e.getCode(), e.getMessage());
            // /v1/models 是公开列表接口：带无效鉴权时回退为公开列表，
            // 仅在鉴权成功时按 keyAllowedModels 做收窄过滤。
            return null;
        }
    }


    @Schema(description = "OpenAI-compatible model list response")
    public record ModelsListResponse(
            @Schema(example = "list") String object,
            @Schema(implementation = ModelObject.class) List<ModelObject> data
    ) {
    }

    @Schema(description = "OpenAI-compatible model object")
    public record ModelObject(
            @Schema(example = "gpt-4o-mini") String id,
            @JsonProperty("execution_id") String executionId,
            @JsonProperty("canonical_id") String canonicalId,
            @JsonProperty("source_type") String sourceType,
            @Schema(example = "model") String object,
            @Schema(example = "0") long created,
            @Schema(example = "openai") String owned_by,
            @Schema(description = "Reserved for future use") List<Object> permission,
            @JsonProperty("context_length") int contextLength,
            List<String> capabilities,
            Map<String, Object> pricing,
            String status,
            Map<String, Object> metadata
    ) {
    }

}
