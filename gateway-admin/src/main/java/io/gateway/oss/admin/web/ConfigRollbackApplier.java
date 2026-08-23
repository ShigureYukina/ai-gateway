package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.DynamicConfigService;
import io.gateway.oss.core.config.LimitConfig;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.ProviderConfig;
import io.gateway.oss.core.config.ResilienceConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.config.SceneConfig;
import io.gateway.oss.core.contract.SystemConfigManager;
import io.gateway.oss.core.error.GatewayException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

/**
 * 负责将版本数据反序列化并应用到对应配置存储。
 */
class ConfigRollbackApplier {

    private static final Logger log = LoggerFactory.getLogger(ConfigRollbackApplier.class);

    private final DynamicConfigService dynamicConfigService;
    private final SystemConfigManager systemConfigManager;
    private final ObjectMapper objectMapper;

    ConfigRollbackApplier(DynamicConfigService dynamicConfigService,
                          SystemConfigManager systemConfigManager,
                          ObjectMapper objectMapper) {
        this.dynamicConfigService = dynamicConfigService;
        this.systemConfigManager = systemConfigManager;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> applyRollback(String configType, String configKey, String jsonValue) {
        try {
            return switch (configType) {
                case DynamicConfigService.TYPE_PROVIDERS -> {
                    ProviderConfig config = objectMapper.readValue(jsonValue, ProviderConfig.class);
                    yield dynamicConfigService.saveProvider(configKey, config);
                }
                case DynamicConfigService.TYPE_ROUTES -> {
                    RouteConfig config = objectMapper.readValue(jsonValue, RouteConfig.class);
                    yield dynamicConfigService.saveRoute(configKey, config);
                }
                case DynamicConfigService.TYPE_SCENES -> {
                    SceneConfig config = objectMapper.readValue(jsonValue, SceneConfig.class);
                    yield dynamicConfigService.saveScene(configKey, config);
                }
                case DynamicConfigService.TYPE_CLIENTS -> {
                    ClientConfig config = objectMapper.readValue(jsonValue, ClientConfig.class);
                    yield dynamicConfigService.saveClient(configKey, config);
                }
                case DynamicConfigService.TYPE_SYSTEM -> applySystemRollback(configKey, jsonValue);
                default -> Mono.error(new GatewayException(
                        HttpStatus.BAD_REQUEST, "invalid_config_type",
                        "Unknown config type: " + configType));
            };
        } catch (JsonProcessingException e) {
            log.warn("rollback_deserialize_failed config_type={} config_key={} reason={}", configType, configKey, e.getMessage());
            return Mono.error(new GatewayException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "rollback_failed",
                    "Failed to deserialize version data"));
        }
    }

    private Mono<Void> applySystemRollback(String configKey, String jsonValue) throws JsonProcessingException {
        return switch (configKey) {
            case DynamicConfigService.KEY_LIMIT -> {
                LimitConfig config = objectMapper.readValue(jsonValue, LimitConfig.class);
                yield systemConfigManager.saveSystemLimit(config);
            }
            case DynamicConfigService.KEY_RESILIENCE -> {
                ResilienceConfig config = objectMapper.readValue(jsonValue, ResilienceConfig.class);
                yield systemConfigManager.saveSystemResilience(config);
            }
            case DynamicConfigService.KEY_PRICING -> {
                PricingConfig config = objectMapper.readValue(jsonValue, PricingConfig.class);
                yield systemConfigManager.saveSystemPricing(config);
            }
            default -> Mono.error(new GatewayException(
                    HttpStatus.BAD_REQUEST, "invalid_system_key",
                    "Unknown system config key: " + configKey));
        };
    }
}
