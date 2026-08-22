package com.smile.aceeconomy.infrastructure.persistence;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Focused contract for {@link StorageConfigParser}: validates nested map parsing, default
 * application, SQLite path containment under the plugin data folder, and fail-fast
 * behaviour for unknown storage types.
 */
final class StorageConfigParserTest {

    @TempDir
    Path dataFolder;

    @Test
    void missingTypeDefaultsToJson() {
        StorageConfig cfg = StorageConfigParser.parse(Map.of(), dataFolder);
        assertInstanceOf(StorageConfig.Json.class, cfg);
        assertEquals(dataFolder.resolve("data-v2.json"), ((StorageConfig.Json) cfg).dataFile());
    }

    @Test
    void nullRootDefaultsToJson() {
        StorageConfig cfg = StorageConfigParser.parse(null, dataFolder);
        assertInstanceOf(StorageConfig.Json.class, cfg);
    }

    @Test
    void jsonKindResolvesDataFileUnderDataFolder() {
        StorageConfig cfg = StorageConfigParser.parse(Map.of("type", "json"), dataFolder);
        assertInstanceOf(StorageConfig.Json.class, cfg);
        assertEquals(dataFolder.resolve("data-v2.json"), ((StorageConfig.Json) cfg).dataFile());
    }

    @Test
    void sqliteKindResolvesConfiguredPathUnderDataFolder() {
        StorageConfig cfg = StorageConfigParser.parse(Map.of(
                "type", "sqlite",
                "sqlite", Map.of("path", "data-v2.sqlite")
        ), dataFolder);
        assertInstanceOf(StorageConfig.Sqlite.class, cfg);
        assertEquals(dataFolder.resolve("data-v2.sqlite"),
                ((StorageConfig.Sqlite) cfg).databaseFile());
    }

    @Test
    void sqliteWithoutExplicitPathDefaultsToDataV2Sqlite() {
        StorageConfig cfg = StorageConfigParser.parse(Map.of(
                "type", "sqlite",
                "sqlite", Map.of()
        ), dataFolder);
        assertInstanceOf(StorageConfig.Sqlite.class, cfg);
        assertEquals(dataFolder.resolve("data-v2.sqlite"),
                ((StorageConfig.Sqlite) cfg).databaseFile());
    }

