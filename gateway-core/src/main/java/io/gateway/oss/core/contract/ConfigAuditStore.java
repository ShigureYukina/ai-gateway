package io.gateway.oss.core.contract;

import reactor.core.publisher.Mono;

/**
 * Optional config audit store — implemented by gateway-admin's ConfigAuditService.
 * When absent from the classpath, audit logging is silently skipped.
 */
public interface ConfigAuditStore {

    Mono<Void> record(String configType, String configKey, String action,
                      String operator, String oldValue, String newValue);
}
