package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Round-trip and corrupt-input coverage for the hand-rolled JSON codec. */
class JsonCodecTest {

    @Test
    void roundTripsObjectWithNestedArraysAndScalars() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("owner", "abc");
        root.put("count", 3.0);
        root.put("active", Boolean.TRUE);
        root.put("missing", null);
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("k", "v");
        root.put("nested", inner);
        root.put("list", List.of("x", 1.0, Boolean.FALSE));

        String json = JsonCodec.write(root);
        Object parsed = JsonCodec.parse(json);

        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) parsed;
        assertEquals("abc", back.get("owner"));
        assertEquals(3.0, back.get("count"));
        assertEquals(Boolean.TRUE, back.get("active"));
        assertEquals(null, back.get("missing"));
        @SuppressWarnings("unchecked")
        Map<String, Object> innerBack = (Map<String, Object>) back.get("nested");
        assertEquals("v", innerBack.get("k"));
        @SuppressWarnings("unchecked")
        List<Object> listBack = (List<Object>) back.get("list");
        assertEquals(List.of("x", 1.0, Boolean.FALSE), listBack);
    }

    @Test
    void escapesAndUnescapesStrings() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("s", "line1\nline2\t\"q\"\\slash");
        String json = JsonCodec.write(root);
        Object parsed = JsonCodec.parse(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) parsed;
        assertEquals("line1\nline2\t\"q\"\\slash", back.get("s"));
    }

    @Test
    void rejectsTruncatedInput() {
        assertThrows(PersistenceException.class, () -> JsonCodec.parse("{\"owner\":"));
    }

    @Test
    void rejectsTrailingContent() {
        assertThrows(PersistenceException.class, () -> JsonCodec.parse("{} extra"));
    }

    @Test
    void rejectsInvalidEscape() {
        assertThrows(PersistenceException.class, () -> JsonCodec.parse("\"\\z\""));
    }

    @Test
    void rejectsInvalidNumber() {
        assertThrows(PersistenceException.class, () -> JsonCodec.parse("12.3.4"));
    }
}
