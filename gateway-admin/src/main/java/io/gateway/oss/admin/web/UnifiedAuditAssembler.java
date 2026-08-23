package io.gateway.oss.admin.web;

import io.gateway.oss.admin.config.audit.ConfigAuditService;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.web.support.ConfigMaskingSupport;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Stream;

/**
 * 负责组装统一审计中心所需的聚合视图。
 */
class UnifiedAuditAssembler {

    private final ConfigAuditService configAuditService;
    private final RequestLogService requestLogService;
    private final ConfigMaskingSupport maskingSupport;

    UnifiedAuditAssembler(ConfigAuditService configAuditService,
                          RequestLogService requestLogService,
                          ConfigMaskingSupport maskingSupport) {
        this.configAuditService = configAuditService;
        this.requestLogService = requestLogService;
        this.maskingSupport = maskingSupport;
    }

    public Mono<List<UnifiedAuditEntry>> buildEntries(int limit) {
        return configAuditService.getRecent(limit)
                .map(configEntries -> {
                    List<UnifiedAuditEntry> configAuditEntries = configEntries.stream()
                            .map(entry -> new UnifiedAuditEntry(
                                    "config_audit",
                                    entry.timestamp(),
                                    entry.operator(),
                                    entry.configType(),
                                    maskingSupport.maskConfigKey(entry.configType(), entry.configKey()),
                                    entry.action(),
                                    "success",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null
                            ))
                            .toList();

                    List<UnifiedAuditEntry> requestEntries = requestLogService.getRecent(limit).stream()
                            .map(entry -> new UnifiedAuditEntry(
                                    "request_log",
                                    entry.timestamp(),
                                    entry.clientId(),
                                    "request",
                                    entry.requestId(),
                                    "chat.completions",
                                    entry.status() >= 400 ? "failure" : "success",
                                    entry.errorMessage(),
                                    entry.requestId(),
                                    entry.clientId(),
                                    entry.model(),
                                    entry.provider(),
                                    entry.routeId(),
                                    entry.scene(),
                                    entry.status(),
                                    entry.latencyMs()
                            ))
                            .toList();

                    return Stream.concat(configAuditEntries.stream(), requestEntries.stream()).toList();
                });
    }
}
