package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.json.SnapshotPreflight;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Operation-audit preflight must never echo a corrupt field back: a bad id,
 * timestamp, reason, operator or backup id carrying a fictitious secret must
 * be refused with a fixed diagnosis naming only the record index and the
 * field, with no raw value anywhere in the message, the cause chain or the
 * printed stack trace, while live state stays untouched and legacy snapshots
 * without the collection (or with legacy free-text transaction reasons) still
 * load.
 */
class OperationAuditDiagnosticSecretTest {

    private static final String ID_SECRET = "sk-fictitious-0701-alpha";
    private static final String TIME_SECRET = "sk-fictitious-0701-timestamp";
    private static final String REASON_SECRET = "fictitiouspassword0701";
    private static final String OPERATOR_SECRET = "ghp_fictitious0701beta";

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

    private static void assertRefusedWithoutEcho(String snapshot, String secret) {
        JsonModel model = JsonModel.fromJson(snapshot);
        PersistenceException refused = assertThrows(PersistenceException.class,
                () -> SnapshotPreflight.validateRecords(model),
                "a corrupt operation record must be refused");
        String exposed = chainText(refused);
        assertFalse(exposed.contains(secret),
                "the diagnosis must never echo the corrupt value, got: " + refused.getMessage());
    }

    @Test
    void badIdNeverEchoes() {
        String bad = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                .replaceFirst("\"id\":\"[^\"]+\"", "\"id\":\"" + ID_SECRET + "\""));
        assertRefusedWithoutEcho(bad, ID_SECRET);
    }

    @Test
    void badTimestampNeverEchoes() {
        String bad = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                .replace("2026-02-01T00:00:00Z", TIME_SECRET));
        assertRefusedWithoutEcho(bad, TIME_SECRET);
    }

    @Test
    void badReasonNeverEchoes() {
        String bad = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                .replace("\"reason\":\"harbor-restore\"", "\"reason\":\"" + REASON_SECRET + "\""));
        assertRefusedWithoutEcho(bad, REASON_SECRET);
    }

    @Test
    void badOperatorNeverEchoes() {
        String bad = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                .replace("\"operatorId\":null", "\"operatorId\":\"" + OPERATOR_SECRET + "\""));
        assertRefusedWithoutEcho(bad, OPERATOR_SECRET);
    }

    @Test
    void mistypedFieldNeverEchoes() {
        String secret = "sk-fictitious-0701-gamma";
        String bad = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                .replace("\"reason\":\"harbor-restore\"", "\"reason\":[\"" + secret + "\"]"));
        PersistenceException refused = assertThrows(PersistenceException.class,
                () -> JsonModel.fromJson(bad),
                "a mistyped operation field must be refused");
        assertFalse(chainText(refused).contains(secret),
                "the diagnosis must never echo the stored value, got: " + refused.getMessage());
    }

    @Test
    void refusedSnapshotLeavesLiveFileUntouched(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("live.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        try {
            UUID owner = UUID.randomUUID();
            backend.create(owner, "Alex",
                    Map.of("dollar", com.smile.aceeconomy.infrastructure.persistence.Fixtures.amt("100.00")));
            String before = Files.readString(file, StandardCharsets.UTF_8);

            String bad = snapshotWithAudit(validAuditJson(UUID.randomUUID())
                    .replace("2026-02-01T00:00:00Z", TIME_SECRET));
            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> backend.restore(new java.io.ByteArrayInputStream(
                            bad.getBytes(StandardCharsets.UTF_8))),
                    "a snapshot with a corrupt operation record must not restore");
            assertFalse(chainText(refused).contains(TIME_SECRET),
                    "the restore refusal must never echo the corrupt value");
            assertEquals(before, Files.readString(file, StandardCharsets.UTF_8),
                    "the live file must be byte-identical after a refused restore");
            assertEquals(0,
                    com.smile.aceeconomy.infrastructure.persistence.Fixtures.amt("100.00")
                            .compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
        } finally {
            backend.close();
        }
    }

    @Test
    void legacySnapshotsWithoutAuditsAndWithLegacyReasonsStillPass() {
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
                + "\"reason\":\"two words legacy reason\","
                + "\"reverted\":false"
                + "}]}";
        JsonModel model = JsonModel.fromJson(legacy);
        SnapshotPreflight.validateRecords(model);
    }
}
