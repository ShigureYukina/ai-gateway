package io.gateway.oss.core.contract;

import io.gateway.oss.core.config.RouteConfig;
import reactor.core.publisher.Mono;

/**
 * Route 子域专用写入接口。
 */
public interface RouteConfigWriter {

    Mono<Void> saveRoute(String id, RouteConfig config);

    Mono<Void> deleteRoute(String id);
}
