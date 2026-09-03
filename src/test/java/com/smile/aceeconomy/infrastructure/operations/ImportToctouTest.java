package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.operations.ImportParseResult;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TOCTOU reproduction: the path gate checks first, the parser reads later.
 * Anything swapped in between (replaced directory, file turned into a
 * directory, file turned into a symlink) must be rejected — the parser must
 * never return content the gate never approved.
 */
class ImportToctouTest {

    private static final UUID ATTACKER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID VICTIM = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private Path importDir(Path dataFolder) throws Exception {
        Path dir = dataFolder.resolve("import");
        Files.createDirectories(dir);
        return dir;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static boolean containsAttacker(ImportParseResult result) {
        return result.records().stream()
                .map(ImportRecord::accountUuid)
                .anyMatch(ATTACKER::equals);
    }

    @Test
    void replacedDirectoryMustNotLeakAttackerRecords(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("benign.csv"),
                "uuid,name,balance\n" + VICTIM + ",Victim,10\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "sheets", ImportSource.CMI);

        // Gate passed on the benign directory; now the whole directory is swapped
        // for one carrying attacker content before the parser reads.
        deleteTree(sheets);
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("evil.csv"),
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);

        ImportParseResult result = CmiParser.parse(gated, "coin", 2);

        assertTrue(!containsAttacker(result),
                "parser must not return records from a directory swapped in after the gate, got: "
                        + result.records());
        assertTrue(!result.failures().isEmpty(), "the swap must be reported, got: " + result);
    }

    @Test
    void fileReplacedByDirectoryMustNotParse(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path target = dir.resolve("balances.csv");
        Files.writeString(target,
                "uuid,name,balance\n" + VICTIM + ",Victim,10\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "balances.csv", ImportSource.CMI);

        // Gate approved a single file; it is replaced by a directory of attacker sheets.
        Files.delete(target);
        Files.createDirectories(target);
        Files.writeString(target.resolve("evil.csv"),
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);

        ImportParseResult result = CmiParser.parse(gated, "coin", 2);

        assertTrue(!containsAttacker(result),
                "a file approved by the gate must not turn into a parsed directory, got: "
                        + result.records());
        assertTrue(!result.failures().isEmpty(), "the swap must be reported, got: " + result);
    }

    @Test
    void essentialsFileReplacedByDirectoryMustNotParse(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path target = dir.resolve(VICTIM + ".yml");
        Files.writeString(target, "money: 10\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated =
                ImportPathGate.gate(dataFolder, VICTIM + ".yml", ImportSource.ESSENTIALS);

        Files.delete(target);
        Files.createDirectories(target);
        Files.writeString(target.resolve(ATTACKER + ".yml"), "money: 999999\n", StandardCharsets.UTF_8);

        ImportParseResult result = EssentialsParser.parse(gated, "coin", 2);

        assertTrue(!containsAttacker(result),
                "a file approved by the gate must not turn into a parsed directory, got: "
                        + result.records());
        assertTrue(!result.failures().isEmpty(), "the swap must be reported, got: " + result);
    }

    @Test
    void fileSwappedToSymlinkMustNotReadOutside(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path target = dir.resolve("balances.csv");
        Files.writeString(target,
                "uuid,name,balance\n" + VICTIM + ",Victim,10\n", StandardCharsets.UTF_8);
        Path outside = dataFolder.resolve("outside.csv");
        Files.writeString(outside,
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "balances.csv", ImportSource.CMI);

        Files.delete(target);
        try {
            Files.createSymbolicLink(target, outside);
        } catch (UnsupportedOperationException e) {
            return;
        }

        // The gate itself must refuse the swapped path on re-check ...
        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.gate(dataFolder, "balances.csv", ImportSource.CMI));
        // ... and the parser must not follow it to the outside file either.
        ImportParseResult result = CmiParser.parse(gated, "coin", 2);
        assertTrue(!containsAttacker(result),
                "parser must not follow a symlink swapped in after the gate, got: "
                        + result.records());
        assertEquals(0, result.records().size(),
                "swapped symlink must yield no records, got: " + result.records());
    }
}
