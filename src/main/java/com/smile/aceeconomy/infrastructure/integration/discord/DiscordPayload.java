package com.smile.aceeconomy.infrastructure.integration.discord;

import com.smile.aceeconomy.infrastructure.integration.discord.DiscordPayloadFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Discord webhook payload (a single embed). Values are expected to be pre-sanitized by
 * {@link DiscordPayloadFilter} before construction; {@link #toJson()} performs the structural JSON
 * escaping so the produced document is always valid and injection-safe.
 */
public final class DiscordPayload {

    private final String username;
    private final String title;
    private final int color;
    private final List<Field> fields;
    private final String timestamp;
    private final String footer;

    private DiscordPayload(Builder b) {
        this.username = b.username;
        this.title = b.title;
        this.color = b.color;
        this.fields = Collections.unmodifiableList(new ArrayList<>(b.fields));
        this.timestamp = b.timestamp;
        this.footer = b.footer;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"username\":\"").append(DiscordPayloadFilter.escapeJson(username)).append("\"");
        sb.append(",\"embeds\":[");
        sb.append("{\"title\":\"").append(DiscordPayloadFilter.escapeJson(title)).append("\"");
        sb.append(",\"color\":").append(color);
        sb.append(",\"fields\":[");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            Field f = fields.get(i);
            sb.append("{\"name\":\"").append(DiscordPayloadFilter.escapeJson(f.name)).append("\"");
            sb.append(",\"value\":\"").append(DiscordPayloadFilter.escapeJson(f.value)).append("\"");
            sb.append(",\"inline\":").append(f.inline);
            sb.append("}");
        }
        sb.append("]");
        if (timestamp != null) {
            sb.append(",\"timestamp\":\"").append(DiscordPayloadFilter.escapeJson(timestamp)).append("\"");
        }
        if (footer != null) {
            sb.append(",\"footer\":{\"text\":\"").append(DiscordPayloadFilter.escapeJson(footer)).append("\"}}");
        } else {
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    public static final class Field {
        private final String name;
        private final String value;
        private final boolean inline;

        public Field(String name, String value, boolean inline) {
            this.name = Objects.requireNonNull(name, "name");
            this.value = Objects.requireNonNull(value, "value");
            this.inline = inline;
        }
    }

    public static final class Builder {
        private String username = "AceEconomy";
        private String title = "";
        private int color = 0x55FF55;
        private final List<Field> fields = new ArrayList<>();
        private String timestamp;
        private String footer = "AceEconomy";

        public Builder username(String username) {
            this.username = Objects.requireNonNull(username, "username");
            return this;
        }

        public Builder title(String title) {
            this.title = Objects.requireNonNull(title, "title");
            return this;
        }

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public Builder addField(String name, String value, boolean inline) {
            this.fields.add(new Field(name, value, inline));
            return this;
        }

        public Builder timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder footer(String footer) {
            this.footer = footer;
            return this;
        }

        public DiscordPayload build() {
            return new DiscordPayload(this);
        }
    }
}
