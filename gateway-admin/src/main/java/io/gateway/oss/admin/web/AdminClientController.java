package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.contract.ClientCatalogView;
import io.gateway.oss.core.contract.ClientConfigView;
import io.gateway.oss.core.contract.ClientConfigWriter;
import io.gateway.oss.core.security.ClientAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminClientController extends AdminBaseController {

    private final ClientCatalogView clientCatalogView;
    private final ClientConfigWriter clientConfigWriter;

    public AdminClientController(ClientAuthService clientAuthService,
                                 ClientCatalogView clientCatalogView,
                                 ClientConfigWriter clientConfigWriter) {
        super(clientAuthService);
        this.clientCatalogView = clientCatalogView;
        this.clientConfigWriter = clientConfigWriter;
    }

    @GetMapping("/clients")
    public ClientsResponse clients(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        Map<String, ClientView> result = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends ClientConfigView> entry : clientCatalogView.getClients().entrySet()) {
            ClientConfigView cfg = entry.getValue();
            result.put(maskClientKey(entry.getKey()), new ClientView(
                    cfg.isEnabled(),
                    cfg.getAllowedModels(),
                    cfg.getAllowedScenes(),
                    cfg.getModelScenes(),
                    new ClientDefaultsView(
                            cfg.getDefaults().getScene(),
                            cfg.getDefaults().getTemperature(),
                            cfg.getDefaults().getMaxTokens()
                    ),
                    new ClientCapabilitiesView(cfg.getCapabilities().isStreaming()),
                    new ClientLimitsView(
                            cfg.getLimits().getMaxTokens(),
                            cfg.getLimits().getDailyTokens(),
                            cfg.getLimits().getDailyCost(),
                            cfg.getLimits().getMonthlyTokens(),
                            cfg.getLimits().getMonthlyCost()
                    )
            ));
        }
        return new ClientsResponse(Instant.now(), result);
    }

    @PutMapping("/clients/{key}")
    public Mono<ResponseEntity<ClientConfig>> putClient(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String key,
            @Valid @RequestBody ClientConfig config) {
        requireAdminAccess(authorizationHeader);
        boolean isNew = !clientCatalogView.getClients().containsKey(key);
        return clientConfigWriter.saveClient(key, config)
                .then(Mono.fromCallable(() -> {
                    int status = isNew ? 201 : 200;
                    return ResponseEntity.status(status).body(config);
                }));
    }

    @DeleteMapping("/clients/{key}")
    public Mono<ResponseEntity<Void>> deleteClient(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String key) {
        requireAdminAccess(authorizationHeader);
        if (!clientCatalogView.getClients().containsKey(key)) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).<Void>build());
        }
        return clientConfigWriter.deleteClient(key)
                .then(Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT).<Void>build()));
    }

    // ─── response records ───

    public record ClientsResponse(
            Instant generatedAt,
            Map<String, ClientView> clients
    ) {
    }

    public record ClientView(
            boolean enabled,
            java.util.Set<String> allowedModels,
            java.util.Set<String> allowedScenes,
            Map<String, String> modelScenes,
            ClientDefaultsView defaults,
            ClientCapabilitiesView capabilities,
            ClientLimitsView limits
    ) {
    }

    public record ClientDefaultsView(
            String scene,
            Double temperature,
            Integer maxTokens
    ) {
    }

    public record ClientCapabilitiesView(
            boolean streaming
    ) {
    }

    public record ClientLimitsView(
            Integer maxTokens,
            Long dailyTokens,
            java.math.BigDecimal dailyCost,
            Long monthlyTokens,
            java.math.BigDecimal monthlyCost
    ) {
    }
}
