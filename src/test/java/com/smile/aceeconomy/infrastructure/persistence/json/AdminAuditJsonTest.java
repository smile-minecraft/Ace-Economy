package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operator-audit compatibility for the JSON backend: files written before
 * operator tracking keep loading with the audit marked unrecorded, unknown
 * audit keys never break the current reader, and a newly written audit
 * survives a file reload intact.
 */
final class AdminAuditJsonTest {

    @TempDir
    Path dir;

    private JsonPersistenceBackend backendAt(Path file) {
        return new JsonPersistenceBackend(file);
    }

    private String legacyFile(UUID id, UUID account, String extraKeys) {
        return "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[{"
                + "\"id\":\"" + id + "\","
                + "\"accountId\":\"" + account + "\","
                + "\"counterparty\":null,"
                + "\"currencyId\":\"dollar\","
                + "\"amount\":\"10.00\","
                + "\"type\":\"DEPOSIT\","
                + "\"balanceBefore\":\"0.00\","
                + "\"balanceAfter\":\"10.00\","
                + "\"timestamp\":\"2026-01-01T00:00:00Z\","
                + "\"reason\":\"deposit\","
                + extraKeys
                + "\"reverted\":false"
                + "}]}";
    }

    @Test
    void legacyFileWithoutAuditFieldsLoadsAsUnrecorded() throws Exception {
        Path file = dir.resolve("legacy.json");
        UUID id = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        Files.writeString(file, legacyFile(id, account, ""), StandardCharsets.UTF_8);

        JsonPersistenceBackend backend = backendAt(file);
        backend.initialize();

        List<Transaction> all = backend.loadAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        assertNull(all.get(0).operatorId(), "legacy rows must read as unrecorded, not fail");
        assertNull(all.get(0).operatorName());
    }

    @Test
    void unknownAuditKeysDoNotBreakTheCurrentReader() throws Exception {
        Path file = dir.resolve("future.json");
        UUID id = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        Files.writeString(file, legacyFile(id, account,
                "\"operatorId\":\"" + operator + "\","
                        + "\"operatorName\":\"Morgan\","),
                StandardCharsets.UTF_8);

        JsonPersistenceBackend backend = backendAt(file);
        backend.initialize();

        assertEquals(1, backend.loadAll().size(),
                "a file carrying audit keys must still load on a reader that predates them");
    }

    @Test
    void auditedTransactionSurvivesFileReload() throws Exception {
        Path file = dir.resolve("audited.json");
        JsonPersistenceBackend backend = backendAt(file);
        backend.initialize();
        UUID id = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        Transaction audited = new Transaction(id, account, null, "dollar",
                Fixtures.amt("10.00"), TransactionType.DEPOSIT,
                Fixtures.amt("0.00"), Fixtures.amt("10.00"),
                Instant.parse("2026-01-01T00:00:00Z"), "harbor-check",
                operator, "Morgan");
        backend.append(audited);

        JsonPersistenceBackend reloaded = backendAt(file);
        reloaded.initialize();

        List<Transaction> all = reloaded.loadAll();
        assertEquals(1, all.size());
        assertEquals(operator, all.get(0).operatorId());
        assertEquals("Morgan", all.get(0).operatorName());
        assertEquals("harbor-check", all.get(0).reason());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains(operator.toString()),
                "the audit identity must be part of the persisted file, not memory only");
    }
}
