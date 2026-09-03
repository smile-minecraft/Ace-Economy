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

    /**
     * Numeric ORDER BY expression for leaderboard amount. Amounts are stored as decimal
     * strings; lexicographic ordering would be wrong (e.g. "100" &lt; "20").
     * MySQL needs DECIMAL with sufficient precision; SQLite uses REAL.
     * The expression is used as {@code ORDER BY <expr> DESC, owner ASC}.
     */
    default String leaderboardAmountOrderExpression() {
        return isMySQL() ? "CAST(b.amount AS DECIMAL(65,30))" : "CAST(b.amount AS REAL)";
    }
}
