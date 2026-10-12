package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.OperationAudit;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audited restore with an independent operation record: the operator and
 * reason travel with the replaced world through the same durable publish, a
 * missing audit context is rejected before any live state moves, and snapshots
 * written before operation tracking still restore.
 *
 * <p>A restore must never fabricate a money transaction. A zero-amount deposit
 * would forge a deposit event, pollute the player account, the leaderboard,
 * rollback and the existing history meaning. The audit therefore lives
 * outside the transaction list, in its own collection and table, merged by
 * record id (live plus candidate, de-duplicated, then the current record
 * appended) and published once with the restored data. A post-hoc transaction
 * after the restore would commit separately: a crash between the two commits
 * leaves a restored world with no trace, and a record written before the
 * restore is wiped by the replacement itself. The stored backup file is never
 * rewritten; only the in-memory bytes handed to the backend carry the merged
 * collection.
 */
final class AdminAuditRestoreTest {

    @TempDir
    Path dir;

    private Path dataFolder() {
        return dir.resolve("data");
    }

    private JsonPersistenceBackend backendAt(Path dataFolder) throws Exception {
        Files.createDirectories(dataFolder);
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dataFolder.resolve("economy.json"));
        backend.initialize();
        return backend;
    }

    private BackupRestoreService service(JsonPersistenceBackend lifecycle, Path dataFolder) {
        return new BackupRestoreService(lifecycle, dataFolder, () -> false,
                () -> Set.of("dollar"), () -> { });
    }

    private UUID seeded(JsonPersistenceBackend backend) throws Exception {
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        backend.append(Fixtures.tx(UUID.randomUUID(), owner, null, "dollar",
                Fixtures.amt("100.00"), TransactionType.DEPOSIT,
                Fixtures.amt("0.00"), Fixtures.amt("100.00")));
        return owner;
    }

    private OperationAuditService auditsOf(JsonPersistenceBackend backend) {
        return new OperationAuditService(backend);
    }

    @Test
    void auditedRestorePublishesIndependentRecordWithoutFakeTransaction() throws Exception {
        Path dataFolder = dataFolder();
        JsonPersistenceBackend backend = backendAt(dataFolder);
        UUID owner = seeded(backend);
        BackupRestoreService restore = service(backend, dataFolder);

        BackupResult seed = restore.createBackup("seed");
        assertTrue(seed.isSuccess());
        int transactionsBefore = backend.loadAll().size();

        UUID newcomer = UUID.randomUUID();
        backend.create(newcomer, "mallory", Map.of("dollar", Fixtures.amt("5.00")));
        byte[] seedBytes = Files.readAllBytes(
                dataFolder.resolve("backups").resolve(seed.backupId() + ".json"));

        RestoreResult result = restore.restore(seed.backupId(),
                AdminAudit.console("harbor-restore"));

        assertTrue(result.isSuccess());
        assertTrue(result.safetyBackupId() != null && !result.safetyBackupId().isBlank(),
                "a successful restore must leave a safety snapshot of the replaced world");
        byte[] currentSeedBytes = Files.readAllBytes(
                dataFolder.resolve("backups").resolve(seed.backupId() + ".json"));
        assertEquals(seedBytes.length, currentSeedBytes.length,
                "the stored seed snapshot must never be rewritten by its own restore");
        assertArrayEquals(seedBytes, currentSeedBytes,
                "the stored seed snapshot bytes must never be rewritten by its own restore");

        List<Transaction> all = backend.loadAll();
        assertTrue(all.stream().anyMatch(t -> owner.equals(t.accountId())),
                "the restored seed records must still be present");
        assertFalse(all.stream().anyMatch(t -> newcomer.equals(t.accountId())),
                "the post-seed divergence must be gone after the restore");
        assertEquals(transactionsBefore, all.size(),
                "a restore must never fabricate a money transaction, not even a zero amount");
        assertTrue(all.stream().noneMatch(t -> "harbor-restore".equals(t.reason())),
                "the restore reason must not appear as a transaction reason");

        List<OperationAudit> audits = auditsOf(backend).query(
                OperationAuditQuery.builder().reasonContains("harbor-restore").build());
        assertEquals(1, audits.size(),
                "the operator reason must be part of the restored world as one independent record");
        OperationAudit record = audits.get(0);
        assertEquals(OperationAudit.OPERATION_RESTORE, record.operation());
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, record.operatorName());
        assertNull(record.operatorId(), "a console restore files under the console marker");
        assertEquals("harbor-restore", record.reason());
        assertEquals(seed.backupId(), record.sourceBackupId());
        assertEquals(result.safetyBackupId(), record.safetyBackupId());

        assertEquals(1, auditsOf(backend).query(
                OperationAuditQuery.builder().sourceBackupId(seed.backupId()).build()).size(),
                "the source snapshot id must stay traceable through the internal query");
        assertEquals(1, auditsOf(backend).query(
                OperationAuditQuery.builder().safetyBackupId(result.safetyBackupId()).build()).size(),
                "the safety snapshot id must stay traceable through the internal query");
    }

    @Test
    void restoreWithoutAuditContextIsRejectedBeforeTouchingLiveState() throws Exception {
        Path dataFolder = dataFolder();
        JsonPersistenceBackend backend = backendAt(dataFolder);
        seeded(backend);
        BackupRestoreService restore = service(backend, dataFolder);

        BackupResult seed = restore.createBackup("seed");
        assertTrue(seed.isSuccess());
        UUID newcomer = UUID.randomUUID();
        backend.create(newcomer, "mallory", Map.of("dollar", Fixtures.amt("5.00")));
        int liveBefore = backend.loadAll().size();

        RestoreResult result = restore.restore(seed.backupId(), null);

        assertFalse(result.isSuccess(),
                "a restore with no audit context must be rejected, never applied silently");
        assertEquals(BackupRestoreError.INVALID_REQUEST, result.error());
        assertEquals(liveBefore, backend.loadAll().size(),
                "a rejected restore must leave the live state exactly as it was");
        assertTrue(backend.loadAll().stream().anyMatch(t -> newcomer.equals(t.accountId()))
                || backend.listAll().stream().anyMatch(a -> newcomer.equals(a.owner())),
                "the pre-restore live records must still be present");
        assertTrue(auditsOf(backend).listAll().isEmpty(),
                "a rejected restore must leave no orphaned operation record");
    }

    @Test
    void failedRestoreLeavesNoOrphanedRecordAndKeepsPriorAudits() throws Exception {
        Path dataFolder = dataFolder();
        JsonPersistenceBackend backend = backendAt(dataFolder);
        seeded(backend);
        BackupRestoreService restore = service(backend, dataFolder);

        BackupResult seed = restore.createBackup("seed");
        assertTrue(seed.isSuccess());

        RestoreResult kept = restore.restore(seed.backupId(),
                AdminAudit.console("harbor-keep"));
        assertTrue(kept.isSuccess());
        int liveBefore = backend.loadAll().size();

        RestoreResult result = restore.restore("no-such-backup-id",
                AdminAudit.console("harbor-restore-fail"));

        assertFalse(result.isSuccess(),
                "an unknown snapshot must fail the restore, never report success");
        assertEquals(BackupRestoreError.BACKUP_NOT_FOUND, result.error());
        assertEquals(liveBefore, backend.loadAll().size(),
                "a failed restore must leave the live transactions exactly as they were");
        assertEquals(1, auditsOf(backend).query(
                OperationAuditQuery.builder().reasonContains("harbor-keep").build()).size(),
                "a failed restore must keep the prior restore record instead of wiping it");
        assertTrue(auditsOf(backend).query(
                OperationAuditQuery.builder().reasonContains("harbor-restore-fail").build()).isEmpty(),
                "a failed restore must leave no orphaned operation record");
    }

    @Test
    void auditedRestoreMergesLiveAndCandidateHistoriesWithoutDuplication() throws Exception {
        Path dataFolder = dataFolder();
        JsonPersistenceBackend backend = backendAt(dataFolder);
        seeded(backend);
        BackupRestoreService restore = service(backend, dataFolder);

        BackupResult seed = restore.createBackup("seed");
        assertTrue(seed.isSuccess());

        RestoreResult first = restore.restore(seed.backupId(),
                AdminAudit.console("harbor-first"));
        assertTrue(first.isSuccess());

        BackupResult secondSeed = restore.createBackup("second");
        assertTrue(secondSeed.isSuccess());
        backend.create(UUID.randomUUID(), "mallory", Map.of("dollar", Fixtures.amt("5.00")));

        RestoreResult second = restore.restore(secondSeed.backupId(),
                AdminAudit.console("harbor-second"));
        assertTrue(second.isSuccess());

        List<OperationAudit> firstAudits = auditsOf(backend).query(
                OperationAuditQuery.builder().reasonContains("harbor-first").build());
        List<OperationAudit> secondAudits = auditsOf(backend).query(
                OperationAuditQuery.builder().reasonContains("harbor-second").build());
        assertEquals(1, firstAudits.size(),
                "the first restore record must survive the second restore instead of being wiped");
        assertEquals(1, secondAudits.size(),
                "the second restore must append exactly one new record");
        assertFalse(firstAudits.get(0).id().equals(secondAudits.get(0).id()),
                "each restore files its own record id so the merge can de-duplicate by id");
    }

    @Test
    void legacySnapshotWithoutAuditRestoresWithRecordsUnrecorded() throws Exception {
        Path dataFolder = dataFolder();
        JsonPersistenceBackend backend = backendAt(dataFolder);
        UUID owner = seeded(backend);
        BackupRestoreService restore = service(backend, dataFolder);

        BackupResult seed = restore.createBackup("seed");
        assertTrue(seed.isSuccess());
        backend.create(UUID.randomUUID(), "mallory", Map.of("dollar", Fixtures.amt("5.00")));

        RestoreResult result = restore.restore(seed.backupId());

        assertTrue(result.isSuccess());
        String before = Files.readString(
                dataFolder.resolve("backups").resolve(seed.backupId() + ".json"),
                StandardCharsets.UTF_8);
        assertTrue(before.contains(owner.toString()));
        assertTrue(backend.listAll().stream().anyMatch(a -> owner.equals(a.owner())));
        assertTrue(backend.loadAll().stream().noneMatch(t -> t.reason() != null
                        && t.reason().contains("harbor-restore")),
                "a legacy restore must not fabricate a transaction either");
    }
}
