package io.gateway.oss.core.contract;

import io.gateway.oss.core.config.ClientConfig;
import reactor.core.publisher.Mono;

/**
 * Client 子域专用写入接口。
 */
public interface ClientConfigWriter {

    Mono<Void> saveClient(String key, ClientConfig config);

    Mono<Void> deleteClient(String key);
}
