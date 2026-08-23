package io.gateway.oss.core.util;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonMapConverterTest {

    private final JsonMapConverter converter = new JsonMapConverter();

    @Test
    void convertToDatabaseColumnReturnsEmptyJsonForNull() {
        assertEquals("{}", converter.convertToDatabaseColumn(null));
    }

    @Test
    void convertToDatabaseColumnReturnsEmptyJsonForEmptyMap() {
        assertEquals("{}", converter.convertToDatabaseColumn(Collections.emptyMap()));
    }

    @Test
    void convertToDatabaseColumnSerializesMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "alice");
        value.put("count", 2);

        assertEquals("{\"name\":\"alice\",\"count\":2}", converter.convertToDatabaseColumn(value));
    }

    @Test
    void convertToEntityAttributeReturnsEmptyMapForNull() {
        assertTrue(converter.convertToEntityAttribute(null).isEmpty());
    }

    @Test
    void convertToEntityAttributeReturnsEmptyMapForEmptyJsonObject() {
        assertTrue(converter.convertToEntityAttribute("{}").isEmpty());
    }

    @Test
    void convertToEntityAttributeDeserializesJson() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "alice");
        expected.put("count", 2);

        assertEquals(expected, converter.convertToEntityAttribute("{\"name\":\"alice\",\"count\":2}"));
    }

    @Test
    void convertRoundTripReturnsSameMap() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("enabled", true);
        original.put("count", 3);
        original.put("name", "demo");

        String dbValue = converter.convertToDatabaseColumn(original);
        Map<String, Object> restored = converter.convertToEntityAttribute(dbValue);

        assertEquals(original, restored);
    }
}
