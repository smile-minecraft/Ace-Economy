package com.smile.aceeconomy.infrastructure.persistence.sql;

/** MySQL dialect. Uses VARCHAR/BOOLEAN and InnoDB; DDL auto-commits per statement. */
public final class MySqlDialect implements SqlDialect {

    @Override
    public boolean isMySQL() {
        return true;
    }
}
