package io.gateway.oss.core.web;

import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.security.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelListProviderTest {

    @Test
    void anonymousProvider_buildModelsAndHasDataWorkAsExpected() {
        ModelsController.ModelObject openAiModel = model("gpt-4o", "exec-gpt-4o", "openai");
        ModelsController.ModelObject anthropicModel = model("claude-3-5-sonnet", "exec-claude", "anthropic");

        ModelListProvider provider = new ModelListProvider() {
            private final List<ModelsController.ModelObject> models = List.of(openAiModel, anthropicModel);

            @Override
            public List<ModelsController.ModelObject> buildModels(String providerFilter, String modelFilter) {
                return models.stream()
                        .filter(model -> providerFilter == null || providerFilter.equals(model.owned_by()))
                        .filter(model -> modelFilter == null || modelFilter.equals(model.id()))
                        .toList();
            }

            @Override
            public boolean hasData() {
                return true;
            }
        };

        assertTrue(provider.hasData());
        assertEquals(List.of(openAiModel), provider.buildModels("openai", null));
        assertEquals(List.of(anthropicModel), provider.buildModels(null, "claude-3-5-sonnet"));
    }

    @Test
    void modelsController_callsThroughToModelListProvider() {
        ModelsController.ModelObject openAiModel = model("gpt-4o", "exec-gpt-4o", "openai");
        ModelListProvider provider = new ModelListProvider() {
            @Override
            public List<ModelsController.ModelObject> buildModels(String providerFilter, String modelFilter) {
                return "openai".equals(providerFilter) ? List.of(openAiModel) : List.of();
            }

            @Override
            public boolean hasData() {
                return true;
            }
        };

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("modelListProvider", provider);
        ModelsController controller = new ModelsController(
                beanFactory.getBeanProvider(UserAccountService.class),
                beanFactory.getBeanProvider(ClientAuthService.class),
                beanFactory.getBeanProvider(ModelListProvider.class)
        );

        ResponseEntity<ModelsController.ModelsListResponse> response = controller.listModels(
                "openai",
                null,
                MockServerWebExchange.from(MockServerHttpRequest.get("/v1/models").build())
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals("list", response.getBody().object());
        assertEquals(1, response.getBody().data().size());
        assertEquals("gpt-4o", response.getBody().data().getFirst().id());
    }

    private static ModelsController.ModelObject model(String id, String executionId, String provider) {
        return new ModelsController.ModelObject(
                id,
                executionId,
                id,
                "provider",
                "model",
                0L,
                provider,
                List.of(),
                128000,
                List.of("chat"),
                Map.of(),
                "active",
                Map.of()
        );
    }
}
