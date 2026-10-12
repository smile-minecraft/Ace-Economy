package com.smile.aceeconomy.infrastructure.persistence.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Forward-compatibility evidence for the JSON reader: keys the current build
 * never wrote still load instead of failing. The audit keys are one instance
 * of this general rule — the reader only looks up the keys it knows
 * ({@code JsonModel.fromJson} uses {@code Map.get} per known field and never
 * rejects extras), so a file carrying operator fields loads on a reader that
 * predates them, and a file carrying still-unknown future keys loads today.
 */
final class AdminAuditFutureKeyTest {

    @TempDir
    Path dir;

    private String fileWith(UUID id, UUID account, String extraKeys) {
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
    void futureUnknownKeysDoNotBreakTheReader() throws Exception {
        Path file = dir.resolve("future.json");
        Files.writeString(file, fileWith(UUID.randomUUID(), UUID.randomUUID(),
                "\"operatorId\":\"" + UUID.randomUUID() + "\","
                        + "\"operatorName\":\"Morgan\","
                        + "\"futureAuditNote\":\"from-a-newer-build\","
                        + "\"anotherFutureKey\":42,"),
                StandardCharsets.UTF_8);

        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();

        assertEquals(1, backend.loadAll().size(),
                "unknown keys from a newer build must be ignored, never fatal");
    }

    @Test
    void futureTopLevelKeysDoNotBreakTheReader() throws Exception {
        Path file = dir.resolve("future-top.json");
        UUID id = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        String base = fileWith(id, account, "");
        String withFuture = base.substring(0, base.length() - 1)
                + ",\"restoreAudit\":{\"by\":\"CONSOLE\",\"reason\":\"harbor-restore\"}}";

        Files.writeString(file, withFuture, StandardCharsets.UTF_8);

        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();

        assertEquals(1, backend.loadAll().size(),
                "an unknown top-level section must be ignored, never fatal");
        assertEquals(id, backend.loadAll().get(0).id());
    }
}
