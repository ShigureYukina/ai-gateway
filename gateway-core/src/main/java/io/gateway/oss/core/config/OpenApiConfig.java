package io.gateway.oss.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Simple AI Gateway")
                        .version("v1")
                        .description("LLM API 网关 — 兼容 OpenAI API 协议，支持多供应商路由、熔断、限流、配额、"
                                + "动态配置管理与 Webhook 事件通知。\n\n"
                                + "## API 分组\n"
                                + "- **业务端点**: `/v1/chat/completions`, `/v1/models`\n"
                                + "- **认证**: `/auth/*`\n"
                                + "- **管理**: `/admin/*`\n"
                                + "- **内部**: `/internal/*`\n"
                                + "- **系统**: `/healthz`\n\n"
                                + "## 认证方式\n"
                                + "Bearer token: JWT (access token) 或 API Key"))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
                .addSecurityItem(new SecurityRequirement().addList("ApiKeyAuth"))
                .schemaRequirement("BearerAuth", new SecurityScheme()
                        .name("Authorization")
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT access token in Authorization header: Bearer <token>"))
                .schemaRequirement("ApiKeyAuth", new SecurityScheme()
                        .name("Authorization")
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .description("Gateway client API key in Authorization header"));
    }
}
