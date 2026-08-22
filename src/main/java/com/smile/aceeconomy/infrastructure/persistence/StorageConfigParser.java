package com.smile.aceeconomy.infrastructure.persistence;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses the raw {@code storage} YAML block into a typed {@link StorageConfig}.
 *
 * <p>Pure (no I/O, no JDBC, no driver class-loading); testable with plain maps. The
 * parser is the only place that defines default values, so all "what if the operator
 * didn't write {@code storage.mysql.host}" decisions live here. Defaults match the
 * v2.0.0 {@link com.smile.aceeconomy.infrastructure.acelib.V2ConfigSchema} contract.</p>
 *
     * <p>Path safety: the SQLite database file is constrained to live under the plugin's
     * configured directory root; any path that resolves outside (including {@code ../}
     * escapes and absolute paths to other roots) is rejected at parse time, before the
     * factory touches the filesystem.</p>
 */
public final class StorageConfigParser {

    private StorageConfigParser() {
    }

    /**
     * @param rawStorageRoot the value at config path {@code storage}, or {@code null}
     *                       /missing → defaults to JSON.
     * @param pluginDataFolder the plugin's data folder; used as the root for SQLite
     *                         path resolution and the default JSON file location.
     */
    public static StorageConfig parse(Object rawStorageRoot, Path pluginDataFolder) {
        Objects.requireNonNull(pluginDataFolder, "pluginDataFolder");
        if (rawStorageRoot == null) {
            return new StorageConfig.Json(defaultJsonFile(pluginDataFolder));
        }
        // Accept plain Map (raw YAML / SnakeYAML output) and Bukkit
        // ConfigurationSection (AceLib hands us a MemorySection for
        // `config.storage`). Both shapes are normalized to Map<String, Object>
        // so the rest of the parser stays map-only.
        if (!(rawStorageRoot instanceof Map<?, ?>)
                && !(rawStorageRoot instanceof ConfigurationSection)) {
            throw new IllegalArgumentException(
                    "storage must be a YAML map, got " + rawStorageRoot.getClass().getName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) normalize(rawStorageRoot);
        Object typeRaw = root.get("type");
        StorageBackendKind kind = StorageBackendKind.fromConfig(
                typeRaw == null ? null : String.valueOf(typeRaw));
        return switch (kind) {
            case JSON -> new StorageConfig.Json(defaultJsonFile(pluginDataFolder));
            case SQLITE -> buildSqlite(root, pluginDataFolder);
            case MYSQL -> buildMysql(root);
        };
    }

    private static StorageConfig.Sqlite buildSqlite(Map<String, Object> root, Path dataFolder) {
        Object sqliteRaw = root.get("sqlite");
        String sqlitePath;
        if (sqliteRaw == null) {
            sqlitePath = "data-v2.sqlite";
        } else if (sqliteRaw instanceof Map<?, ?> sqliteMap) {
            Object p = castMap(sqliteMap).get("path");
            sqlitePath = (p == null) ? "data-v2.sqlite" : String.valueOf(p);
        } else {
            // A non-null, non-map value (e.g. `storage.sqlite: data-v2.sqlite`) is
            // almost certainly an operator mistake. Silently falling through to
            // defaults would mask the typo; fail fast so the misconfiguration is
            // visible at boot.
            throw new IllegalArgumentException(
                    "storage.sqlite must be a YAML map, got "
                            + sqliteRaw.getClass().getName());
        }
        return new StorageConfig.Sqlite(resolveSqliteFile(sqlitePath, dataFolder));
    }

    private static StorageConfig.Mysql buildMysql(Map<String, Object> root) {
        Object mysqlRaw = root.get("mysql");
        Map<String, Object> mysqlMap;
        if (mysqlRaw == null) {
            mysqlMap = Map.of();
        } else if (mysqlRaw instanceof Map<?, ?> anyMap) {
            mysqlMap = castMap(anyMap);
        } else {
            // Same rationale as storage.sqlite: a scalar at storage.mysql means
            // the operator wrote the block incorrectly. Reject it so the mistake
            // is surfaced instead of producing a working-but-wrong MySQL config
            // built entirely from defaults.
            throw new IllegalArgumentException(
                    "storage.mysql must be a YAML map, got "
                            + mysqlRaw.getClass().getName());
        }
        String host = stringOr(mysqlMap, "host", "localhost");
        int port = intOr(mysqlMap, "port", 3306);
        String database = stringOr(mysqlMap, "database", "aceeconomy");
        String username = stringOr(mysqlMap, "username", "root");
        String password = stringOr(mysqlMap, "password", "");
        int poolSize = intOr(mysqlMap, "pool-size", 10);
        long maxLifetime = longOr(mysqlMap, "max-lifetime", 1_800_000L);
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database;
        return new StorageConfig.Mysql(jdbcUrl, username, password, poolSize, maxLifetime);
    }

    private static Path defaultJsonFile(Path dataFolder) {
        return dataFolder.resolve("data-v2.json");
    }

    private static Path resolveSqliteFile(String raw, Path dataFolder) {
        Path normalizedRoot = dataFolder.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(raw).normalize();
        if (!candidate.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(
                    "SQLite database path '" + raw + "' resolves to '"
                            + candidate + "', which is outside the plugin data folder '"
                            + normalizedRoot + "'. SQLite files must live under the data folder.");
        }
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /**
     * Recursively converts a Bukkit {@link ConfigurationSection} (or a raw
     * {@link Map}) into a plain {@code Map<String, Object>} tree, recursing
     * into nested sections and maps. Other values (strings, numbers, lists,
     * booleans) are returned unchanged so existing coercion paths keep
     * behaving the same way. Non-string map keys are rejected at the
     * {@code storage} root to keep the parser's pre-existing fail-fast
     * contract intact.
     */
    private static Object normalize(Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
                result.put(entry.getKey(), normalize(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String s)) {
                    throw new IllegalArgumentException(
                            "storage map keys must be strings, got "
                                    + (key == null ? "null" : key.getClass().getName()));
                }
                result.put(s, normalize(entry.getValue()));
            }
            return result;
        }
        return value;
    }

    private static String stringOr(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static int intOr(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long longOr(Map<String, Object> m, String key, long fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }
}