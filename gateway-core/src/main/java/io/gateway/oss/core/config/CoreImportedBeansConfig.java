package io.gateway.oss.core.config;

import io.gateway.oss.core.limit.ConcurrentRequestLimiter;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.upstream.AnthropicChatProviderAdapter;
import io.gateway.oss.core.upstream.GeminiChatProviderAdapter;
import io.gateway.oss.core.upstream.OpenAiCompatibleChatProviderAdapter;
import io.gateway.oss.core.upstream.ProviderHealthService;
import io.gateway.oss.core.upstream.ProviderKeyResilienceTracker;
import io.gateway.oss.core.upstream.ProviderKeySelector;
import io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService;
import io.gateway.oss.core.upstream.UpstreamChatClient;
import io.gateway.oss.core.web.ChatCompletionsController;
import io.gateway.oss.core.web.ChatCompletionsOrchestrator;
import io.gateway.oss.core.web.CompletionRecorder;
import io.gateway.oss.core.web.GatewayHealthIndicator;
import io.gateway.oss.core.web.GlobalExceptionHandler;
import io.gateway.oss.core.web.HealthController;
import io.gateway.oss.core.web.ModelsController;
import io.gateway.oss.core.web.OperationalGateService;
import io.gateway.oss.core.web.RequestIdFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * core 显式导入 Bean 分组配置。
 * <p>
 * 仅做装配归组，降低单个 auto-configuration / component-scan 配置类的
 * “大清单”集中度，不改变现有启动语义。
 * </p>
 */
@Configuration
@Import({
        ConcurrentRequestLimiter.class,
        RequestLogService.class,
        GatewayMetricsRecorder.class,
        ChatCompletionsController.class,
        ChatCompletionsOrchestrator.class,
        CompletionRecorder.class,
        GatewayHealthIndicator.class,
        GlobalExceptionHandler.class,
        HealthController.class,
        ModelsController.class,
        OperationalGateService.class,
        RequestIdFilter.class,
        Resilience4jCircuitBreakerService.class,
        UpstreamChatClient.class,
        ProviderHealthService.class,
        GeminiChatProviderAdapter.class,
        AnthropicChatProviderAdapter.class,
        OpenAiCompatibleChatProviderAdapter.class,
        ProviderKeyResilienceTracker.class,
        ProviderKeySelector.class,
        RedisConnectionConfig.class,
        ConfigSyncConfig.class,
        ConfigLoadService.class,
        ConfigImportApplier.class,
        SecurityStartupValidator.class
})
public class CoreImportedBeansConfig {
}
