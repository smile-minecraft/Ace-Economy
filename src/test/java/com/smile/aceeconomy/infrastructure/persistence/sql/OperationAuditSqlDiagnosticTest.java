package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.OperationAudit;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A damaged operation-audit row must be refused without echoing the stored
 * value: a bad id, timestamp or operator id carrying a fictitious secret is
 * diagnosed with only the row index and the field, with no raw value in the
 * message, the cause chain or the printed stack, while a normal row still
 * loads.
 */
final class OperationAuditSqlDiagnosticTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    private static final String ID_SECRET = "sk-fictitious-sql-id-0701";
    private static final String TIME_SECRET = "sk-fictitious-sql-time-0701";
    private static final String OPERATOR_SECRET = "ghp_fictitioussql0701beta";
    private static final String REASON_SECRET = "fictitiouspassword-sql-0701";
    private static final String SOURCE_SECRET = "src-fictitious-sql-0701";
    private static final String SAFETY_SECRET = "safety-fictitious-sql-0701";

    @TempDir
    Path dir;

    private SqlBackend backendFor(Path dbFile) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        return new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
    }

    private static String chainText(Throwable t) {
        StringBuilder out = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            out.append(String.valueOf(c.getMessage())).append('\n');
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        out.append(sw);
        return out.toString();
    }

    private void insertAuditRow(Path db, String id, String operatorId, String reason,
                                String timestamp, String source, String safety) throws SQLException {
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db);
             var st = raw.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + V2Schema.operationAuditsTable()
                    + " (id TEXT PRIMARY KEY, operation TEXT NOT NULL,"
                    + " operator_id TEXT, operator_name TEXT NOT NULL,"
                    + " reason TEXT NOT NULL, timestamp TEXT NOT NULL,"
                    + " source_backup_id TEXT NOT NULL, safety_backup_id TEXT NOT NULL)");
            try (var ps = raw.prepareStatement("INSERT INTO " + V2Schema.operationAuditsTable()
                    + " (id, operation, operator_id, operator_name, reason,"
                    + " timestamp, source_backup_id, safety_backup_id)"
                    + " VALUES (?, 'RESTORE', ?, 'CONSOLE', ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, operatorId);
                ps.setString(3, reason);
                ps.setString(4, timestamp);
                ps.setString(5, source);
                ps.setString(6, safety);
                ps.executeUpdate();
            }
        }
    }

    private PersistenceException readBadRow(Path db) throws Exception {
        SqlBackend backend = backendFor(db);
        backend.initialize();
        try {
            return assertThrows(PersistenceException.class, backend::listOperationAudits,
                    "a damaged operation row must be refused");
        } finally {
            backend.close();
        }
    }

    private static void assertNoSecret(PersistenceException refused, String... secrets) {
        String exposed = chainText(refused);
        for (String secret : secrets) {
            assertFalse(exposed.contains(secret),
                    "the diagnosis must never echo the stored value, got: " + refused.getMessage());
        }
        assertNull(refused.getCause(),
                "the parsing failure must not travel as a cause, got: " + refused.getMessage());
    }

    @Test
    void badIdNeverEchoes() throws Exception {
        Path db = dir.resolve("audit-bad-id.db");
        SqlBackend setup = backendFor(db);
        setup.initialize();
        setup.close();
        insertAuditRow(db, ID_SECRET, null, REASON_SECRET,
                "2026-02-01T00:00:00Z", SOURCE_SECRET, SAFETY_SECRET);

        PersistenceException refused = readBadRow(db);

        assertNoSecret(refused, ID_SECRET, REASON_SECRET, SOURCE_SECRET, SAFETY_SECRET);
        assertTrue(refused.getMessage().contains("at index 0"),
                "the diagnosis must keep the row position, got: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("malformed id")
                        || refused.getMessage().contains("non-canonical id"),
                "the diagnosis must name the id field, got: " + refused.getMessage());
    }

    @Test
    void badTimestampNeverEchoes() throws Exception {
        Path db = dir.resolve("audit-bad-time.db");
        SqlBackend setup = backendFor(db);
        setup.initialize();
        setup.close();
        insertAuditRow(db, "550e8400-e29b-41d4-a716-446655440000", null, REASON_SECRET,
                TIME_SECRET, SOURCE_SECRET, SAFETY_SECRET);

        PersistenceException refused = readBadRow(db);

        assertNoSecret(refused, TIME_SECRET, REASON_SECRET, SOURCE_SECRET, SAFETY_SECRET);
        assertTrue(refused.getMessage().contains("at index 0"),
                "the diagnosis must keep the row position, got: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("malformed timestamp"),
                "the diagnosis must name the timestamp field, got: " + refused.getMessage());
    }

    @Test
    void badOperatorNeverEchoes() throws Exception {
        Path db = dir.resolve("audit-bad-operator.db");
        SqlBackend setup = backendFor(db);
        setup.initialize();
        setup.close();
        insertAuditRow(db, "550e8400-e29b-41d4-a716-446655440001", OPERATOR_SECRET, REASON_SECRET,
                "2026-02-01T00:00:00Z", SOURCE_SECRET, SAFETY_SECRET);

        PersistenceException refused = readBadRow(db);

        assertNoSecret(refused, OPERATOR_SECRET, REASON_SECRET, SOURCE_SECRET, SAFETY_SECRET);
        assertTrue(refused.getMessage().contains("at index 0"),
                "the diagnosis must keep the row position, got: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("malformed operator id")
                        || refused.getMessage().contains("non-canonical operator id"),
                "the diagnosis must name the operator field, got: " + refused.getMessage());
    }

    @Test
    void normalRecordStillLoads() throws Exception {
        Path db = dir.resolve("audit-normal.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();
        backend.close();
        insertAuditRow(db, "550e8400-e29b-41d4-a716-446655440002", null, "harbor-restore-sql",
                "2026-02-01T00:00:00Z", "seed-sql", "safety-sql");

        SqlBackend reopened = backendFor(db);
        reopened.initialize();
        try {
            List<OperationAudit> audits = reopened.listOperationAudits();
            assertEquals(1, audits.size(), "a normal row must still load");
            assertEquals("harbor-restore-sql", audits.get(0).reason());
        } finally {
            reopened.close();
        }
    }
}
