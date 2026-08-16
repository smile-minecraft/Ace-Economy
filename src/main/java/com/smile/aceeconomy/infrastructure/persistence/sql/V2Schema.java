package com.smile.aceeconomy.infrastructure.persistence.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure builder for the v2 SQL schema DDL. No JDBC, no I/O: given a dialect it returns the exact
 * statements that must be executed, so the contract is fully testable without a live database.
 *
 * <p>Design notes (durable):</p>
 * <ul>
 *   <li>All {@code CREATE TABLE} use {@code IF NOT EXISTS} so a restart is idempotent and a
 *       retry after a partial failure does not error on already-existing tables.</li>
 *   <li>Indexes are declared inline in {@code CREATE TABLE} to stay idempotent without a separate
 *       {@code CREATE INDEX IF NOT EXISTS} (older MySQL rejects that syntax).</li>
 *   <li>Amounts and balances are stored as exact decimal strings (no float drift).</li>
 *   <li>The version row is written with {@code INSERT OR IGNORE}/{@code INSERT IGNORE} so re-running
 *       initialization never duplicates it.</li>
 * </ul>
 */
public final class V2Schema {

    public static final String SCHEMA_TABLE = "ace_v2_schema";
    public static final String ACCOUNTS_TABLE = "ace_v2_accounts";
    public static final String BALANCES_TABLE = "ace_v2_balances";
    public static final String TRANSACTIONS_TABLE = "ace_v2_transactions";

    private V2Schema() {
    }

    public static List<String> ddlStatements(SqlDialect d) {
        List<String> stmts = new ArrayList<>();
        if (d.isMySQL()) {
            stmts.add("""
                    CREATE TABLE IF NOT EXISTS %s (
                        version INT PRIMARY KEY,
                        updated_at VARCHAR(64) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(SCHEMA_TABLE));
            stmts.add("""
                    CREATE TABLE IF NOT EXISTS %s (
                        owner VARCHAR(36) PRIMARY KEY,
                        owner_name VARCHAR(255) NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(ACCOUNTS_TABLE));
            stmts.add("""
                    CREATE TABLE IF NOT EXISTS %s (
                        owner VARCHAR(36) NOT NULL,
                        currency_id VARCHAR(32) NOT NULL,
                        amount VARCHAR(64) NOT NULL,
                        PRIMARY KEY (owner, currency_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(BALANCES_TABLE));
            stmts.add("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id VARCHAR(36) PRIMARY KEY,
                        account_id VARCHAR(36) NOT NULL,
                        counterparty VARCHAR(36),
                        currency_id VARCHAR(32) NOT NULL,
                        amount VARCHAR(64) NOT NULL,
                        type VARCHAR(32) NOT NULL,
                        balance_before VARCHAR(64),
                        balance_after VARCHAR(64),
                        timestamp VARCHAR(64) NOT NULL,
                        reason VARCHAR(255),
                        reverted BOOLEAN NOT NULL DEFAULT FALSE,
                        INDEX idx_tx_account (account_id),
                        INDEX idx_tx_reverted (reverted)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(TRANSACTIONS_TABLE));
        } else {
            stmts.add("""
                    CREATE TABLE IF NOT EXISTS %s (
                        version INTEGER PRIMARY KEY,
                        updated_at TEXT NOT NULL
                    )
                    """.formatted(SCHEMA_TABLE));
            stmts.add("""
                    CREATE TABLE IF NOT EXISTS %s (
                        owner TEXT PRIMARY KEY,
                        owner_name TEXT NOT NULL
                    )
                    """.formatted(ACCOUNTS_TABLE));
            stmts.add("""
                    CREATE TABLE IF NOT EXISTS %s (
                        owner TEXT NOT NULL,
                        currency_id TEXT NOT NULL,
                        amount TEXT NOT NULL,
                        PRIMARY KEY (owner, currency_id)
                    )
                    """.formatted(BALANCES_TABLE));
            stmts.add("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id TEXT PRIMARY KEY,
                        account_id TEXT NOT NULL,
                        counterparty TEXT,
                        currency_id TEXT NOT NULL,
                        amount TEXT NOT NULL,
                        type TEXT NOT NULL,
                        balance_before TEXT,
                        balance_after TEXT,
                        timestamp TEXT NOT NULL,
                        reason TEXT,
                        reverted INTEGER NOT NULL DEFAULT 0
                    )
                    """.formatted(TRANSACTIONS_TABLE));
        }
        return stmts;
    }

    /** Version-row insert with an idempotent ignore modifier and a {@code ?} placeholder for the timestamp. */
    public static String versionInsertSql(SqlDialect d) {
        return d.insertIgnore() + " INTO " + SCHEMA_TABLE + " (version, updated_at) VALUES (1, ?)";
    }

    public static String schemaTable() {
        return SCHEMA_TABLE;
    }

    public static String accountsTable() {
        return ACCOUNTS_TABLE;
    }

    public static String balancesTable() {
        return BALANCES_TABLE;
    }

    public static String transactionsTable() {
        return TRANSACTIONS_TABLE;
    }
}
