package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.OperationAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restore-audit compatibility for the JSON backend.
 *
 * <p>The collection is additive: files written before operation tracking carry
 * no {@code operationAudits} section and must load with an empty list, never a
 * failure. Files carrying the section must round-trip the record intact, and
 * unknown keys inside the section must never break the current reader. The
 * stored backup file itself is never rewritten by a restore.
 */
final class OperationAuditJsonTest {

    @TempDir
    Path dir;

    private String fileWithAudits(UUID txId, UUID account, JsonModel.JsonOperationAudit audit) {
        String auditJson = "{\"id\":\"" + audit.id + "\","
                + "\"operation\":\"" + audit.operation + "\","
                + "\"operatorId\":" + (audit.operatorId == null ? "null" : "\"" + audit.operatorId + "\"") + ","
                + "\"operatorName\":\"" + audit.operatorName + "\","
                + "\"reason\":\"" + audit.reason + "\","
                + "\"timestamp\":\"" + audit.timestamp + "\","
                + "\"sourceBackupId\":\"" + audit.sourceBackupId + "\","
                + "\"safetyBackupId\":\"" + audit.safetyBackupId + "\"}";
        return "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[{"
                + "\"id\":\"" + txId + "\","
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

    @Test
    void missingCollectionLoadsAsEmptyInsteadOfFailing() throws Exception {
        Path file = dir.resolve("legacy-ops.json");
        UUID tx = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        Files.writeString(file, "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[{"
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
                + "}]}", StandardCharsets.UTF_8);

        JsonModel model = JsonModel.fromJson(Files.readString(file, StandardCharsets.UTF_8));

        assertEquals(1, model.transactions.size());
        assertTrue(model.operationAudits.isEmpty(),
                "a snapshot without the additive section must read as empty, never fail");
    }

    @Test
    void roundTripPreservesIndependentRecord() throws Exception {
        OperationAudit record = OperationAudit.restore(
                AdminAudit.console("harbor-restore"),
                Instant.parse("2026-02-01T00:00:00Z"),
                "seed-id", "safety-id");
        JsonModel.JsonOperationAudit json = JsonModel.JsonOperationAudit.fromDomain(record);
        Path file = dir.resolve("audited-ops.json");
        Files.writeString(file, fileWithAudits(UUID.randomUUID(), UUID.randomUUID(), json),
                StandardCharsets.UTF_8);

        JsonModel reloaded = JsonModel.fromJson(Files.readString(file, StandardCharsets.UTF_8));

        assertEquals(1, reloaded.operationAudits.size(),
                "the independent record must survive a file reload, not just memory");
        assertEquals("harbor-restore", reloaded.operationAudits.get(0).toDomain().reason());
        assertEquals("seed-id", reloaded.operationAudits.get(0).toDomain().sourceBackupId());
        assertEquals("safety-id", reloaded.operationAudits.get(0).toDomain().safetyBackupId());
    }

    @Test
    void unknownKeysInRecordDoNotBreakTheReader() throws Exception {
        Path file = dir.resolve("future-ops.json");
        UUID tx = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        Files.writeString(file, "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[{"
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
                + "}],\"operationAudits\":[{"
                + "\"id\":\"" + UUID.randomUUID() + "\","
                + "\"operation\":\"RESTORE\","
                + "\"operatorId\":null,"
                + "\"operatorName\":\"CONSOLE\","
                + "\"reason\":\"harbor-restore\","
                + "\"timestamp\":\"2026-02-01T00:00:00Z\","
                + "\"sourceBackupId\":\"seed-id\","
                + "\"safetyBackupId\":\"safety-id\","
                + "\"futureProbe\":\"from-a-newer-build\""
                + "}]}", StandardCharsets.UTF_8);

        JsonModel model = JsonModel.fromJson(Files.readString(file, StandardCharsets.UTF_8));

        assertEquals(1, model.transactions.size(),
                "unknown keys in the additive section must be ignored, never fatal");
    }
}
