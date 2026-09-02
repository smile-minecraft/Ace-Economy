package com.smile.aceeconomy.infrastructure.persistence.sql;

/** SQL dialect abstraction. Only JDBC-standard types are used by the adapters. */
public interface SqlDialect {

    boolean isMySQL();

    /** Keyword between INSERT and INTO for idempotent version-row writes. */
    default String insertIgnore() {
        return isMySQL() ? "INSERT IGNORE" : "INSERT OR IGNORE";
    }

    /** Row-level lock suffix for read-modify-write. MySQL uses {@code FOR UPDATE}; SQLite returns empty. */
    default String forUpdateClause() {
        return "";
    }
}
