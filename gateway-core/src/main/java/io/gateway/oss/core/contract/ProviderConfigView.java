package io.gateway.oss.core.contract;

import java.time.Duration;
import java.util.List;

/**
 * Provider 只读配置视图。
 * <p>
 * 该接口用于收窄读取侧对具体配置实现类的直接依赖，
 * 仅暴露高频读取所需字段，不承担写入与校验职责。
 * </p>
 */
public interface ProviderConfigView {

    String getType();

    String getBaseUrl();

    String getApiKey();

    List<String> getKeys();

    List<Integer> getKeyWeights();

    Duration getTimeout();

    boolean isEnabled();

    List<String> getModels();
}
