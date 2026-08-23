package io.gateway.oss.admin.web;

import io.gateway.oss.admin.config.audit.ConfigVersionService;
import io.gateway.oss.core.config.DynamicConfigService;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.SystemConfigManager;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.web.support.ConfigMaskingSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * 配置审计、版本查询与回滚的 Admin/Internal API。
 * <p>
 * 所有端点需要 Admin RBAC 保护。
 * </p>
 */
@RestController
@RequestMapping("/internal/config")
public class ConfigAuditController extends AdminBaseController {

    private final AuditQueryService auditQueryService;
    private final ConfigVersionService configVersionService;
    private final ConfigSnapshotAssembler configSnapshotAssembler;
    private final ConfigRollbackApplier configRollbackApplier;

    public ConfigAuditController(ClientAuthService clientAuthService,
                                 ConfigVersionService configVersionService,
                                 DynamicConfigService dynamicConfigService,
                                 SystemConfigManager systemConfigManager,
                                 GatewayConfigView gatewayConfigView,
                                 ObjectMapper objectMapper,
                                 AuditQueryService auditQueryService) {
        super(clientAuthService);
        this.auditQueryService = auditQueryService;
        this.configVersionService = configVersionService;
        ConfigMaskingSupport maskingSupport = new ConfigMaskingSupport(objectMapper);
        this.configSnapshotAssembler = new ConfigSnapshotAssembler(gatewayConfigView, maskingSupport);
        this.configRollbackApplier = new ConfigRollbackApplier(dynamicConfigService, systemConfigManager, objectMapper);
    }

    @GetMapping("/audit")
    public Mono<AuditResponse> audit(
            ServerWebExchange exchange,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(required = false) String configType,
            @RequestParam(required = false) String configKey,
            @RequestParam(required = false) String operator,
            @RequestParam(defaultValue = "100") int limit) {
        requireAdminAccess(exchange);
        return auditQueryService.audit(configType, configKey, operator, limit);
    }

    @GetMapping("/audit-center")
    public Mono<UnifiedAuditCenterResponse> auditCenter(
            ServerWebExchange exchange,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String routeId,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String configType,
            @RequestParam(required = false) String configKey,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "100") int limit) {
        requireAdminAccess(exchange);
        return auditQueryService.auditCenter(
                eventType,
                from,
                to,
                clientId,
                model,
                status,
                provider,
                routeId,
                scene,
                requestId,
                configType,
                configKey,
                operator,
                action,
                limit
        );
    }

    @GetMapping("/versions/{configType}/{configKey}")
    public Mono<VersionsResponse> versions(
            ServerWebExchange exchange,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String configType,
            @PathVariable String configKey) {
        requireAdminAccess(exchange);
        return auditQueryService.versions(configType, configKey);
    }

    @PostMapping("/rollback/{configType}/{configKey}/{versionNumber}")
    public Mono<ResponseEntity<?>> rollback(
            ServerWebExchange exchange,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String configType,
            @PathVariable String configKey,
            @PathVariable int versionNumber) {
        requireAdminAccess(exchange);

        return configVersionService.rollbackTo(configType, configKey, versionNumber)
                .switchIfEmpty(Mono.error(new io.gateway.oss.core.error.GatewayException(
                        HttpStatus.NOT_FOUND, "version_not_found",
                        "Version " + versionNumber + " not found for " + configType + ":" + configKey)))
                .flatMap(jsonValue -> configRollbackApplier.applyRollback(configType, configKey, jsonValue)
                        .thenReturn(ResponseEntity.ok(new RollbackResponse(
                                configType, configKey, versionNumber, Instant.now()))));
    }

    @GetMapping("/snapshot")
    public SnapshotResponse snapshot(
            ServerWebExchange exchange,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(exchange);
        return configSnapshotAssembler.buildSnapshot();
    }
}
