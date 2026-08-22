package com.smile.aceeconomy.infrastructure.persistence;

import java.util.Locale;

/**
 * The set of v2.0.0 storage backends. Only JSON, SQLite and MySQL are supported; any
 * other value is rejected by {@link #fromConfig(String)} with a message that lists the
 * valid options so operator typos fail fast at boot, never silently fall back.
 */
public enum StorageBackendKind {
    JSON,
    SQLITE,
    MYSQL;

    /**
     * Normalize a raw {@code storage.type} string and convert it to a kind.
     *
     * <p>Normalization rules (durable contract):</p>
     * <ul>
     *   <li>{@code null} or blank (after trim) → {@link #JSON} (fresh-install default).</li>
     *   <li>Surrounding whitespace is trimmed; comparison is case-insensitive
     *       (US-root locale, so the contract is independent of the JVM default locale).</li>
     *   <li>Any other value throws {@link IllegalArgumentException} whose message echoes
     *       the original raw value AND lists every supported kind.</li>
     * </ul>
     */
    public static StorageBackendKind fromConfig(String raw) {
        if (raw == null) {
            return JSON;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return JSON;
        }
        return switch (normalized) {
            case "json" -> JSON;
            case "sqlite" -> SQLITE;
            case "mysql" -> MYSQL;
            default -> throw new IllegalArgumentException(
                    "Unsupported storage.type '" + raw
                            + "'. Valid values are: json, sqlite, mysql.");
        };
    }
}