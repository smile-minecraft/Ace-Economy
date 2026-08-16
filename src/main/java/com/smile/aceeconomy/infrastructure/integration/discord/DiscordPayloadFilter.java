package com.smile.aceeconomy.infrastructure.integration.discord;

import java.util.Collection;
import java.util.Objects;

/**
 * Content policy for Discord payloads: bounds string lengths and redacts configured secrets so no
 * credential or player-sensitive value can leak into the webhook body.
 *
 * <p>Structural JSON escaping lives in {@link DiscordPayload#toJson()}; this class only sanitizes
 * the raw field content (truncate + redact). The webhook URL is never placed into a payload, so the
 * redaction list is a defense-in-depth safety net.</p>
 */
public final class DiscordPayloadFilter {

    public static final int MAX_FIELD_NAME_LENGTH = 256;
    public static final int MAX_FIELD_VALUE_LENGTH = 1024;
    public static final int MAX_TITLE_LENGTH = 256;
    public static final int MAX_FOOTER_LENGTH = 256;

    /** Truncate to {@code maxLength} and redact any configured secret substring. Null → empty. */
    public String sanitize(String input, int maxLength, Collection<String> secrets) {
        if (input == null) {
            return "";
        }
        String s = redact(input, secrets);
        if (s.length() > maxLength) {
            s = s.substring(0, maxLength);
        }
        return s;
    }

    private static String redact(String input, Collection<String> secrets) {
        String s = input;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && !secret.isEmpty()) {
                    s = s.replace(secret, "***");
                }
            }
        }
        return s;
    }

    /** Escape a string for safe embedding inside a JSON string literal. */
    public static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
