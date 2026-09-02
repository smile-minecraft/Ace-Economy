package com.smile.aceeconomy.infrastructure.persistence.sql;

/**
 * MySQL dialect. Uses VARCHAR/BOOLEAN and InnoDB. DDL implicitly commits per statement
 * (the surrounding {@code setAutoCommit(false)} does not make MySQL DDL transactional);
 * every DDL therefore uses {@code CREATE TABLE IF NOT EXISTS}/{@code INSERT IGNORE} so a
 * later {@link com.smile.aceeconomy.ports.persistence.PersistenceLifecycle#initialize()} can
 * resume after a partial failure without a compensating DROP.
 */
public final class MySqlDialect implements SqlDialect {

    @Override
    public boolean isMySQL() {
        return true;
    }

    @Override
    public String forUpdateClause() {
        return " FOR UPDATE";
    }
}
