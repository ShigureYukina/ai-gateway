package io.gateway.oss.core.contract;

import io.gateway.oss.core.config.PricingConfig;
import reactor.core.publisher.Mono;

/**
 * model-publication 场景专用写入口契约。
 * <p>
 * 仅覆盖当前发布流程真实使用到的最小写能力：route / scene / pricing。
 * 不向其他 admin 写场景扩展。
 * </p>
 */
public interface ModelPublicationConfigWriter extends RouteConfigWriter, SceneConfigWriter {

    Mono<Void> saveSystemPricing(PricingConfig config);
}
