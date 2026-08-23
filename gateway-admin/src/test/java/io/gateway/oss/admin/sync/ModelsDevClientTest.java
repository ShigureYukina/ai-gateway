package io.gateway.oss.admin.sync;

import io.gateway.oss.core.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelsDevClientTest {

    private final ModelsDevClient client = new ModelsDevClient(
            WebClient.builder(), new GatewayProperties());

    @Test
    void shouldExtractBooleanCapabilitiesFromModelMap() throws Exception {
        Map<String, Object> modelMap = new LinkedHashMap<>();
        modelMap.put("context_length", 8192);
        modelMap.put("supports_files", true);
        modelMap.put("supports_images", true);
        modelMap.put("supports_vision", false);
        modelMap.put("supports_audio", true);
        modelMap.put("supports_tools", true);

        Method method = ModelsDevClient.class.getDeclaredMethod(
                "extractModelMetadata", Map.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(client, modelMap);

        assertThat(result).containsEntry("context_length", 8192);
        assertThat(result).containsEntry("supports_files", true);
        assertThat(result).containsEntry("supports_images", true);
        assertThat(result).containsEntry("supports_audio", true);
        assertThat(result).containsEntry("supports_tools", true);
        assertThat(result).doesNotContainKey("supports_vision");
    }

    @Test
    void shouldNotIncludeFalseOrMissingCapabilities() throws Exception {
        Map<String, Object> modelMap = new LinkedHashMap<>();
        modelMap.put("supports_files", false);
        modelMap.put("supports_vision", null);

        Method method = ModelsDevClient.class.getDeclaredMethod(
                "extractModelMetadata", Map.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(client, modelMap);

        assertThat(result).doesNotContainKey("supports_files");
        assertThat(result).doesNotContainKey("supports_vision");
    }

    @Test
    void shouldParseOfficialCostInputAndOutputFields() throws Exception {
        Map<String, Object> modelMap = new LinkedHashMap<>();
        modelMap.put("cost", Map.of(
                "input", 0.0005,
                "output", 0.0015
        ));

        Method method = ModelsDevClient.class.getDeclaredMethod("extractPricingEntry", Map.class);
        method.setAccessible(true);
        PricingSyncService.ModelPricingEntry result =
                (PricingSyncService.ModelPricingEntry) method.invoke(client, modelMap);

        assertThat(result.inputUnitPrice()).isEqualByComparingTo("0.0005");
        assertThat(result.outputUnitPrice()).isEqualByComparingTo("0.0015");
        assertThat(result.unitPrice()).isEqualByComparingTo("0.0005");
    }

    @Test
    void shouldKeepLegacyPricingFieldsCompatible() throws Exception {
        Map<String, Object> modelMap = new LinkedHashMap<>();
        modelMap.put("pricing", Map.of(
                "input", 0.0003,
                "output", 0.0009
        ));

        Method method = ModelsDevClient.class.getDeclaredMethod("extractPricingEntry", Map.class);
        method.setAccessible(true);
        PricingSyncService.ModelPricingEntry result =
                (PricingSyncService.ModelPricingEntry) method.invoke(client, modelMap);

        assertThat(result.inputUnitPrice()).isEqualByComparingTo("0.0003");
        assertThat(result.outputUnitPrice()).isEqualByComparingTo("0.0009");
        assertThat(result.unitPrice()).isEqualByComparingTo("0.0003");
    }
}
