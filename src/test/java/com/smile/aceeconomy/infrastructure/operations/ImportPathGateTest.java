package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.ports.operations.ImportSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red: every import read must pass the path gate first — the user path stays
 * inside {@code <dataFolder>/import}, absolute paths, {@code ..}, symlinks and
 * sensitive/unsupported files are rejected before anything is read.
 */
class ImportPathGateTest {

    private Path importDir(Path dataFolder) throws Exception {
        Path dir = dataFolder.resolve("import");
        Files.createDirectories(dir);
        return dir;
    }

    private void write(Path file, String content) throws Exception {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void relativeFileInsideImportResolves(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        write(dir.resolve("balances.csv"), "uuid,name,balance\n");

        Path resolved = ImportPathGate.resolve(dataFolder, "balances.csv", ImportSource.CMI);

        assertEquals(dir.resolve("balances.csv").toRealPath(), resolved.toRealPath());
    }

    @Test
    void nestedRelativeFileResolves(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Files.createDirectories(dir.resolve("ess"));
        write(dir.resolve("ess").resolve("a.yml"), "money: 1\n");

        Path resolved = ImportPathGate.resolve(dataFolder, "ess/a.yml", ImportSource.ESSENTIALS);

        assertTrue(resolved.toRealPath().startsWith(dir.toRealPath()));
    }

    @Test
    void directoryResolves(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Files.createDirectories(dir.resolve("userdata"));

        Path resolved = ImportPathGate.resolve(dataFolder, "userdata", ImportSource.ESSENTIALS);

        assertEquals(dir.resolve("userdata").toRealPath(), resolved.toRealPath());
    }

    @Test
    void absolutePathIsRejected(@TempDir Path dataFolder) throws Exception {
        importDir(dataFolder);

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "/etc/passwd", ImportSource.ESSENTIALS));
    }

    @Test
    void parentTraversalIsRejected(@TempDir Path dataFolder) throws Exception {
        importDir(dataFolder);

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "../config.yml", ImportSource.ESSENTIALS));
        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "ess/../../config.yml", ImportSource.ESSENTIALS));
        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "..\\config.yml", ImportSource.ESSENTIALS));
    }

    @Test
    void symlinkEscapeIsRejected(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path outside = dataFolder.resolve("outside.txt");
        write(outside, "secret");
        Path link = dir.resolve("evil.csv");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException e) {
            return;
        }

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "evil.csv", ImportSource.CMI));
    }

    @Test
    void symlinkedDirectoryIsRejected(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path target = dataFolder.resolve("realdir");
        Files.createDirectories(target);
        Path link = dir.resolve("linked");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException e) {
            return;
        }

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "linked", ImportSource.ESSENTIALS));
    }

    @Test
    void sensitiveNamesAreRejected(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        write(dir.resolve("config.yml"), "storage: {}\n");

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "config.yml", ImportSource.ESSENTIALS));
    }

    @Test
    void wrongExtensionForSourceIsRejected(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        write(dir.resolve("dump.db"), "binary");
        write(dir.resolve("notes.txt"), "hello");

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "dump.db", ImportSource.CMI));
        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "notes.txt", ImportSource.ESSENTIALS));
    }

    @Test
    void missingFileIsRejected(@TempDir Path dataFolder) throws Exception {
        importDir(dataFolder);

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "nope.csv", ImportSource.CMI));
    }

    @Test
    void blankPathIsRejected(@TempDir Path dataFolder) throws Exception {
        importDir(dataFolder);

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.resolve(dataFolder, "  ", ImportSource.CMI));
    }
}
