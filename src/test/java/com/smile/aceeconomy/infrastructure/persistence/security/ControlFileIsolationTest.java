package com.smile.aceeconomy.infrastructure.persistence.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.operations.BackupRestoreService;
import com.smile.aceeconomy.operations.security.SecurityControlService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The safety control lives in its own file beside the data folder, never inside
 * the economy snapshot: ordinary backups never contain it and restoring an
 * economy snapshot never replaces the current guard.
 *
 * <p>The fixed file name is {@code security-control.json}. Copying it requires a
 * stopped server or a controlled snapshot export; downgrades cannot keep the
 * protection. All assertions fail on the skeleton store that persists nothing.
 */
class ControlFileIsolationTest {

    static final String CONTROL_FILE_NAME = "security-control.json";

    private Path controlFile(Path dataFolder) {
        return dataFolder.resolve(CONTROL_FILE_NAME);
    }

    @Test
    @DisplayName("control file has the fixed name beside the data folder")
    void controlFileHasFixedName(@TempDir Path dir) {
        Path dataFolder = dir.resolve("data");
        assertEquals(CONTROL_FILE_NAME, controlFile(dataFolder).getFileName().toString(),
                "control file name is fixed");
        assertEquals(dataFolder, controlFile(dataFolder).getParent(),
                "control file lives beside the data folder, outside the economy snapshot");
    }

    @Test
    @DisplayName("freeze survives through the same control file")
    void freezeSurvivesThroughControlFile(@TempDir Path dir) {
        Path dataFolder = dir.resolve("data");
        FileSecurityControlStore store = new FileSecurityControlStore(controlFile(dataFolder));
        SecurityControlService first = new SecurityControlService(store);
        UUID acc = UUID.randomUUID();
        first.freeze(acc, AdminAudit.console("incident"));

        SecurityControlService second = new SecurityControlService(store);
        assertTrue(second.isFrozen(acc), "freeze must survive through the control file");
    }

    @Test
    @DisplayName("ordinary backup carries no guard and restore touching a frozen account is refused")
    void backupRestoreLeavesGuardAlone(@TempDir Path dir) throws Exception {
        Path dataFolder = dir.resolve("data");
        Files.createDirectories(dataFolder);
        Path liveFile = dir.resolve("live.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(liveFile);
        backend.initialize();
        UUID acc = UUID.randomUUID();
        backend.create(acc, "owner", Map.of("coin", Amount.of(100, 2)));

        FileSecurityControlStore controlStore =
                new FileSecurityControlStore(controlFile(dataFolder));
        SecurityControlService control = new SecurityControlService(controlStore);
        control.freeze(acc, AdminAudit.console("incident"));
        assertTrue(control.isFrozen(acc), "freeze must be effective before the backup");

        BackupRestoreService restores = new BackupRestoreService(backend, dataFolder,
                () -> false, () -> Set.of("coin"), () -> {
                });
        restores.bindSecurityControl(control);
        var backup = restores.createBackup("before");
        assertTrue(backup.isSuccess(), "setup backup must succeed");

        Path backupFile = dataFolder.resolve("backups").resolve(backup.backupId() + ".json");
        String content = Files.readString(backupFile);
        JsonModel snapshot = JsonModel.fromJson(content);
        assertTrue(snapshot.accounts.containsKey(acc.toString()),
                "economy backup must carry the economy account");
        assertFalse(content.contains("\"frozen\""),
                "economy backup must not contain frozen guard state");
        assertFalse(content.contains("\"globalReadonly\""),
                "economy backup must not contain the read-only guard state");
        assertFalse(content.contains("security-control"),
                "economy backup must not reference the control file");
        assertFalse(content.contains("\"controlVersion\""),
                "economy backup must not contain control journal content");

        var result = restores.restore(backup.backupId(), AdminAudit.console("incident"));
        assertFalse(result.isSuccess(),
                "restore touching a frozen account must be refused as a whole");
        assertTrue(control.isFrozen(acc),
                "a refused restore must never lift the current guard");
        assertEquals(0, Amount.of(100, 2).compareTo(
                backend.load(acc).orElseThrow().balanceOf("coin")),
                "a refused restore must leave live state untouched");
    }

    @Test
    @DisplayName("restore of a snapshot without the frozen account succeeds and keeps the guard")
    void restoreWithoutFrozenTargetKeepsGuard(@TempDir Path dir) throws Exception {
        Path dataFolder = dir.resolve("data");
        Files.createDirectories(dataFolder);
        Path liveFile = dir.resolve("live.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(liveFile);
        backend.initialize();
        UUID bob = UUID.randomUUID();
        backend.create(bob, "bob", Map.of("coin", Amount.of(50, 2)));

        BackupRestoreService restores = new BackupRestoreService(backend, dataFolder,
                () -> false, () -> Set.of("coin"), () -> {
                });
        var backup = restores.createBackup("clean");
        assertTrue(backup.isSuccess(), "setup backup must succeed");

        // The frozen account exists nowhere in the economy: neither the live
        // world nor the snapshot names it, so the restore deletes no frozen
        // asset and may proceed. (A frozen account present in the live world
        // but absent from the snapshot must refuse instead: the restore would
        // delete it.)
        UUID frozen = UUID.randomUUID();
        FileSecurityControlStore controlStore =
                new FileSecurityControlStore(controlFile(dataFolder));
        SecurityControlService control = new SecurityControlService(controlStore);
        control.freeze(frozen, AdminAudit.console("incident"));
        restores.bindSecurityControl(control);

        var result = restores.restore(backup.backupId(), AdminAudit.console("incident"));
        assertTrue(result.isSuccess(),
                "restore of a snapshot without the frozen account must succeed");
        assertTrue(control.isFrozen(frozen),
                "a successful restore must never lift the current guard");
        assertEquals(0, Amount.of(50, 2).compareTo(
                backend.load(bob).orElseThrow().balanceOf("coin")),
                "restored world must carry the snapshot balance");
    }
}
