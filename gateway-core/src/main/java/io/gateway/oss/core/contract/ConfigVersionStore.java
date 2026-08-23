package io.gateway.oss.core.contract;

import reactor.core.publisher.Mono;

/**
 * Optional config version store — implemented by gateway-admin's ConfigVersionService.
 * When absent from the classpath, version snapshots are silently skipped.
 */
public interface ConfigVersionStore {

    Mono<Void> snapshotBeforeChange(String configType, String configKey,
                                    String currentJson, String operator);
}
