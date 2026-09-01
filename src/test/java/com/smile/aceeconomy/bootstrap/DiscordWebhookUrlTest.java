package com.smile.aceeconomy.bootstrap;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for the strict Discord webhook URL validator used by the bootstrap wiring.
 *
 * <p>Each test below fails against the previous bootstrap code which only called
 * {@code URI.create(webhookUrl)}; a relative URL, an {@code ftp:} URL, or a {@code mailto:}
 * URL would all have passed that check and reached the HTTP transport.</p>
 */
class DiscordWebhookUrlTest {

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookUrl.validate(null));
    }

    @Test
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookUrl.validate(""));
    }

    @Test
    void rejectsRelativePath() {
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookUrl.validate("/relative/path"));
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookUrl.validate("hooks/123"));
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookUrl.validate("./local"));
    }

    @Test
    void rejectsSchemeLess() {
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookUrl.validate("hooks.discord.example/services/foo"));
    }

    @Test
    void rejectsFtp() {
        assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookUrl.validate("ftp://hooks.discord.example/services/foo"));
    }

    @Test
    void rejectsMailto() {
        assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookUrl.validate("mailto:noreply@discord.example"));
    }

    @Test
    void rejectsFile() {
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookUrl.validate("file:///etc/passwd"));
    }

    @Test
    void rejectsJavaScript() {
        assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookUrl.validate("javascript:alert(1)"));
    }

    @Test
    void rejectsMissingHost() {
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookUrl.validate("http:///path"));
    }

    @Test
    void rejectsUserInfo() {
        assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookUrl.validate("https://user:pass@hooks.discord.example/services/foo"));
    }

    @Test
    void rejectsOutOfRangePort() {
        assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookUrl.validate("https://hooks.discord.example:99999/services/foo"));
        assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookUrl.validate("https://hooks.discord.example:0/services/foo"));
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookUrl.validate("http://[invalid"));
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookUrl.validate("http://example .com/x"));
    }

    @Test
    void acceptsAbsoluteHttp() {
        URI uri = DiscordWebhookUrl.validate("http://hooks.discord.example/services/foo");
        assertEquals("http", uri.getScheme());
        assertEquals("hooks.discord.example", uri.getHost());
        assertTrue(uri.isAbsolute());
    }

    @Test
    void acceptsAbsoluteHttps() {
        URI uri = DiscordWebhookUrl.validate("https://hooks.discord.example/services/foo");
        assertEquals("https", uri.getScheme());
        assertEquals("hooks.discord.example", uri.getHost());
        assertTrue(uri.isAbsolute());
    }

    @Test
    void acceptsPort() {
        URI uri = DiscordWebhookUrl.validate("https://hooks.discord.example:8443/services/foo");
        assertEquals(8443, uri.getPort());
    }

    @Test
    void acceptsSchemeCaseInsensitively() {
        URI uri = DiscordWebhookUrl.validate("HTTPS://hooks.discord.example/services/foo");
        assertEquals("https", uri.getScheme().toLowerCase());
    }

    @Test
    void errorMessageNeverContainsUrl() {
        // Use a URL that's structurally invalid (relative) so the validator throws.
        // The validator must echo a fixed message that never contains any portion of the URL.
        String secretUrl = "/SECRET-DISCORD-WEBHOOK/services/foo";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookUrl.validate(secretUrl));
        assertNotNull(ex.getMessage());
        assertFalse(ex.getMessage().contains("SECRET-DISCORD-WEBHOOK"),
                "validator message must not echo URL: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("/services/foo"),
                "validator message must not echo URL path: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("hooks"),
                "validator message must not echo URL host: " + ex.getMessage());
    }

    @Test
    void errorMessageNeverEchoesSchemeFailureUrl() {
        String secretUrl = "ftp://SECRET-DISCORD-WEBHOOK.example/services/foo";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookUrl.validate(secretUrl));
        assertFalse(ex.getMessage().contains("SECRET-DISCORD-WEBHOOK"));
        assertFalse(ex.getMessage().contains("example"));
        assertFalse(ex.getMessage().contains("discord"));
        assertFalse(ex.getMessage().contains("services"));
    }

    @Test
    void errorMessageNeverEchoesUserInfoFailureUrl() {
        String secretUrl = "https://user:SECRET-DISCORD-WEBHOOK@example.com/services/foo";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookUrl.validate(secretUrl));
        assertFalse(ex.getMessage().contains("SECRET-DISCORD-WEBHOOK"));
        assertFalse(ex.getMessage().contains("user:"));
    }

    @Test
    void errorMessageNeverEchoesPortFailureUrl() {
        String secretUrl = "https://SECRET.example:99999/services/foo";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DiscordWebhookUrl.validate(secretUrl));
        assertFalse(ex.getMessage().contains("SECRET"));
        assertFalse(ex.getMessage().contains("99999"));
        assertFalse(ex.getMessage().contains("example"));
    }
}
