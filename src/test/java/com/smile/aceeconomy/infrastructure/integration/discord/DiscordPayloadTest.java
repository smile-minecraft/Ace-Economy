package com.smile.aceeconomy.infrastructure.integration.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscordPayloadTest {

    @Test
    void toJsonEscapesAndStructuresFields() {
        DiscordPayload payload = new DiscordPayload.Builder()
                .title("a\"b")
                .color(0x55FF55)
                .addField("Name", "val\"ue", true)
                .build();
        String json = payload.toJson();
        assertTrue(json.startsWith("{\"username\":"));
        assertTrue(json.contains("\"title\":\"a\\\"b\""));
        assertTrue(json.contains("\"value\":\"val\\\"ue\""));
        assertTrue(json.contains("\"inline\":true"));
        assertTrue(json.contains("\"color\":5635925")); // 0x55FF55
    }

    @Test
    void toJsonIncludesTimestampAndFooterWhenSet() {
        DiscordPayload payload = new DiscordPayload.Builder()
                .title("t")
                .timestamp("2026-01-01T00:00:00Z")
                .footer("f")
                .build();
        String json = payload.toJson();
        assertTrue(json.contains("\"timestamp\":\"2026-01-01T00:00:00Z\""));
        assertTrue(json.contains("\"footer\":{\"text\":\"f\"}}"));
    }

    @Test
    void toJsonOmitsTimestampWhenAbsent() {
        DiscordPayload payload = new DiscordPayload.Builder().title("t").build();
        String json = payload.toJson();
        assertFalse(json.contains("\"timestamp\""));
    }
}
