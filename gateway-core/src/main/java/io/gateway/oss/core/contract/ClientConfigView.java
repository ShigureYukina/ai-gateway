package io.gateway.oss.core.contract;

import io.gateway.oss.core.config.ClientCapabilities;
import io.gateway.oss.core.config.ClientDefaults;
import io.gateway.oss.core.config.ClientLimits;

import java.util.Map;
import java.util.Set;

/**
 * Client 只读配置视图。
 * <p>
 * 该接口面向鉴权、模型许可、默认场景等高频读取路径，
 * 只暴露读取所需数据，不定义更新语义。
 * </p>
 */
public interface ClientConfigView {

    boolean isEnabled();

    Set<String> getAllowedModels();

    Set<String> getAllowedScenes();

    Map<String, String> getModelScenes();

    ClientDefaults getDefaults();

    ClientCapabilities getCapabilities();

    ClientLimits getLimits();
}
