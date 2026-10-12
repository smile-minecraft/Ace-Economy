package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operator-audit compatibility for the SQL backend against a real SQLite file:
 * rows written before operator tracking keep loading with the audit marked
 * unrecorded, initialization stays repeatable across restarts, and a newly
 * written audit survives a database reload intact.
 */
final class AdminAuditSqlTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    @TempDir
    Path dir;

    private SqlBackend backendFor(Path dbFile) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        return new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
    }

    private void insertLegacyRow(Path dbFile, UUID id, UUID account) throws SQLException {
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             PreparedStatement ps = raw.prepareStatement(
                     "INSERT INTO " + V2Schema.transactionsTable()
                             + " (id, account_id, counterparty, currency_id, amount, type,"
                             + " balance_before, balance_after, timestamp, reason, reverted)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id.toString());
            ps.setString(2, account.toString());
            ps.setString(3, null);
            ps.setString(4, "dollar");
            ps.setString(5, "10.00");
            ps.setString(6, "DEPOSIT");
            ps.setString(7, "0.00");
            ps.setString(8, "10.00");
            ps.setString(9, "2026-01-01T00:00:00Z");
            ps.setString(10, "deposit");
            ps.setBoolean(11, false);
            ps.executeUpdate();
        }
    }

    @Test
    void legacyRowWithoutAuditColumnsLoadsAsUnrecorded() throws Exception {
        Path db = dir.resolve("legacy.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();
        UUID id = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        insertLegacyRow(db, id, account);

        List<Transaction> all = backend.loadAll();

        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        assertNull(all.get(0).operatorId(), "legacy rows must read as unrecorded, not fail");
        assertNull(all.get(0).operatorName());
        backend.close();
    }

    @Test
    void initializeStaysRepeatableAcrossRestarts() throws Exception {
        Path db = dir.resolve("restart.db");
        SqlBackend first = backendFor(db);
        first.initialize();
        first.close();

        SqlBackend second = backendFor(db);
        second.initialize();

        assertTrue(second.isInitialized());
        assertTrue(second.loadAll().isEmpty());
        second.close();
    }

    @Test
    void auditedTransactionSurvivesDatabaseReload() throws Exception {
        Path db = dir.resolve("audited.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();
        UUID id = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        backend.append(new Transaction(id, account, null, "dollar",
                Fixtures.amt("10.00"), TransactionType.DEPOSIT,
                Fixtures.amt("0.00"), Fixtures.amt("10.00"),
                Instant.parse("2026-01-01T00:00:00Z"), "harbor-check",
                operator, "Morgan"));
        backend.close();

        SqlBackend reopened = backendFor(db);
        reopened.initialize();

        List<Transaction> all = reopened.loadAll();
        assertEquals(1, all.size());
        assertEquals(operator, all.get(0).operatorId());
        assertEquals("Morgan", all.get(0).operatorName());
        assertEquals("harbor-check", all.get(0).reason());
        reopened.close();
    }
}
