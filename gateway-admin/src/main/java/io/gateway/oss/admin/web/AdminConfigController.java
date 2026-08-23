package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.SystemConfigManager;
import io.gateway.oss.core.config.ConfigImportApplier;
import io.gateway.oss.core.security.ClientAuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminConfigController extends AdminBaseController {

    private final AdminConfigImportSupport importSupport;
    private final ConfigImportApplier configImportApplier;
    private final AdminConfigExportSupport exportSupport;
    private final SystemConfigManager systemConfigManager;

    public AdminConfigController(ClientAuthService clientAuthService,
                                 AdminConfigImportSupport importSupport,
                                 ConfigImportApplier configImportApplier,
                                 AdminConfigExportSupport exportSupport,
                                 SystemConfigManager systemConfigManager) {
        super(clientAuthService);
        this.importSupport = importSupport;
        this.configImportApplier = configImportApplier;
        this.exportSupport = exportSupport;
        this.systemConfigManager = systemConfigManager;
    }

    @PostMapping("/config/import")
    public Mono<ResponseEntity<Map<String, Object>>> importConfig(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            @RequestParam(value = "validateOnly", defaultValue = "false") boolean validateOnly,
            @Valid @RequestBody Map<String, Object> body) {
        requireAdminAccess(authorizationHeader);
        boolean effectiveDryRun = dryRun || validateOnly;

        AdminImportedConfig importedConfig = importSupport.parseImport(body);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("imported", importedConfig.importedCount());
        summary.put("status", "ok");
        summary.put("dryRun", effectiveDryRun);
        summary.put("validated", true);
        summary.put("applied", !effectiveDryRun);
        summary.put("errors", List.of());

        boolean hasSystemConfig = importedConfig.limitConfig() != null
                || importedConfig.resilienceConfig() != null
                || importedConfig.pricingConfig() != null
                || importedConfig.operationalConfig() != null;
        summary.put("pendingRestart", hasSystemConfig ? systemConfigManager.getPendingSystemKeys() : List.of());

        if (effectiveDryRun) {
            return Mono.just(ResponseEntity.ok(summary));
        }

        return configImportApplier.apply(
                        importedConfig.providers(),
                        importedConfig.routes(),
                        importedConfig.scenes(),
                        importedConfig.clients(),
                        importedConfig.limitConfig(),
                        importedConfig.resilienceConfig(),
                        importedConfig.pricingConfig(),
                        importedConfig.operationalConfig())
                .thenReturn(ResponseEntity.ok(summary));
    }

    @GetMapping("/config/export")
    public Mono<ResponseEntity<Map<String, Object>>> exportConfig(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        return Mono.just(ResponseEntity.ok(exportSupport.buildExport()));
    }
}
