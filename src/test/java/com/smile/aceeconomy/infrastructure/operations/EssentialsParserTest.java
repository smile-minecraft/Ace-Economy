package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;
import com.smile.aceeconomy.operations.ImportParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red: EssentialsX userdata ({@code <uuid>.yml} with {@code money:} /
 * {@code last-account-name:}) must normalize to {@link ImportRecord} values.
 */
class EssentialsParserTest {

    private static final UUID ALEX = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID BLOB = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    private void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void directoryOfUserdataNormalizesToRecords(@TempDir Path dir) throws Exception {
        write(dir, ALEX + ".yml", "last-account-name: Alex\nmoney: '1234.56'\n");
        write(dir, BLOB + ".yml", "last-account-name: Blob\nmoney: 100\n");

        ImportParseResult result = EssentialsParser.parse(dir, "coin", 2);

        assertTrue(result.failures().isEmpty(), "expected no failures: " + result.failures());
        assertEquals(2, result.records().size());
        Map<UUID, ImportRecord> byAccount = result.records().stream()
                .collect(Collectors.toMap(ImportRecord::accountUuid, r -> r));
        assertEquals(Amount.of(new BigDecimal("1234.56"), 2), byAccount.get(ALEX).amount());
        assertEquals("Alex", byAccount.get(ALEX).ownerName());
        assertEquals(Amount.of(100, 2), byAccount.get(BLOB).amount());
        for (ImportRecord record : result.records()) {
            assertEquals(ImportSource.ESSENTIALS, record.source());
            assertEquals("coin", record.currencyId());
            assertEquals(record.accountUuid().toString(), record.sourceRecordId());
        }
    }

    @Test
    void singleFileParses(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(ALEX + ".yml");
        write(dir, ALEX + ".yml", "money: 50.5\nlast-account-name: Alex\n");

        ImportParseResult result = EssentialsParser.parse(file, "coin", 2);

        assertTrue(result.failures().isEmpty(), "expected no failures: " + result.failures());
        assertEquals(1, result.records().size());
        assertEquals(ALEX, result.records().get(0).accountUuid());
    }

    @Test
    void missingNameFallsBackToNullOwner(@TempDir Path dir) throws Exception {
        write(dir, ALEX + ".yml", "money: 10\n");

        ImportParseResult result = EssentialsParser.parse(dir, "coin", 2);

        assertTrue(result.failures().isEmpty(), "expected no failures: " + result.failures());
        assertEquals(1, result.records().size());
        assertNull(result.records().get(0).ownerName(),
                "a missing name must stay null so the service can fall back to the uuid string");
    }

    @Test
    void missingMoneyIsAFailureNotAGuess(@TempDir Path dir) throws Exception {
        write(dir, ALEX + ".yml", "last-account-name: Alex\n");
        write(dir, BLOB + ".yml", "last-account-name: Blob\nmoney: 5\n");

        ImportParseResult result = EssentialsParser.parse(dir, "coin", 2);

        assertEquals(1, result.records().size(), "the valid file must still parse");
        assertEquals(1, result.failures().size(), "missing money must fail closed, not default to zero");
        assertTrue(result.failures().get(0).contains(ALEX + ".yml"), "failure must name the file");
    }

    @Test
    void invalidNumberIsAFailure(@TempDir Path dir) throws Exception {
        write(dir, ALEX + ".yml", "last-account-name: Alex\nmoney: 'not-a-number'\n");

        ImportParseResult result = EssentialsParser.parse(dir, "coin", 2);

        assertTrue(result.records().isEmpty());
        assertEquals(1, result.failures().size());
    }

    @Test
    void negativeBalancePassesThroughForPerRecordHandling(@TempDir Path dir) throws Exception {
        write(dir, ALEX + ".yml", "last-account-name: Alex\nmoney: -25.5\n");

        ImportParseResult result = EssentialsParser.parse(dir, "coin", 2);

        assertTrue(result.failures().isEmpty(), "expected no failures: " + result.failures());
        assertEquals(1, result.records().size());
        assertTrue(result.records().get(0).amount().isNegative(),
                "negatives stay in the record so ImportService can fail them per-record");
    }

    @Test
    void nonUuidFilenameFailsClosed(@TempDir Path dir) throws Exception {
        write(dir, "steve.yml", "last-account-name: Steve\nmoney: 10\n");

        ImportParseResult result = EssentialsParser.parse(dir, "coin", 2);

        assertTrue(result.records().isEmpty());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).contains("steve.yml"));
    }

    @Test
    void unknownShapeFailsClosed(@TempDir Path dir) throws Exception {
        write(dir, ALEX + ".yml", "nickname: Alex\nkit-last-use: 123\n");

        ImportParseResult result = EssentialsParser.parse(dir, "coin", 2);

        assertTrue(result.records().isEmpty());
        assertEquals(1, result.failures().size());
    }
}
