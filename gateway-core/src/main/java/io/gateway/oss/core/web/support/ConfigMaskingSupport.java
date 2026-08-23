package io.gateway.oss.core.web.support;

import io.gateway.oss.core.config.DynamicConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConfigMaskingSupport {

    private final ObjectMapper objectMapper;

    public ConfigMaskingSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String maskSensitiveJson(String json, String configType) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (DynamicConfigService.TYPE_PROVIDERS.equals(configType)) {
                maskField(map, "apiKey");
                maskListField(map, "keys");
            }
            if (DynamicConfigService.TYPE_CLIENTS.equals(configType)) {
                maskField(map, "apiKey");
            }
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "***";
        }
    }

    public String maskConfigKey(String configType, String configKey) {
        if (DynamicConfigService.TYPE_CLIENTS.equals(configType)) {
            return maskClientKey(configKey);
        }
        return configKey;
    }

    public String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    public List<String> maskKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(keys.size());
        for (String key : keys) {
            result.add(mask(key));
        }
        return result;
    }

    public String maskClientKey(String clientKey) {
        return mask(clientKey);
    }

    private void maskField(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (value instanceof String s && !s.isBlank()) {
            map.put(field, mask(s));
        }
    }

    private void maskListField(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (value instanceof List<?> list) {
            map.put(field, list.stream()
                    .map(item -> item instanceof String s ? mask(s) : item)
                    .toList());
        }
    }
}
