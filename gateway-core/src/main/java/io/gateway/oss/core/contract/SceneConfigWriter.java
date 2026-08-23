package io.gateway.oss.core.contract;

import io.gateway.oss.core.config.SceneConfig;
import reactor.core.publisher.Mono;

/**
 * Scene 子域专用写入接口。
 */
public interface SceneConfigWriter {

    Mono<Void> saveScene(String id, SceneConfig config);

    Mono<Void> deleteScene(String id);
}
