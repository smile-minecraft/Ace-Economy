package com.smile.aceeconomy.infrastructure.persistence.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline schema/dialect contract tests for {@link V2Schema} and the dialects. These prove the
 * generated DDL is versioned, idempotent and dialect-specific WITHOUT requiring a live database.
 * Live MySQL execution is NOT performed here (see docs/persistence.md for the runtime setup).
 */
final class V2SchemaContractTest {

    @Test
    void sqliteDdlUsesTextStorageAndNoEngineClause() {
        List<String> ddl = V2Schema.ddlStatements(new SqliteDialect());
        assertEquals(4, ddl.size());
        for (String stmt : ddl) {
            assertTrue(stmt.contains("CREATE TABLE IF NOT EXISTS"),
                    "SQLite DDL must be idempotent (IF NOT EXISTS): " + stmt);
        }
        String schemaTable = ddl.get(0);
        assertTrue(schemaTable.contains("INTEGER PRIMARY KEY"), "SQLite schema table uses INTEGER: " + schemaTable);
        assertTrue(schemaTable.contains("TEXT NOT NULL"), "SQLite uses TEXT columns: " + schemaTable);
        assertFalse(containsAnyEngineClause(ddl), "SQLite DDL must not contain MySQL ENGINE/CHARSET clauses");
    }

    @Test
    void mysqlDdlUsesInnoDBAndVarcharBoolean() {
        List<String> ddl = V2Schema.ddlStatements(new MySqlDialect());
        assertEquals(4, ddl.size());
        for (String stmt : ddl) {
            assertTrue(stmt.contains("CREATE TABLE IF NOT EXISTS"),
                    "MySQL DDL must be idempotent (IF NOT EXISTS): " + stmt);
            assertTrue(stmt.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
                    "MySQL DDL must pin InnoDB + utf8mb4: " + stmt);
        }
        String txTable = ddl.get(3);
        assertTrue(txTable.contains("VARCHAR(36)"), "MySQL transaction id is VARCHAR(36): " + txTable);
        assertTrue(txTable.contains("BOOLEAN NOT NULL DEFAULT FALSE"), "MySQL reverted flag is BOOLEAN: " + txTable);
        assertFalse(txTable.contains("TEXT PRIMARY KEY"), "MySQL must not use SQLite TEXT primary keys");
    }

    @Test
    void ddlIsDeterministicAcrossCalls() {
        assertEquals(V2Schema.ddlStatements(new SqliteDialect()), V2Schema.ddlStatements(new SqliteDialect()));
        assertEquals(V2Schema.ddlStatements(new MySqlDialect()), V2Schema.ddlStatements(new MySqlDialect()));
    }

    @Test
    void versionInsertSqlIsDialectSpecific() {
        assertTrue(V2Schema.versionInsertSql(new SqliteDialect()).contains("INSERT OR IGNORE"),
                "SQLite version insert must use INSERT OR IGNORE");
        assertTrue(V2Schema.versionInsertSql(new MySqlDialect()).contains("INSERT IGNORE"),
                "MySQL version insert must use INSERT IGNORE");
        assertNotEquals(V2Schema.versionInsertSql(new SqliteDialect()),
                V2Schema.versionInsertSql(new MySqlDialect()));
    }

    @Test
    void dialectFlagsAreCorrect() {
        assertFalse(new SqliteDialect().isMySQL());
        assertTrue(new MySqlDialect().isMySQL());
        assertEquals("INSERT OR IGNORE", new SqliteDialect().insertIgnore());
        assertEquals("INSERT IGNORE", new MySqlDialect().insertIgnore());
    }

    @Test
    void schemaVersionContract() {
        assertTrue(SchemaVersion.isCompatible(1));
        assertFalse(SchemaVersion.isCompatible(0));
        assertFalse(SchemaVersion.isCompatible(2));
    }

    private static boolean containsAnyEngineClause(List<String> ddl) {
        return ddl.stream().anyMatch(s -> s.contains("ENGINE=") || s.contains("CHARSET="));
    }
}
