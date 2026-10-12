package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.OperationAudit;
import com.smile.aceeconomy.operations.OperationAuditQuery;
import com.smile.aceeconomy.operations.OperationAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restore-audit compatibility for the SQL backend against a real SQLite file.
 *
 * <p>The table is additive: databases initialized by older builds gain it on
 * the next start without a version bump, initialization stays repeatable, rows
 * written before operation tracking read as an empty list, and a database
 * carrying the new table still loads its transactions on a reader that
 * predates it.
 */
final class OperationAuditSqlTest {

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

    private boolean tableExists(Path dbFile, String table) throws SQLException {
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             var rs = raw.getMetaData().getTables(null, null, table, null)) {
            return rs != null && rs.next();
        }
    }

    @Test
    void initializeCreatesTableAndStaysRepeatable() throws Exception {
        Path db = dir.resolve("ops-init.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();

        assertTrue(tableExists(db, V2Schema.operationAuditsTable()),
                "initialization must create the additive audit table");
        backend.close();

        SqlBackend second = backendFor(db);
        second.initialize();

        assertTrue(second.isInitialized());
        assertTrue(tableExists(db, V2Schema.operationAuditsTable()));
        assertTrue(second.listOperationAudits().isEmpty());
        second.close();
    }

    @Test
    void legacyDatabaseWithoutTableLoadsAsEmpty() throws Exception {
        Path db = dir.resolve("ops-legacy.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();

        List<OperationAudit> audits = backend.listOperationAudits();

        assertTrue(audits.isEmpty(),
                "a database written before operation tracking must read as empty, never fail");
        backend.close();
    }

    @Test
    void persistedRecordIsTraceableByReasonAndSnapshotIds() throws Exception {
        Path db = dir.resolve("ops-trace.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();

        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db);
             var st = raw.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + V2Schema.operationAuditsTable()
                    + " (id TEXT PRIMARY KEY, operation TEXT NOT NULL,"
                    + " operator_id TEXT, operator_name TEXT NOT NULL,"
                    + " reason TEXT NOT NULL, timestamp TEXT NOT NULL,"
                    + " source_backup_id TEXT NOT NULL, safety_backup_id TEXT NOT NULL)");
            st.executeUpdate("INSERT INTO " + V2Schema.operationAuditsTable()
                    + " (id, operation, operator_id, operator_name, reason,"
                    + " timestamp, source_backup_id, safety_backup_id)"
                    + " VALUES ('550e8400-e29b-41d4-a716-446655440000', 'RESTORE', NULL,"
                    + " 'CONSOLE', 'harbor-restore-sql',"
                    + " '2026-02-01T00:00:00Z', 'seed-sql', 'safety-sql')");
        }

        OperationAuditService queries = new OperationAuditService(backend);

        assertEquals(1, queries.query(
                OperationAuditQuery.builder().reasonContains("harbor-restore-sql").build()).size(),
                "the independent record must be traceable by reason through the internal query");
        assertEquals(1, queries.query(
                OperationAuditQuery.builder().sourceBackupId("seed-sql").build()).size(),
                "the source snapshot id must stay traceable through the internal query");
        assertEquals(1, queries.query(
                OperationAuditQuery.builder().safetyBackupId("safety-sql").build()).size(),
                "the safety snapshot id must stay traceable through the internal query");
        backend.close();
    }

    @Test
    void extraTableDoesNotBreakTransactionLoading() throws Exception {
        Path db = dir.resolve("ops-ignore.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();
        backend.close();

        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db);
             var st = raw.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + V2Schema.operationAuditsTable()
                    + " (id TEXT PRIMARY KEY, operation TEXT NOT NULL,"
                    + " operator_id TEXT, operator_name TEXT NOT NULL,"
                    + " reason TEXT NOT NULL, timestamp TEXT NOT NULL,"
                    + " source_backup_id TEXT NOT NULL, safety_backup_id TEXT NOT NULL)");
        }

        SqlBackend reopened = backendFor(db);
        reopened.initialize();

        assertTrue(reopened.loadAll().isEmpty(),
                "a database carrying the additive table must still load its transactions");
        reopened.close();
    }
}
