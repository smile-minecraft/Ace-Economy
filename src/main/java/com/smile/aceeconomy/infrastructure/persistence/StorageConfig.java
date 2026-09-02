package com.smile.aceeconomy.infrastructure.persistence;

import java.nio.file.Path;

/**
 * Typed view of the v2.0.0 {@code storage} configuration block. Built by
 * {@link StorageConfigParser}; consumed by {@link PersistenceBackendFactory}.
 *
 * <p>This is a sealed interface so the factory's switch is exhaustive at compile time
 * and any future backend addition must explicitly extend the type system (and the
 * schema, parser and factory) — silently accepted backends are a runtime hazard.</p>
 */
public sealed interface StorageConfig
        permits StorageConfig.Json, StorageConfig.Sqlite, StorageConfig.Mysql {

    /** JSON file backend. Used as the v2.0.0 default for fresh installs. */
    record Json(Path dataFile) implements StorageConfig {
        public Json {
            if (dataFile == null) {
                throw new IllegalArgumentException("dataFile must not be null");
            }
        }
    }

    /** Single-file SQLite backend under the plugin data folder. */
    record Sqlite(Path databaseFile) implements StorageConfig {
        public Sqlite {
            if (databaseFile == null) {
                throw new IllegalArgumentException("databaseFile must not be null");
            }
        }
    }

    /**
     * MySQL backend via the provider-owned strict pool. URL/credentials/pool settings all come from
     * {@code storage.mysql.*} — never hard-coded.
     */
    record Mysql(String jdbcUrl,
                 String username,
                 String password,
                 int poolSize,
                 long maxLifetimeMs) implements StorageConfig {
        public Mysql {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("jdbcUrl must not be blank");
            }
            if (username == null) {
                throw new IllegalArgumentException("username must not be null");
            }
            if (password == null) {
                throw new IllegalArgumentException("password must not be null");
            }
            if (poolSize <= 0) {
                throw new IllegalArgumentException("poolSize must be positive");
            }
            if (maxLifetimeMs <= 0) {
                throw new IllegalArgumentException("maxLifetimeMs must be positive");
            }
        }
    }
}
