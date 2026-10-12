package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.OperationAudit;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.json.SnapshotPreflight;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joint restore preflight over the operation-audit collection: a snapshot
 * carrying a corrupt operation record must be refused before any live state
 * moves, on both real backends, while snapshots without the collection stay
 * compatible.
 */
class OperationAuditPreflightTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    private static String snapshotWithAudit(String auditJson) {
        UUID tx = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        return "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[{"
                + "\"id\":\"" + tx + "\","
                + "\"accountId\":\"" + account + "\","
                + "\"counterparty\":null,"
                + "\"currencyId\":\"dollar\","
                + "\"amount\":\"10.00\","
                + "\"type\":\"DEPOSIT\","
                + "\"balanceBefore\":\"0.00\","
                + "\"balanceAfter\":\"10.00\","
                + "\"timestamp\":\"2026-01-01T00:00:00Z\","
                + "\"reason\":\"deposit\","
                + "\"reverted\":false"
                + "}],\"operationAudits\":[" + auditJson + "]}";
    }

    private static String validAuditJson(UUID id) {
        return "{\"id\":\"" + id + "\","
                + "\"operation\":\"RESTORE\","
                + "\"operatorId\":null,"
                + "\"operatorName\":\"CONSOLE\","
                + "\"reason\":\"harbor-restore\","
                + "\"timestamp\":\"2026-02-01T00:00:00Z\","
                + "\"sourceBackupId\":\"seed-id\","
                + "\"safetyBackupId\":\"safety-id\"}";
    }

    @Test
    void preflightRejectsBadOperationIdBeforeAnyWrite() {
        String bad = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                .replaceFirst("\"id\":\"[^\"]+\"", "\"id\":\"not-a-uuid\""));
        JsonModel model = JsonModel.fromJson(bad);
        assertThrows(com.smile.aceeconomy.ports.persistence.PersistenceException.class,
                () -> SnapshotPreflight.validateRecords(model),
                "a corrupt operation id must fail the joint preflight");
    }

    @Test
    void preflightRejectsBadOperationTimestamp() {
        UUID id = UUID.randomUUID();
        String bad = snapshotWithAudit(validAuditJson(id)
                .replace("2026-02-01T00:00:00Z", "not-a-time"));
        JsonModel model = JsonModel.fromJson(bad);
        assertThrows(com.smile.aceeconomy.ports.persistence.PersistenceException.class,
                () -> SnapshotPreflight.validateRecords(model),
                "a corrupt operation timestamp must fail the joint preflight");
    }

    @Test
    void preflightRejectsDuplicateOperationIds() {
        UUID id = UUID.randomUUID();
        String snapshot = snapshotWithAudit(validAuditJson(id) + "," + validAuditJson(id));
        JsonModel model = JsonModel.fromJson(snapshot);
        assertThrows(com.smile.aceeconomy.ports.persistence.PersistenceException.class,
                () -> SnapshotPreflight.validateRecords(model),
                "duplicate operation ids must fail the joint preflight");
    }

    @Test
    void preflightRejectsBlankBackupIdAndInconsistentIdentity() {
        UUID id = UUID.randomUUID();
        String blankSource = snapshotWithAudit(validAuditJson(id)
                .replace("\"sourceBackupId\":\"seed-id\"", "\"sourceBackupId\":\"\""));
        assertThrows(com.smile.aceeconomy.ports.persistence.PersistenceException.class,
                () -> SnapshotPreflight.validateRecords(JsonModel.fromJson(blankSource)),
                "a blank backup id must fail the joint preflight");

        String inconsistent = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                .replace("\"operatorName\":\"CONSOLE\"", "\"operatorName\":\"Morgan\""));
        assertThrows(com.smile.aceeconomy.ports.persistence.PersistenceException.class,
                () -> SnapshotPreflight.validateRecords(JsonModel.fromJson(inconsistent)),
                "a null operator id with a player name must fail the joint preflight");
    }

    @Test
    void jsonRestoreWithBadOperationAuditLeavesLiveFileUntouched(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("live-ops.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        try {
            String liveBefore = Files.readString(file, StandardCharsets.UTF_8);
            String bad = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                    .replaceFirst("\"id\":\"[^\"]+\"", "\"id\":\"not-a-uuid\""));
            assertThrows(com.smile.aceeconomy.ports.persistence.PersistenceException.class,
                    () -> backend.restore(
                            new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8))),
                    "a corrupt operation audit must fail the JSON restore closed");
            assertEquals(liveBefore, Files.readString(file, StandardCharsets.UTF_8),
                    "a refused restore must leave the live file bytes unchanged");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqlRestoreWithBadOperationAuditLeavesLiveStateUntouched(@TempDir Path dir)
            throws Exception {
        Path db = dir.resolve("live-ops.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        try {
            int txBefore = backend.loadAll().size();
            int auditsBefore = backend.listOperationAudits().size();
            String bad = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                    .replace("2026-02-01T00:00:00Z", "not-a-time"));
            assertThrows(com.smile.aceeconomy.ports.persistence.PersistenceException.class,
                    () -> backend.restore(
                            new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8))),
                    "a corrupt operation audit must fail the SQL restore closed");
            assertEquals(txBefore, backend.loadAll().size(),
                    "a refused restore must leave live transactions untouched");
            assertEquals(auditsBefore, backend.listOperationAudits().size(),
                    "a refused restore must leave live operation audits untouched");
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // Verification above is authoritative.
            }
        }
    }

    @Test
    void snapshotWithoutCollectionStaysCompatible() {
        UUID tx = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        String legacy = "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[{"
                + "\"id\":\"" + tx + "\","
                + "\"accountId\":\"" + account + "\","
                + "\"counterparty\":null,"
                + "\"currencyId\":\"dollar\","
                + "\"amount\":\"10.00\","
                + "\"type\":\"DEPOSIT\","
                + "\"balanceBefore\":\"0.00\","
                + "\"balanceAfter\":\"10.00\","
                + "\"timestamp\":\"2026-01-01T00:00:00Z\","
                + "\"reason\":\"deposit\","
                + "\"reverted\":false"
                + "}]}";
        JsonModel model = JsonModel.fromJson(legacy);
        SnapshotPreflight.validateRecords(model);
        assertTrue(model.operationAudits.isEmpty(),
                "a snapshot without the additive section must stay readable as empty");
    }

    @Test
    void corruptOperationRecordSurfacesAsPersistenceDiagnosisNotRawException() {
        String bad = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                .replaceFirst("\"id\":\"[^\"]+\"", "\"id\":\"not-a-uuid\""));
        JsonModel model = JsonModel.fromJson(bad);
        try {
            SnapshotPreflight.validateRecords(model);
            throw new AssertionError("a corrupt operation id must not validate silently");
        } catch (com.smile.aceeconomy.ports.persistence.PersistenceException expected) {
            assertTrue(expected.getMessage().toLowerCase(java.util.Locale.ROOT)
                    .contains("operation audit"),
                    "the diagnosis must name the operation audit");
        }
        OperationAudit record = OperationAudit.restore(
                AdminAudit.console("harbor-restore"), Instant.parse("2026-02-01T00:00:00Z"),
                "seed-id", "safety-id");
        assertEquals("harbor-restore", record.reason());
    }
}
