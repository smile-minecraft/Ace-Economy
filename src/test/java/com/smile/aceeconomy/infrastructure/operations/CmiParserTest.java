package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;
import com.smile.aceeconomy.operations.ImportParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red: the v1 CMI input is an operator-prepared balance sheet (CSV
 * {@code uuid,name,balance}, header optional). A raw {@code cmi.sqlite.db}
 * binary is never parsed — the path gate rejects it before this parser runs.
 */
class CmiParserTest {

    private static final UUID ALEX = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID BLOB = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    private Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void csvWithHeaderNormalizesToRecords(@TempDir Path dir) throws Exception {
        Path file = write(dir, "balances.csv",
                "uuid,name,balance\n" + ALEX + ",Alex,1234.56\n" + BLOB + ",Blob,100\n");

        ImportParseResult result = CmiParser.parse(file, "coin", 2);

        assertTrue(result.failures().isEmpty(), "expected no failures: " + result.failures());
        assertEquals(2, result.records().size());
        ImportRecord alex = result.records().get(0);
        assertEquals(ImportSource.CMI, alex.source());
        assertEquals(ALEX, alex.accountUuid());
        assertEquals("Alex", alex.ownerName());
        assertEquals(ALEX.toString(), alex.sourceRecordId());
        assertEquals("coin", alex.currencyId());
        assertEquals("1234.56", alex.amount().value().toPlainString());
    }

    @Test
    void csvWithoutHeaderParses(@TempDir Path dir) throws Exception {
        Path file = write(dir, "balances.csv", ALEX + ",Alex,42.5\n");

        ImportParseResult result = CmiParser.parse(file, "coin", 2);

        assertTrue(result.failures().isEmpty(), "expected no failures: " + result.failures());
        assertEquals(1, result.records().size());
    }

    @Test
    void commentsAndBlankLinesAreSkipped(@TempDir Path dir) throws Exception {
        Path file = write(dir, "balances.csv",
                "# exported balances\n\n" + ALEX + ",Alex,10\n\n");

        ImportParseResult result = CmiParser.parse(file, "coin", 2);

        assertTrue(result.failures().isEmpty(), "expected no failures: " + result.failures());
        assertEquals(1, result.records().size());
    }

    @Test
    void missingNameFallsBackToNullOwner(@TempDir Path dir) throws Exception {
        Path file = write(dir, "balances.csv", ALEX + ",,10\n");

        ImportParseResult result = CmiParser.parse(file, "coin", 2);

        assertTrue(result.failures().isEmpty(), "expected no failures: " + result.failures());
        assertEquals(1, result.records().size());
        assertNull(result.records().get(0).ownerName());
    }

    @Test
    void invalidUuidLineIsAFailure(@TempDir Path dir) throws Exception {
        Path file = write(dir, "balances.csv",
                "uuid,name,balance\nnot-a-uuid,Steve,10\n" + ALEX + ",Alex,10\n");

        ImportParseResult result = CmiParser.parse(file, "coin", 2);

        assertEquals(1, result.records().size(), "the valid line must still parse");
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).contains("line 2"), "failure must carry the line number");
    }

    @Test
    void invalidNumberIsAFailure(@TempDir Path dir) throws Exception {
        Path file = write(dir, "balances.csv", ALEX + ",Alex,ten\n");

        ImportParseResult result = CmiParser.parse(file, "coin", 2);

        assertTrue(result.records().isEmpty());
        assertEquals(1, result.failures().size());
    }

    @Test
    void negativeBalancePassesThroughForPerRecordHandling(@TempDir Path dir) throws Exception {
        Path file = write(dir, "balances.csv", ALEX + ",Alex,-5\n");

        ImportParseResult result = CmiParser.parse(file, "coin", 2);

        assertTrue(result.failures().isEmpty(), "expected no failures: " + result.failures());
        assertEquals(1, result.records().size());
        assertTrue(result.records().get(0).amount().isNegative());
    }

    @Test
    void wrongColumnCountIsAFailure(@TempDir Path dir) throws Exception {
        Path file = write(dir, "balances.csv", ALEX + ",Alex\n");

        ImportParseResult result = CmiParser.parse(file, "coin", 2);

        assertTrue(result.records().isEmpty());
        assertEquals(1, result.failures().size());
    }
}
