package com.smile.aceeconomy.infrastructure.integration.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiscordPayloadFilterTest {

    private final DiscordPayloadFilter filter = new DiscordPayloadFilter();

    @Test
    void sanitizeTruncatesLongInput() {
        String longInput = "x".repeat(2000);
        String out = filter.sanitize(longInput, 10, List.of());
        assertEquals(10, out.length());
    }

    @Test
    void sanitizeRedactsSecret() {
        String out = filter.sanitize("token=ABC123secret", 1024, List.of("ABC123secret"));
        assertEquals("token=***", out);
    }

    @Test
    void sanitizeNullBecomesEmpty() {
        assertEquals("", filter.sanitize(null, 10, null));
    }

    @Test
    void sanitizeLeavesNormalInputUnchanged() {
        assertEquals("hello", filter.sanitize("hello", 1024, List.of()));
    }

    @Test
    void escapeJsonEscapesSpecialChars() {
        assertEquals("a\\\"b\\\\c", DiscordPayloadFilter.escapeJson("a\"b\\c"));
        assertEquals("line1\\nline2", DiscordPayloadFilter.escapeJson("line1\nline2"));
        assertEquals("tab\\tend", DiscordPayloadFilter.escapeJson("tab\tend"));
    }

    @Test
    void escapeJsonNullBecomesEmpty() {
        assertEquals("", DiscordPayloadFilter.escapeJson(null));
    }
}
