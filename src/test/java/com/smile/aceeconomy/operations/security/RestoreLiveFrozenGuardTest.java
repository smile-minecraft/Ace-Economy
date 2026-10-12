package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BlockedControlAttempt;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.security.FileBlockedAttemptJournal;
import com.smile.aceeconomy.operations.BackupRestoreService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restore that would delete a live frozen account is refused as a whole.
 *
 * <p>The candidate snapshot names only the surviving account; the frozen
 * account exists solely in the live world, so a candidate-only gate would let
 * the restore through and wipe the frozen balance. The gate must judge the
 * union of every account the restore would change or delete (accounts,
 * transactions and their balances) from live and candidate inside the same
 * exclusive window that publishes, and the refusal leaves one minimal journal
 * record. Live bytes, live state and the guard are untouched.
 */
class RestoreLiveFrozenGuardTest {

    private static final Executor DIRECT = Runnable::run;

    private SecurityControlService journaledControl(Path dataFolder) {
        SecurityControlService control = new SecurityControlService();
        control.bindAttemptJournal(
                new FileBlockedAttemptJournal(dataFolder.resolve("attempts.jsonl")), DIRECT);
        return control;
    }

    @Test
    @DisplayName("JSON restore deleting a live frozen account is refused with live bytes intact")
    void jsonRestoreDeletingLiveFrozenRefused(@TempDir Path dir) throws Exception {
        Path dataFolder = dir.resolve("data");
        Files.createDirectories(dataFolder);
        Path liveFile = dir.resolve("live.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(liveFile);
        backend.initialize();
        UUID keeper = UUID.randomUUID();
        backend.create(keeper, "keeper", Map.of("coin", Amount.of(50, 2)));

        BackupRestoreService restores = new BackupRestoreService(backend, dataFolder,
                () -> false, () -> Set.of("coin"), () -> {
                });
        var backup = restores.createBackup("before-frozen-exists");
        assertTrue(backup.isSuccess(), "setup backup must succeed");

        // The frozen account only ever exists in the live world, never in the snapshot.
        UUID frozen = UUID.randomUUID();
        backend.create(frozen, "frozen-owner", Map.of("coin", Amount.of(100, 2)));
        SecurityControlService control = journaledControl(dataFolder);
        control.freeze(frozen, AdminAudit.console("incident"));
        restores.bindSecurityControl(control);
        byte[] liveBefore = Files.readAllBytes(liveFile);

        var result = restores.restore(backup.backupId(), AdminAudit.console("incident"));

        assertFalse(result.isSuccess(),
                "restore that would delete a live frozen account must be refused as a whole");
        assertArrayEquals(liveBefore, Files.readAllBytes(liveFile),
                "a refused restore must leave the live file byte-identical");
        assertEquals(0, Amount.of(100, 2).compareTo(
                        backend.load(frozen).orElseThrow().balanceOf("coin")),
                "a refused restore must leave the frozen balance untouched");
        assertTrue(control.isFrozen(frozen), "a refused restore must never lift the guard");
        List<BlockedControlAttempt> journal = new FileBlockedAttemptJournal(
                dataFolder.resolve("attempts.jsonl")).list();
        assertEquals(1, journal.size(), "one refused restore must leave one journal record");
        assertEquals("restore", journal.get(0).operation());
        assertEquals(frozen, journal.get(0).targetAccount().orElseThrow());
        assertEquals(BlockedControlAttempt.CATEGORY_FROZEN, journal.get(0).refusalCategory());
    }

    @Test
    @DisplayName("SQL restore deleting a live frozen account is refused with live rows intact")
    void sqlRestoreDeletingLiveFrozenRefused(@TempDir Path dir) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path db = dir.resolve("live.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend backend =
                new com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend(
                        new com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider(
                                conn),
                        new com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect());
        backend.initialize();
        UUID keeper = UUID.randomUUID();
        backend.create(keeper, "keeper", Map.of("coin", Amount.of(50, 2)));

        Path dataFolder = dir.resolve("data");
        Files.createDirectories(dataFolder);
        BackupRestoreService restores = new BackupRestoreService(backend, dataFolder,
                () -> false, () -> Set.of("coin"), () -> {
                });
        var backup = restores.createBackup("before-frozen-exists");
        assertTrue(backup.isSuccess(), "setup backup must succeed");

        UUID frozen = UUID.randomUUID();
        backend.create(frozen, "frozen-owner", Map.of("coin", Amount.of(100, 2)));
        SecurityControlService control = journaledControl(dataFolder);
        control.freeze(frozen, AdminAudit.console("incident"));
        restores.bindSecurityControl(control);

        var result = restores.restore(backup.backupId(), AdminAudit.console("incident"));

        assertFalse(result.isSuccess(),
                "SQL restore that would delete a live frozen account must be refused as a whole");
        assertEquals(0, Amount.of(100, 2).compareTo(
                        backend.load(frozen).orElseThrow().balanceOf("coin")),
                "a refused SQL restore must leave the frozen balance untouched");
        assertTrue(backend.exists(keeper), "a refused SQL restore must keep the surviving account");
        assertTrue(control.isFrozen(frozen), "a refused SQL restore must never lift the guard");
        backend.close();
    }
}
