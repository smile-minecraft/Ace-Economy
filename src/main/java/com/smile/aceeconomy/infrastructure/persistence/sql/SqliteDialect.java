package com.smile.aceeconomy.infrastructure.persistence.sql;

/** SQLite dialect. Uses TEXT/INTEGER storage; DDL is transactional. */
public final class SqliteDialect implements SqlDialect {

    @Override
    public boolean isMySQL() {
        return false;
    }

    @Override
    public String leaderboardAmountOrderExpression() {
        return "CAST(b.amount AS REAL)";
    }
}
