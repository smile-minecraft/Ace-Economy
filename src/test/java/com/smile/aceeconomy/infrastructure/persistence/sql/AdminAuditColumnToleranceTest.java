package com.smile.aceeconomy.infrastructure.persistence.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.smile.aceeconomy.domain.Transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Forward-compatibility evidence for the SQL reader: columns the current
 * build never wrote do not break loading. Reads use {@code SELECT *} but map
 * only the columns they know by name, so a database carrying additional
 * operator columns (or any other future column) still loads on a reader that
 * predates them, with the known fields intact.
 */
final class AdminAuditColumnToleranceTest {

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

    @Test
    void extraFutureColumnsDoNotBreakLoading() throws Exception {
        Path db = dir.resolve("future.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();

        UUID id = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (var st = raw.createStatement()) {
                st.executeUpdate("ALTER TABLE " + V2Schema.transactionsTable()
                        + " ADD COLUMN future_probe TEXT");
            }
            try (PreparedStatement ps = raw.prepareStatement(
                    "INSERT INTO " + V2Schema.transactionsTable()
                            + " (id, account_id, counterparty, currency_id, amount, type,"
                            + " balance_before, balance_after, timestamp, reason, reverted,"
                            + " future_probe)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
                ps.setString(12, "from-a-newer-build");
                ps.executeUpdate();
            }
        }

        List<Transaction> all = backend.loadAll();

        assertEquals(1, all.size(),
                "a row carrying a future column must still load on a reader that predates it");
        assertEquals(id, all.get(0).id());
        assertEquals("deposit", all.get(0).reason());
        backend.close();
    }
}