    @Test
    void sqlitePathTraversalIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse(Map.of(
                        "type", "sqlite",
                        "sqlite", Map.of("path", "../escape.sqlite")
                ), dataFolder));
        assertEquals(true, ex.getMessage().contains("plugin data folder")
                || ex.getMessage().toLowerCase().contains("data folder"),
                "rejection message must mention the data folder: " + ex.getMessage());
    }

    @Test
    void sqliteAbsolutePathOutsideDataFolderIsRejected() {
        // Even an absolute path that is outside the data folder must be refused.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse(Map.of(
                        "type", "sqlite",
                        "sqlite", Map.of("path", "/etc/passwd")
                ), dataFolder));
        assertEquals(true, ex.getMessage().toLowerCase().contains("data folder"));
    }

    @Test
    void mysqlKindBuildsJdbcUrlFromHostPortDatabase() {
        StorageConfig cfg = StorageConfigParser.parse(Map.of(
                "type", "mysql",
                "mysql", Map.of(
                        "host", "db.example.com",
                        "port", 3307,
                        "database", "ace",
                        "username", "alice",
                        "password", "secret",
                        "pool-size", 8,
                        "max-lifetime", 600000
                )
        ), dataFolder);
        assertInstanceOf(StorageConfig.Mysql.class, cfg);
        StorageConfig.Mysql m = (StorageConfig.Mysql) cfg;
        assertEquals("jdbc:mysql://db.example.com:3307/ace", m.jdbcUrl());
        assertEquals("alice", m.username());
        assertEquals("secret", m.password());
        assertEquals(8, m.poolSize());
        assertEquals(600_000L, m.maxLifetimeMs());
    }

    @Test
    void mysqlDefaultsAppliedForMissingFields() {
        StorageConfig cfg = StorageConfigParser.parse(Map.of(
                "type", "mysql",
                "mysql", Map.of("password", "p")
        ), dataFolder);
        StorageConfig.Mysql m = (StorageConfig.Mysql) cfg;
        assertEquals("jdbc:mysql://localhost:3306/aceeconomy", m.jdbcUrl());
        assertEquals("root", m.username());
        assertEquals("p", m.password());
        assertEquals(10, m.poolSize());
        assertEquals(1_800_000L, m.maxLifetimeMs());
    }

    @Test
    void mysqlMissingBlockStillUsesAllDefaults() {
        StorageConfig cfg = StorageConfigParser.parse(Map.of("type", "mysql"), dataFolder);
        StorageConfig.Mysql m = (StorageConfig.Mysql) cfg;
        assertEquals("jdbc:mysql://localhost:3306/aceeconomy", m.jdbcUrl());
        assertEquals(10, m.poolSize());
        assertEquals(1_800_000L, m.maxLifetimeMs());
    }

    @Test
    void unknownStorageTypeFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse(Map.of("type", "postgres"), dataFolder));
        assertEquals(true, ex.getMessage().contains("postgres"));
    }

    @Test
    void nonMapRootIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse("not a map", dataFolder));
        assertEquals(true, ex.getMessage().toLowerCase().contains("yaml map")
                || ex.getMessage().toLowerCase().contains("map"));
    }

    @Test
    void numericCoercionFromStringPortIsAllowed() {
        Map<String, Object> mysql = new HashMap<>();
        mysql.put("port", "3307"); // YAML may yield a String for unquoted numerics on some paths
        StorageConfig cfg = StorageConfigParser.parse(Map.of("type", "mysql", "mysql", mysql),
                dataFolder);
        StorageConfig.Mysql m = (StorageConfig.Mysql) cfg;
        assertEquals("jdbc:mysql://localhost:3307/aceeconomy", m.jdbcUrl());
    }

    // ---------------------------------------------------------------------
    // Bukkit ConfigurationSection / MemorySection regression coverage.
    //
    // AceLib returns the raw `storage` node as a Bukkit ConfigurationSection
    // (concretely a MemorySection). The parser must accept that shape at the
    // root and recurse into nested ConfigurationSection children for sqlite
    // and mysql, while preserving defaults, type coercion, SQLite path safety
    // and the fail-fast behaviour for unknown / non-map inputs.
    // ---------------------------------------------------------------------

    @Test
    void memorySectionRootDefaultsToJson() {
        MemoryConfiguration root = new MemoryConfiguration();
        StorageConfig cfg = StorageConfigParser.parse(root, dataFolder);
        assertInstanceOf(StorageConfig.Json.class, cfg);
        assertEquals(dataFolder.resolve("data-v2.json"), ((StorageConfig.Json) cfg).dataFile());
    }

    @Test
    void memorySectionRootWithSqliteNestedSectionParsesPath() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("type", "sqlite");
        ConfigurationSection sqlite = root.createSection("sqlite");
        sqlite.set("path", "data-v2.sqlite");
        StorageConfig cfg = StorageConfigParser.parse(root, dataFolder);
        assertInstanceOf(StorageConfig.Sqlite.class, cfg);
        assertEquals(dataFolder.resolve("data-v2.sqlite"),
                ((StorageConfig.Sqlite) cfg).databaseFile());
    }

    @Test
    void memorySectionRootWithMysqlNestedSectionBuildsJdbcUrl() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("type", "mysql");
        ConfigurationSection mysql = root.createSection("mysql");
        mysql.set("host", "db.example.com");
        mysql.set("port", 3307);
        mysql.set("database", "ace");
        mysql.set("username", "alice");
        mysql.set("password", "secret");
        mysql.set("pool-size", 8);
        mysql.set("max-lifetime", 600_000L);
        StorageConfig cfg = StorageConfigParser.parse(root, dataFolder);
        assertInstanceOf(StorageConfig.Mysql.class, cfg);
        StorageConfig.Mysql m = (StorageConfig.Mysql) cfg;
        assertEquals("jdbc:mysql://db.example.com:3307/ace", m.jdbcUrl());
        assertEquals("alice", m.username());
        assertEquals("secret", m.password());
        assertEquals(8, m.poolSize());
        assertEquals(600_000L, m.maxLifetimeMs());
    }

    @Test
    void memorySectionSqlitePathTraversalStillRejected() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("type", "sqlite");
        ConfigurationSection sqlite = root.createSection("sqlite");
        sqlite.set("path", "../escape.sqlite");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse(root, dataFolder));
        assertEquals(true, ex.getMessage().toLowerCase().contains("data folder"),
                "rejection message must mention the data folder: " + ex.getMessage());
    }

    @Test
    void memorySectionUnknownTypeStillFailsFast() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("type", "postgres");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse(root, dataFolder));
        assertEquals(true, ex.getMessage().contains("postgres"));
    }

    // ---------------------------------------------------------------------
    // Nested scalar regression coverage.
    //
    // A misconfigured operator may write `storage.sqlite: data-v2.sqlite`
    // (a scalar) instead of the expected nested map. The parser used to
    // silently fall through to defaults in that case, which masks the
    // mistake. The contract is: null → use defaults, map/section → parse
    // it, anything else → fail fast with a message that names the offending
    // block.
    // ---------------------------------------------------------------------

    @Test
    void nestedSqliteScalarIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse(Map.of(
                        "type", "sqlite",
                        "sqlite", "data-v2.sqlite"
                ), dataFolder));
        assertEquals(true, ex.getMessage().contains("storage.sqlite"),
                "rejection message must name the storage.sqlite block: " + ex.getMessage());
    }

    @Test
    void nestedMysqlScalarIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse(Map.of(
                        "type", "mysql",
                        "mysql", "oops"
                ), dataFolder));
        assertEquals(true, ex.getMessage().contains("storage.mysql"),
                "rejection message must name the storage.mysql block: " + ex.getMessage());
    }

    @Test
    void nestedSqliteNonStringScalarIsRejected() {
        // Numeric or other non-map scalar must not be silently accepted either.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse(Map.of(
                        "type", "sqlite",
                        "sqlite", 42
                ), dataFolder));
        assertEquals(true, ex.getMessage().contains("storage.sqlite"),
                "rejection message must name the storage.sqlite block: " + ex.getMessage());
    }

    @Test
    void memorySectionNestedSqliteScalarIsRejected() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("type", "sqlite");
        // Operator mistake: writing a string instead of a nested map.
        root.set("sqlite", "data-v2.sqlite");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse(root, dataFolder));
        assertEquals(true, ex.getMessage().contains("storage.sqlite"),
                "rejection message must name the storage.sqlite block: " + ex.getMessage());
    }

    @Test
    void memorySectionNestedMysqlScalarIsRejected() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("type", "mysql");
        root.set("mysql", "oops");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                StorageConfigParser.parse(root, dataFolder));
        assertEquals(true, ex.getMessage().contains("storage.mysql"),
                "rejection message must name the storage.mysql block: " + ex.getMessage());
    }
}