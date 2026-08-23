package io.gateway.oss.core.contract;

import io.gateway.oss.core.config.ProviderConfig;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Provider 子域专用写入接口。
 */
public interface ProviderConfigWriter {

    Mono<Void> saveProvider(String name, ProviderConfig config);

    Mono<Void> deleteProvider(String name);

    List<String> getRouteReferences(String providerName);
}
