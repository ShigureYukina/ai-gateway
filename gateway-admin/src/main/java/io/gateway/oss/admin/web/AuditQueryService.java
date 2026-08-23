package io.gateway.oss.admin.web;

import io.gateway.oss.admin.config.audit.ConfigAuditService;
import io.gateway.oss.admin.config.audit.ConfigVersionService;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.web.support.ConfigMaskingSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

@Service
public class AuditQueryService {

    private final ConfigAuditService configAuditService;
    private final ConfigVersionService configVersionService;
    private final ConfigMaskingSupport maskingSupport;
    private final UnifiedAuditAssembler unifiedAuditAssembler;

    AuditQueryService(ConfigAuditService configAuditService,
                      ConfigVersionService configVersionService,
                      RequestLogService requestLogService,
                      ObjectMapper objectMapper) {
        this.configAuditService = configAuditService;
        this.configVersionService = configVersionService;
        this.maskingSupport = new ConfigMaskingSupport(objectMapper);
        this.unifiedAuditAssembler = new UnifiedAuditAssembler(configAuditService, requestLogService, maskingSupport);
    }

    public Mono<AuditResponse> audit(String configType, String configKey, String operator, int limit) {
        return configAuditService.query(configType, configKey, operator, limit)
                .map(entries -> entries.stream()
                        .map(entry -> new ConfigAuditService.AuditEntry(
                                entry.auditId(),
                                entry.timestamp(),
                                entry.configType(),
                                maskingSupport.maskConfigKey(entry.configType(), entry.configKey()),
                                entry.action(),
                                entry.operator(),
                                maskingSupport.maskSensitiveJson(entry.oldValue(), entry.configType()),
                                maskingSupport.maskSensitiveJson(entry.newValue(), entry.configType())
                        ))
                        .toList())
                .map(entries -> new AuditResponse(Instant.now(), entries));
    }

    public Mono<UnifiedAuditCenterResponse> auditCenter(String eventType,
                                                        Instant from,
                                                        Instant to,
                                                        String clientId,
                                                        String model,
                                                        Integer status,
                                                        String provider,
                                                        String routeId,
                                                        String scene,
                                                        String requestId,
                                                        String configType,
                                                        String configKey,
                                                        String operator,
                                                        String action,
                                                        int limit) {
        int effectiveLimit = Math.max(1, Math.min(500, limit));
        return unifiedAuditAssembler.buildEntries(effectiveLimit * 2)
                .map(entries -> entries.stream()
                        .filter(entry -> matches(entry.eventType(), eventType))
                        .filter(entry -> from == null || (entry.timestamp() != null && !entry.timestamp().isBefore(from)))
                        .filter(entry -> to == null || (entry.timestamp() != null && entry.timestamp().isBefore(to)))
                        .filter(entry -> matches(entry.clientId(), clientId))
                        .filter(entry -> matches(entry.model(), model))
                        .filter(entry -> status == null || Objects.equals(entry.status(), status))
                        .filter(entry -> matches(entry.provider(), provider))
                        .filter(entry -> matches(entry.routeId(), routeId))
                        .filter(entry -> matches(entry.scene(), scene))
                        .filter(entry -> matches(entry.requestId(), requestId))
                        .filter(entry -> matches(entry.resourceType(), configType))
                        .filter(entry -> matches(entry.resourceId(), configKey))
                        .filter(entry -> matches(entry.actor(), operator))
                        .filter(entry -> matches(entry.action(), action))
                        .sorted(Comparator.comparing(UnifiedAuditEntry::timestamp, Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(effectiveLimit)
                        .toList())
                .map(entries -> new UnifiedAuditCenterResponse(Instant.now(), entries));
    }

    public Mono<VersionsResponse> versions(String configType, String configKey) {
        return configVersionService.getVersions(configType, configKey)
                .map(versions -> versions.stream()
                        .map(version -> new ConfigVersionService.ConfigVersion(
                                version.versionId(),
                                version.configType(),
                                maskingSupport.maskConfigKey(version.configType(), version.configKey()),
                                version.versionNumber(),
                                maskingSupport.maskSensitiveJson(version.jsonValue(), version.configType()),
                                version.createdAt(),
                                version.operator()
                        ))
                        .toList())
                .map(versions -> new VersionsResponse(
                        Instant.now(),
                        configType,
                        maskingSupport.maskConfigKey(configType, configKey),
                        versions
                ));
    }

    private boolean matches(String actual, String expected) {
        return expected == null || expected.equals(actual);
    }
}
