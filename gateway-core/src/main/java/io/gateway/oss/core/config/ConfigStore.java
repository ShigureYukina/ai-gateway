package io.gateway.oss.core.config;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 配置持久化存储接口。
 * <p>
 * 每条配置由 {@code configType}（如 providers / routes / clients / system）和
 * {@code key}（如 provider name / route id / client key / "limit"）唯一标识，
 * 值为 JSON 字符串。
 * </p>
 * <p>
 * Redis 实现使用 Hash 结构：Redis key = {@code {prefix}:config:{configType}}，
 * hash field = {@code key}，hash value = JSON 字符串。
 * </p>
 */
public interface ConfigStore {

    /**
     * 保存一条配置。
     *
     * @param configType 配置类型（providers / routes / clients / system）
     * @param key        配置项唯一标识
     * @param jsonValue  JSON 序列化后的配置值
     */
    Mono<Void> save(String configType, String key, String jsonValue);

    /**
     * 加载一条配置。
     *
     * @return 配置 JSON 字符串，不存在时返回 empty
     */
    Mono<String> load(String configType, String key);

    /**
     * 删除一条配置。
     */
    Mono<Void> delete(String configType, String key);

    /**
     * 加载某类型下的全部配置。
     *
     * @return key → JSON 映射，类型不存在时返回空 Map
     */
    Mono<Map<String, String>> loadAll(String configType);

    /**
     * 仅当 key 不存在，或旧值已过期时才写入新值。
     */
    Mono<Boolean> saveIfAbsentOrReplaceExpired(String configType,
                                               String key,
                                               String jsonValue,
                                               Duration ttl);
}
