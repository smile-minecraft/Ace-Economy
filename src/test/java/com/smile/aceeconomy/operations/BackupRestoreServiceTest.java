package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.ports.Clock;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the managed {@link BackupRestoreService}: controlled directory, safe
     * labels/ids, marker-based never-overwriting output, credential-free snapshots, restore gate
 * ordering (preflight → lock → online gate → safety backup → lifecycle.restore), and lock
 * release on every outcome.
 */
class BackupRestoreServiceTest {

    @TempDir
    Path dir;

    private static final Set<String> CURRENCIES = Set.of("dollar", "token");

    // ---------------- fakes ----------------

    /** Lifecycle fake mirroring the backend contract: parse-before-write, swappable bytes. */
    static final class FakeLifecycle implements PersistenceLifecycle {
        byte[] current = "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[],\"nonces\":{}}"
                .getBytes(StandardCharsets.UTF_8);
        int backupCalls;
        int restoreCalls;
        CountDownLatch blockBackup;
        boolean failBackup;
        Runnable failRestore;
        final List<String> events = new java.util.ArrayList<>();
        // One reentrant guard shared by backup, restore, and runExclusive: ordinary
        // persistence work cannot interleave inside an exclusive window, while the composed
        // operation may re-enter backup/restore on the same thread without deadlocking.
        private final ReentrantLock lock = new ReentrantLock();

        @Override
        public void initialize() { }

        @Override
        public void close() { }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public int schemaVersion() {
            return JsonModel.SCHEMA_VERSION;
        }

        @Override
        public boolean needsRecreation() {
            return false;
        }

        @Override
        public void truncateAndRecreate() { }

        @Override
        public void backup(OutputStream out) throws PersistenceException, IOException {
            lock.lock();
            try {
                backupCalls++;
                events.add("backup");
                if (blockBackup != null) {
                    try {
                        assertTrue(blockBackup.await(10, TimeUnit.SECONDS), "backup latch timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                }
                if (failBackup) {
                    throw new PersistenceException("injected backup failure");
                }
                out.write(current);
                out.flush();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void restore(InputStream in) throws PersistenceException, IOException {
            lock.lock();
            try {
                restoreCalls++;
                events.add("restore");
                byte[] bytes = in.readAllBytes();
                // Mirror the real contract: fully parse BEFORE swapping live state.
                JsonModel.fromJson(new String(bytes, StandardCharsets.UTF_8));
                if (failRestore != null) {
                    Runnable hook = failRestore;
                    failRestore = null;
                    hook.run();
                }
                current = bytes;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public <R> R runExclusive(ExclusiveOperation<R> operation)
                throws PersistenceException, IOException {
            lock.lock();
            try {
                events.add("exclusive:start");
                return operation.run();
            } finally {
                events.add("exclusive:end");
                lock.unlock();
            }
        }

        String json() {
            return new String(current, StandardCharsets.UTF_8);
        }
    }

    /** Lifecycle wrapper that counts restore invocations on a real backend. */
    static final class CountingLifecycle implements PersistenceLifecycle {
        private final JsonPersistenceBackend delegate;
        int restoreCalls;
        int backupCalls;

        CountingLifecycle(JsonPersistenceBackend delegate) {
            this.delegate = delegate;
        }

        @Override
        public void initialize() { }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public boolean isInitialized() {
            return delegate.isInitialized();
        }

        @Override
        public int schemaVersion() {
            return delegate.schemaVersion();
        }

        @Override
        public boolean needsRecreation() {
            return delegate.needsRecreation();
        }

        @Override
        public void truncateAndRecreate() {
            delegate.truncateAndRecreate();
        }

        @Override
        public void backup(OutputStream out) throws PersistenceException, IOException {
            backupCalls++;
            delegate.backup(out);
        }

        @Override
        public void restore(InputStream in) throws PersistenceException, IOException {
            restoreCalls++;
            delegate.restore(in);
        }

        /**
         * Delegates to the wrapped backend so the exclusive window is held by the SAME
         * {@link ReentrantLock} that guards its repository methods, mirroring the real
         * contract instead of running the composed operation unlocked.
         */
        @Override
        public <R> R runExclusive(ExclusiveOperation<R> operation)
                throws PersistenceException, IOException {
            return delegate.runExclusive(operation);
        }
    }

    private FakeLifecycle lifecycle;
    private CountingLifecycle counting;
    private int cacheResets;

    private BackupRestoreService service(FakeLifecycle fake) {
        this.lifecycle = fake;
        this.cacheResets = 0;
        Clock fixed = () -> Instant.parse("2026-08-24T09:30:00Z");
        return new BackupRestoreService(fake, dir, () -> false, () -> CURRENCIES,
                () -> cacheResets++, fixed, () -> "aaaa1111");
    }

    private BackupRestoreService serviceWithProbe(FakeLifecycle fake, PlayerOnlineProbe probe) {
        this.lifecycle = fake;
        this.cacheResets = 0;
        Clock fixed = () -> Instant.parse("2026-08-24T09:30:00Z");
        return new BackupRestoreService(fake, dir, probe, () -> CURRENCIES,
                () -> cacheResets++, fixed, () -> "aaaa1111");
    }

    private static JsonModel modelOf(String json) {
        return JsonModel.fromJson(json);
    }

    // ---------------- backup ----------------

    @Test
    void backupWritesLogicalSnapshotUnderControlledDirectoryWithoutCredentials() throws Exception {
        FakeLifecycle fake = new FakeLifecycle();
        fake.current = seedSnapshotJson().getBytes(StandardCharsets.UTF_8);
        BackupRestoreService service = service(fake);

        BackupResult result = service.createBackup(null);

        assertTrue(result.isSuccess(), "backup must succeed: " + result.message());
        Path expected = dir.resolve("backups")
                .resolve("20260824T093000-aaaa1111.json");
        assertTrue(Files.isRegularFile(expected), "snapshot must exist at the controlled path");
        Path ready = dir.resolve("backups").resolve("20260824T093000-aaaa1111.ready");
        assertTrue(Files.isRegularFile(ready), "commit marker must be published after the target");
        assertTrue(Files.readString(ready, StandardCharsets.US_ASCII).matches(
                "sha256=[0-9a-f]{64}\\n"), "commit marker must contain a digest");
        assertEquals(1, fake.backupCalls);

        String text = Files.readString(expected, StandardCharsets.UTF_8);
        JsonModel model = modelOf(text);
        assertEquals(Set.of("schemaVersion", "accounts", "transactions", "nonces"),
                modelOf(text).toJsonObject().keySet(),
                "snapshot top-level keys must stay inside the v2 logical allowlist");
        assertTrue(model.accounts.containsKey("11111111-2222-3333-4444-555555555555"));
        assertFalse(text.toLowerCase().contains("password"), "no credentials in snapshot");
        assertFalse(text.toLowerCase().contains("webhook"), "no webhook in snapshot");
        assertFalse(text.contains("jdbc:"), "no connection strings in snapshot");
    }

    @Test
    void backupLabelAcceptsSafeTokenAndRejectsTraversalAbsoluteControlAndDotDot() throws Exception {
        BackupRestoreService service = service(new FakeLifecycle());

        assertTrue(service.createBackup("monthly-2026_08.01").isSuccess(),
                "safe label characters must be accepted");

        List<String> unsafe = List.of("../evil", "sub/dir", "sub\\dir", "..", ".", "/etc/passwd",
                "a\0b", "a b", "a\nb", "x".repeat(65));
        for (String label : unsafe) {
            BackupResult result = service.createBackup(label);
            assertFalse(result.isSuccess(), "label must be rejected: '" + label + "'");
            assertEquals(BackupRestoreError.LABEL_INVALID, result.error(), "label: " + label);
        }
        Path backups = dir.resolve("backups");
        if (Files.exists(backups)) {
            try (var files = Files.list(backups)) {
                assertEquals(2, files.count(), "only the safe-label snapshot and marker may exist");
            }
        }
    }

    @Test
    void duplicateBackupIdNeverOverwritesExistingSnapshot() throws Exception {
        FakeLifecycle fake = new FakeLifecycle();
        fake.current = seedSnapshotJson().getBytes(StandardCharsets.UTF_8);
        BackupRestoreService service = service(fake);

        BackupResult first = service.createBackup(null);
        assertTrue(first.isSuccess());
        Path target = dir.resolve("backups").resolve(first.backupId() + ".json");
        Path marker = dir.resolve("backups").resolve(first.backupId() + ".ready");
        byte[] original = Files.readAllBytes(target);
        byte[] originalMarker = Files.readAllBytes(marker);

        fake.current = "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[],\"nonces\":{}}"
                .getBytes(StandardCharsets.UTF_8);
        BackupResult second = service.createBackup(null);

        assertFalse(second.isSuccess(), "an id collision must be rejected, never overwritten");
        assertEquals(BackupRestoreError.IO_FAILED, second.error());
        assertNotNull(second.message(), "the rejection must carry an operator-readable message");
        assertTrue(Files.isRegularFile(target), "original snapshot must survive");
        assertEquals(java.util.Arrays.hashCode(original),
                java.util.Arrays.hashCode(Files.readAllBytes(target)),
                "original snapshot bytes must be untouched");
        assertEquals(java.util.Arrays.hashCode(originalMarker),
                java.util.Arrays.hashCode(Files.readAllBytes(marker)),
                "original commit marker bytes must be untouched");
        try (var files = Files.list(dir.resolve("backups"))) {
            assertEquals(2, files.count(), "no partial or extra snapshot may remain");
        }
    }

    @Test
    void snapshotWithoutCommitMarkerIsRejectedAndLiveBytesRemainUnchanged() throws Exception {
        JsonPersistenceBackend backend = seededRealBackend();
        BackupRestoreService service = serviceForReal(backend);
        String id = "partial000-aaaa-1111-bbbb-cccccccccccc";
        writeRawSnapshotWithoutMarker(id, seedSnapshotJson());
        byte[] liveBefore = Files.readAllBytes(dir.resolve("data-v2.json"));

        RestoreResult result = service.restore(id);

        assertEquals(BackupRestoreError.BACKUP_NOT_FOUND, result.error());
        assertEquals(0, restoreCallsObserved());
        assertTrue(java.util.Arrays.equals(liveBefore, Files.readAllBytes(dir.resolve("data-v2.json"))));
        assertFalse(Files.exists(dir.resolve("backups").resolve(id + ".ready")));
    }

    @Test
    void commitMarkerWithoutSnapshotIsRejected() throws Exception {
        JsonPersistenceBackend backend = seededRealBackend();
        BackupRestoreService service = serviceForReal(backend);
        String id = "marker000-aaaa-1111-bbbb-cccccccccccc";
        Path backups = Files.createDirectories(dir.resolve("backups"));
        Files.writeString(backups.resolve(id + ".ready"),
                readyMarker(seedSnapshotJson().getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.US_ASCII);

        RestoreResult result = service.restore(id);

        assertEquals(BackupRestoreError.BACKUP_NOT_FOUND, result.error());
        assertEquals(0, restoreCallsObserved());
        assertFalse(Files.exists(backups.resolve(id + ".json")));
    }

    @Test
    void markerDigestMismatchIsRejectedWithoutTouchingLiveState() throws Exception {
        JsonPersistenceBackend backend = seededRealBackend();
        BackupRestoreService service = serviceForReal(backend);
        String id = "digest000-aaaa-1111-bbbb-cccccccccccc";
        writeRawSnapshot(id, seedSnapshotJson());
        Files.writeString(dir.resolve("backups").resolve(id + ".ready"),
                "sha256=" + "0".repeat(64) + "\n", StandardCharsets.US_ASCII);
        byte[] liveBefore = Files.readAllBytes(dir.resolve("data-v2.json"));

        RestoreResult result = service.restore(id);

        assertEquals(BackupRestoreError.BACKUP_NOT_FOUND, result.error());
        assertEquals(0, restoreCallsObserved());
        assertTrue(java.util.Arrays.equals(liveBefore, Files.readAllBytes(dir.resolve("data-v2.json"))));
    }

    @Test
    void existingCommitMarkerIsRejectedWithoutCreatingOrOverwritingTarget() throws Exception {
        FakeLifecycle fake = new FakeLifecycle();
        BackupRestoreService service = service(fake);
        String id = "20260824T093000-aaaa1111";
        Path backups = Files.createDirectories(dir.resolve("backups"));
        Path marker = backups.resolve(id + ".ready");
        byte[] originalMarker = "operator-marker\n".getBytes(StandardCharsets.US_ASCII);
        Files.write(marker, originalMarker);

        BackupResult result = service.createBackup(null);

        assertFalse(result.isSuccess());
        assertEquals(BackupRestoreError.IO_FAILED, result.error());
        assertFalse(Files.exists(backups.resolve(id + ".json")));
        assertEquals(java.util.Arrays.hashCode(originalMarker),
                java.util.Arrays.hashCode(Files.readAllBytes(marker)));
    }

    @Test
    void backupFailureLeavesNoPartialFilesAndReportsTypedError() {
        FakeLifecycle fake = new FakeLifecycle();
        fake.failBackup = true;
        BackupRestoreService service = service(fake);

        BackupResult result = service.createBackup(null);

        assertFalse(result.isSuccess());
        assertEquals(BackupRestoreError.IO_FAILED, result.error());
        assertFalse(Files.exists(dir.resolve("backups")),
                "no backup directory or partial file may appear on failure");
    }

    @Test
    void symlinkedBackupDirectoryIsRejectedBeforeAnyWrite() throws Exception {
        Path outside = Files.createTempDirectory("ace-outside");
        FakeLifecycle fake = new FakeLifecycle();
        BackupRestoreService service = service(fake);
        // Replace the (not yet created) backups path with a symlink pointing outside.
        Path link = dir.resolve("backups");
        Files.createSymbolicLink(link, outside);

        BackupResult result = service.createBackup(null);

        assertFalse(result.isSuccess(), "a symlinked backup directory must be rejected");
        assertEquals(BackupRestoreError.IO_FAILED, result.error());
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count(), "nothing may be written through the escape link");
        }
    }

    @Test
    void brokenSymlinkedBackupDirectoryIsRejectedWithoutCreatingExternalTarget() throws Exception {
        FakeLifecycle fake = new FakeLifecycle();
        BackupRestoreService service = service(fake);
        // Dangling link: the external target does not exist yet. The backup attempt must
        // reject the symlink WITHOUT materializing the external directory.
        Path outsideRoot = Files.createTempDirectory("ace-external");
        Path externalTarget = outsideRoot.resolve("deep").resolve("backups");
        Files.createSymbolicLink(dir.resolve("backups"), externalTarget);
        assertFalse(Files.exists(externalTarget, LinkOption.NOFOLLOW_LINKS),
                "precondition: the external target must not exist yet");

        BackupResult result = service.createBackup(null);

        assertFalse(result.isSuccess(), "a symlinked backup directory must be rejected");
        assertEquals(BackupRestoreError.IO_FAILED, result.error());
        assertFalse(Files.exists(externalTarget, LinkOption.NOFOLLOW_LINKS),
                "the backup attempt must not create the external symlink target");
        assertTrue(Files.isSymbolicLink(dir.resolve("backups")),
                "the plugin data folder must keep the untouched link node, nothing more");
    }

    // ---------------- restore gates ----------------

    @Test
    void restoreHappyPathCreatesSafetyBackupRestoresAndInvalidatesCache() throws Exception {
        Path dataFile = dir.resolve("data-v2.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        backend.create(owner, "Alice", Map.of("dollar", Fixtures.amt("100.00")));
        backend.append(Fixtures.tx(txId, owner, null, "dollar",
                Fixtures.amt("40.00"), TransactionType.WITHDRAW,
                Fixtures.amt("100.00"), Fixtures.amt("60.00")));
        backend.markReverted(txId);
        assertTrue(backend.consume(nonce));

        BackupRestoreService service = new BackupRestoreService(backend, dir, () -> false,
                () -> CURRENCIES, () -> cacheResets++,
                () -> Instant.parse("2026-08-24T09:30:00Z"), () -> "aaaa1111");

        BackupResult backup = service.createBackup("before-wipe");
        assertTrue(backup.isSuccess(), "seed backup must succeed: " + backup.message());

        // Mutate live state after the snapshot: a second account and transaction appear.
        UUID other = UUID.randomUUID();
        backend.create(other, "Bob", Map.of("dollar", Fixtures.amt("1.00")));
        backend.append(Fixtures.tx(UUID.randomUUID(), other, null, "dollar",
                Fixtures.amt("1.00"), TransactionType.DEPOSIT,
                Fixtures.amt("0.00"), Fixtures.amt("1.00")));

        RestoreResult restored = service.restore(backup.backupId());

        assertTrue(restored.isSuccess(), "restore must succeed: " + restored.message());
        assertEquals(backup.backupId(), restored.restoredBackupId());
        assertNotNull(restored.safetyBackupId(), "a safety backup id must be reported");
        assertNotEquals(backup.backupId(), restored.safetyBackupId());
        assertTrue(restored.restartRequired(), "success must demand a restart boundary");
        assertEquals(1, cacheResets, "leaderboard cache must be invalidated exactly once");
        assertTrue(Files.isRegularFile(dir.resolve("backups")
                        .resolve(restored.safetyBackupId() + ".json")),
                "the safety snapshot must exist on disk");

        // Live state equals the snapshot again: Bob is gone, revert marker and nonce survive.
        assertFalse(backend.exists(other), "post-snapshot mutation must be rolled back");
        Account alice = backend.load(owner).orElseThrow();
        assertEquals(0, Fixtures.amt("100.00").compareTo(alice.balances().get("dollar")));
        assertTrue(backend.isReverted(txId), "reverted marker must survive the round trip");
        assertTrue(backend.isConsumed(nonce), "consumed nonce must survive the round trip");
        assertEquals(1, backend.loadAll().size());

        // The safety snapshot holds the mutated (pre-restore) live state.
        String safetyJson = Files.readString(
                dir.resolve("backups").resolve(restored.safetyBackupId() + ".json"),
                StandardCharsets.UTF_8);
        assertTrue(modelOf(safetyJson).accounts.containsKey(other.toString()),
                "safety snapshot must capture the pre-restore live state");
    }

    @Test
    void playersOnlineHardRejectsBeforeSafetyBackupOrRestore() throws Exception {
        FakeLifecycle fake = new FakeLifecycle();
        fake.current = seedSnapshotJson().getBytes(StandardCharsets.UTF_8);
        BackupRestoreService service = serviceWithProbe(fake, () -> true);
        BackupResult backup = service.createBackup(null);
        assertTrue(backup.isSuccess());
        int backupsBefore = snapshotCount();

        RestoreResult result = service.restore(backup.backupId());

        assertFalse(result.isSuccess());
        assertEquals(BackupRestoreError.PLAYERS_ONLINE, result.error());
        assertEquals(0, fake.restoreCalls, "restore must never run while players are online");
        assertEquals(backupsBefore, snapshotCount(),
                "no safety backup may be created when the online gate rejects");
        assertEquals(0, cacheResets, "cache must not be invalidated on failure");
    }

    @Test
    void busyGateRejectsConcurrentOperationAndLockIsReleasedAfterSuccess() throws Exception {
        FakeLifecycle fake = new FakeLifecycle();
        java.util.concurrent.atomic.AtomicInteger counter =
                new java.util.concurrent.atomic.AtomicInteger();
        this.lifecycle = fake;
        this.cacheResets = 0;
        BackupRestoreService service = new BackupRestoreService(fake, dir, () -> false,
                () -> CURRENCIES, () -> cacheResets++,
                () -> Instant.parse("2026-08-24T09:30:00Z"),
                () -> "sfx" + counter.incrementAndGet());

        // A completed backup gives us a valid id to address while the lock is held.
        BackupResult existing = service.createBackup(null);
        assertTrue(existing.isSuccess(), "seed backup must succeed: " + existing.message());

        // A worker now blocks inside lifecycle.backup, holding the operation lock.
        fake.blockBackup = new CountDownLatch(1);
        Thread worker = new Thread(() -> service.createBackup(null));
        worker.start();
        long deadline = System.currentTimeMillis() + 5_000;
        while (fake.backupCalls < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        RestoreResult blocked = service.restore(existing.backupId());
        assertEquals(BackupRestoreError.BUSY, blocked.error(),
                "a concurrent operation must get a typed busy failure");
        assertEquals(0, fake.restoreCalls);

        BackupResult blockedBackup = service.createBackup(null);
        assertEquals(BackupRestoreError.BUSY, blockedBackup.error());

        fake.blockBackup.countDown();
        worker.join(10_000);
        assertFalse(worker.isAlive(), "worker must finish after the latch release");

        // Lock released after completion: the next operation proceeds normally.
        fake.blockBackup = null;
        BackupResult after = service.createBackup(null);
        assertTrue(after.isSuccess(), "lock must be released after completion: " + after.message());
    }

    @Test
    void lockIsReleasedWhenLifecycleRestoreFails() throws Exception {
        FakeLifecycle fake = new FakeLifecycle();
        fake.current = seedSnapshotJson().getBytes(StandardCharsets.UTF_8);
        BackupRestoreService service = service(fake);
        BackupResult backup = service.createBackup(null);
        assertTrue(backup.isSuccess());
        fake.failRestore = () -> {
            throw new PersistenceException("injected restore failure");
        };

        RestoreResult result = service.restore(backup.backupId());

        assertFalse(result.isSuccess());
        assertEquals(BackupRestoreError.RESTORE_FAILED, result.error());
        // The reply must stay truthful: a backend failure after gates passed may have left
        // the state changed or committed (SQL rollback failure / post-commit autocommit
        // failure), so it must NEVER claim the live state was rolled back.
        assertFalse(result.message().toLowerCase().contains("rolled back"),
                "must not claim a confirmed rollback: " + result.message());
        assertTrue(result.message().toLowerCase().contains("not confirmed"),
                "must state the live state was not confirmed unchanged: " + result.message());
        assertTrue(result.message().contains("injected restore failure"),
                "the backend cause must stay visible: " + result.message());
        fake.failBackup = true; // make the next backup fail ONLY if the lock got stuck
        BackupResult followUp = service.createBackup(null);
        assertEquals(BackupRestoreError.IO_FAILED, followUp.error(),
                "the follow-up must reach lifecycle.backup (lock released), not report BUSY");
    }

    // ---------------- restore preflight rejections ----------------

    @Test
    void malformedSnapshotIsRejectedWithoutCallingRestoreOrTouchingLiveState() throws Exception {
        JsonPersistenceBackend backend = seededRealBackend();
        BackupRestoreService service = serviceForReal(backend);
        writeRawSnapshot("bad00000-aaaa-1111-bbbb-cccccccccccc", "this is not json{");
        byte[] liveBefore = Files.readAllBytes(dir.resolve("data-v2.json"));

        RestoreResult result = service.restore("bad00000-aaaa-1111-bbbb-cccccccccccc");

        assertEquals(BackupRestoreError.SNAPSHOT_INVALID, result.error());
        assertEquals(0, restoreCallsObserved());
        assertTrue(java.util.Arrays.equals(liveBefore, Files.readAllBytes(dir.resolve("data-v2.json"))),
                "live file bytes must be unchanged");
        assertNotNull(backend.load(UUID.fromString(SEEDED_OWNER)), "live account must survive");
    }

    @Test
    void schemaIncompatibleSnapshotIsRejectedInPreflight() throws Exception {
        JsonPersistenceBackend backend = seededRealBackend();
        BackupRestoreService service = serviceForReal(backend);
        writeRawSnapshot("sch000000-aaaa-1111-bbbb-cccccccccccc",
                "{\"schemaVersion\":99,\"accounts\":{},\"transactions\":[],\"nonces\":{}}");

        RestoreResult result = service.restore("sch000000-aaaa-1111-bbbb-cccccccccccc");

        assertEquals(BackupRestoreError.SCHEMA_INCOMPATIBLE, result.error());
        assertEquals(0, restoreCallsObserved());
    }

    @Test
    void unknownCurrencyIsRejectedWhileKnownTokenCurrencyIsAccepted() throws Exception {
        JsonPersistenceBackend backend = seededRealBackend();
        BackupRestoreService service = serviceForReal(backend);
        writeRawSnapshot("cur000000-aaaa-1111-bbbb-cccccccccccc",
                "{\"schemaVersion\":1,\"accounts\":{\""
                        + SEEDED_OWNER + "\":{\"owner\":\"" + SEEDED_OWNER
                        + "\",\"ownerName\":\"Alice\",\"balances\":{\"mysterycoin\":\"5\"}}},"
                        + "\"transactions\":[],\"nonces\":{}}");

        RestoreResult unknown = service.restore("cur000000-aaaa-1111-bbbb-cccccccccccc");
        assertEquals(BackupRestoreError.CURRENCY_INCOMPATIBLE, unknown.error());
        assertEquals(0, restoreCallsObserved());

        // The legitimate currency id "token" must never be misjudged by a denylist.
        writeRawSnapshot("tok000000-aaaa-1111-bbbb-cccccccccccc",
                "{\"schemaVersion\":1,\"accounts\":{\""
                        + SEEDED_OWNER + "\":{\"owner\":\"" + SEEDED_OWNER
                        + "\",\"ownerName\":\"Alice\",\"balances\":{\"token\":\"5\"}}},"
                        + "\"transactions\":[],\"nonces\":{}}");
        RestoreResult token = service.restore("tok000000-aaaa-1111-bbbb-cccccccccccc");
        assertTrue(token.isSuccess(), "currency 'token' is a legal allowlist member: "
                + token.message());
    }

    @Test
    void invalidRecordsAreRejectedInPreflightWithoutTouchingLiveState() throws Exception {
        String owner = SEEDED_OWNER;
        List<String> broken = List.of(
                // unknown transaction type (passes shallow JSON parse, fails domain conversion)
                txSnapshot("rec000001-aaaa-1111-bbbb-cccccccccccc",
                        "\"currencyId\":\"dollar\",\"type\":\"NOT_A_TYPE\",\"amount\":\"1\","
                                + "\"timestamp\":\"" + Instant.now().toString() + "\",\"reverted\":false,"),
                // non-decimal amount
                txSnapshot("rec000002-aaaa-1111-bbbb-cccccccccccc",
                        "\"currencyId\":\"dollar\",\"type\":\"DEPOSIT\",\"amount\":\"12abc\","
                                + "\"timestamp\":\"" + Instant.now().toString() + "\",\"reverted\":false,"),
                // unparseable timestamp
                txSnapshot("rec000003-aaaa-1111-bbbb-cccccccccccc",
                        "\"currencyId\":\"dollar\",\"type\":\"DEPOSIT\",\"amount\":\"1\","
                                + "\"timestamp\":\"yesterday\",\"reverted\":false,"),
                // garbage counterparty uuid
                txSnapshot("rec000004-aaaa-1111-bbbb-cccccccccccc",
                        "\"counterparty\":\"not-a-uuid\",\"currencyId\":\"dollar\",\"type\":\"DEPOSIT\","
                                + "\"amount\":\"1\",\"timestamp\":\"" + Instant.now().toString()
                                + "\",\"reverted\":false,"));
        List<String> ids = List.of("rec000001-aaaa-1111-bbbb-cccccccccccc",
                "rec000002-aaaa-1111-bbbb-cccccccccccc",
                "rec000003-aaaa-1111-bbbb-cccccccccccc",
                "rec000004-aaaa-1111-bbbb-cccccccccccc");
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            JsonPersistenceBackend backend = seededRealBackend();
            BackupRestoreService service = serviceForReal(backend);
            writeRawSnapshot(id, broken.get(i));
            byte[] liveBefore = Files.readAllBytes(dir.resolve("data-v2.json"));

            RestoreResult result = service.restore(id);

            assertEquals(BackupRestoreError.SNAPSHOT_INVALID, result.error(),
                    "invalid record snapshot must be rejected: " + id);
            assertEquals(0, restoreCallsObserved(),
                    "lifecycle.restore must never run: " + id);
            assertTrue(java.util.Arrays.equals(liveBefore,
                            Files.readAllBytes(dir.resolve("data-v2.json"))),
                    "live bytes unchanged for " + id);
        }

        // Duplicate transaction ids inside one snapshot must also be rejected up front.
        JsonPersistenceBackend backend = seededRealBackend();
        BackupRestoreService service = serviceForReal(backend);
        String dupTx = "{\"id\":\"" + UUID.randomUUID() + "\",\"accountId\":\"" + owner
                + "\",\"counterparty\":null,\"currencyId\":\"dollar\",\"amount\":\"1\","
                + "\"type\":\"DEPOSIT\",\"balanceBefore\":\"0\",\"balanceAfter\":\"1\","
                + "\"timestamp\":\"" + Instant.now().toString() + "\",\"reason\":\"t\",\"reverted\":false}";
        writeRawSnapshot("dup000000-aaaa-1111-bbbb-cccccccccccc",
                "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[" + dupTx + "," + dupTx
                        + "],\"nonces\":{}}");
        RestoreResult dup = service.restore("dup000000-aaaa-1111-bbbb-cccccccccccc");
        assertEquals(BackupRestoreError.SNAPSHOT_INVALID, dup.error());
        assertEquals(0, restoreCallsObserved());
    }

    @Test
    void unsafeOrMissingIdsAreRejectedWithoutSideEffects() throws Exception {
        JsonPersistenceBackend backend = seededRealBackend();
        BackupRestoreService service = serviceForReal(backend);

        List<String> unsafe = List.of("../escape", "sub/dir", "sub\\dir", "..", ".", "",
                "   ", "a b", "a\0b", "x".repeat(129));
        for (String id : unsafe) {
            RestoreResult result = service.restore(id);
            assertFalse(result.isSuccess(), "unsafe id must be rejected: '" + id + "'");
            assertEquals(0, restoreCallsObserved(), "id: " + id);
        }
        RestoreResult missing = service.restore("nof000000-aaaa-1111-bbbb-cccccccccccc");
        assertEquals(BackupRestoreError.BACKUP_NOT_FOUND, missing.error());
        assertFalse(Files.exists(dir.resolve("backups")),
                "restore must never create the backup directory");
    }

    @Test
    void symlinkedSnapshotTargetIsTreatedAsNotFound() throws Exception {
        JsonPersistenceBackend backend = seededRealBackend();
        BackupRestoreService service = serviceForReal(backend);
        Path backups = Files.createDirectories(dir.resolve("backups"));
        Path outside = Files.createTempFile("ace-outside", ".json");
        Files.writeString(outside, seedSnapshotJson(), StandardCharsets.UTF_8);
        Files.createSymbolicLink(backups.resolve("lnk000000-aaaa-1111-bbbb-cccccccccccc.json"),
                outside);
        Files.writeString(backups.resolve("lnk000000-aaaa-1111-bbbb-cccccccccccc.ready"),
                readyMarker(seedSnapshotJson().getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.US_ASCII);

        RestoreResult result = service.restore("lnk000000-aaaa-1111-bbbb-cccccccccccc");

        assertEquals(BackupRestoreError.BACKUP_NOT_FOUND, result.error(),
                "a symlinked snapshot target must not be followed");
        assertEquals(0, restoreCallsObserved());
    }

    @Test
    void symlinkedCommitMarkerIsRejectedWithoutFollowingTheLink() throws Exception {
        JsonPersistenceBackend backend = seededRealBackend();
        BackupRestoreService service = serviceForReal(backend);
        Path backups = Files.createDirectories(dir.resolve("backups"));
        String id = "markerlnk0-aaaa-1111-bbbb-cccccccccccc";
        Path outside = Files.createTempFile("ace-marker-outside", ".ready");
        Files.writeString(outside, readyMarker(seedSnapshotJson().getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.US_ASCII);
        Files.writeString(backups.resolve(id + ".json"), seedSnapshotJson(), StandardCharsets.UTF_8);
        Files.createSymbolicLink(backups.resolve(id + ".ready"), outside);

        RestoreResult result = service.restore(id);

        assertEquals(BackupRestoreError.BACKUP_NOT_FOUND, result.error());
        assertEquals(0, restoreCallsObserved());
    }

    // ---------------- exclusive boundary ----------------

    @Test
    void restoreRunsSafetyBackupAndRestoreInsideOneExclusiveBoundary() throws Exception {
        FakeLifecycle fake = new FakeLifecycle();
        fake.current = seedSnapshotJson().getBytes(StandardCharsets.UTF_8);
        BackupRestoreService service = service(fake);
        BackupResult backup = service.createBackup(null);
        assertTrue(backup.isSuccess());
        fake.events.clear();

        RestoreResult result = service.restore(backup.backupId());

        assertTrue(result.isSuccess(), "restore must succeed: " + result.message());
        assertEquals(List.of("exclusive:start", "backup", "restore", "exclusive:end"),
                fake.events,
                "safety backup and lifecycle.restore must share one exclusive window");
        assertEquals(1, cacheResets, "cache invalidation stays after the successful restore");
    }

    @Test
    void ordinaryRepositoryWritesCannotInterleaveInsideTheExclusiveWindow() throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("excl.json"));
        backend.initialize();
        CountDownLatch insideWindow = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                backend.runExclusive(() -> {
                    insideWindow.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("release latch timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while holding the window", e);
                    }
                    return "done";
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        holder.start();
        assertTrue(insideWindow.await(5, TimeUnit.SECONDS), "holder must enter the window");

        // An ordinary repository write issued while the window is open must WAIT — it may
        // not interleave between the operations the boundary composes.
        UUID lateOwner = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicBoolean writeCompleted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            try {
                backend.create(lateOwner, "Late", Map.of("dollar", Fixtures.amt("1.00")));
            } catch (com.smile.aceeconomy.ports.persistence.PersistenceException e) {
                throw new IllegalStateException(e);
            }
            writeCompleted.set(true);
        });
        writer.start();
        Thread.sleep(200);
        assertFalse(writeCompleted.get(),
                "a repository write must not slip inside the exclusive window");
        assertFalse(writer.getState() == Thread.State.TERMINATED);

        release.countDown();
        holder.join(10_000);
        writer.join(10_000);
        assertTrue(writeCompleted.get(), "the write proceeds once the window closes");
        assertTrue(backend.exists(lateOwner), "the late write landed after the window");
    }

    @Test
    void directFakePersistenceOperationsCannotInterleaveInsideTheExclusiveWindow() throws Exception {
        FakeLifecycle fake = new FakeLifecycle();
        CountDownLatch insideWindow = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                fake.runExclusive(() -> {
                    insideWindow.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("release latch timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while holding the window", e);
                    }
                    return "held";
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        holder.start();
        assertTrue(insideWindow.await(5, TimeUnit.SECONDS), "holder must enter the window");

        // Direct persistence calls issued while the window is open must WAIT on the SAME
        // shared lock — the fake must mirror the real backend boundary instead of running
        // them unlocked between the operations the boundary composes.
        byte[] snapshotBytes = fake.current;
        java.util.concurrent.atomic.AtomicBoolean backupDone =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean restoreDone =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread directBackup = new Thread(() -> {
            try {
                fake.backup(new ByteArrayOutputStream());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            backupDone.set(true);
        });
        Thread directRestore = new Thread(() -> {
            try {
                fake.restore(new ByteArrayInputStream(snapshotBytes));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            restoreDone.set(true);
        });
        directBackup.start();
        directRestore.start();

        // Fast rejection of the unlocked behavior, then a deterministic wait until both
        // callers are parked on the shared lock before the window is released.
        Thread.sleep(200);
        assertFalse(backupDone.get(),
                "a direct backup must not slip inside the exclusive window");
        assertFalse(restoreDone.get(),
                "a direct restore must not slip inside the exclusive window");
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && (directBackup.getState() != Thread.State.WAITING
                        || directRestore.getState() != Thread.State.WAITING)) {
            Thread.sleep(10);
        }
        assertEquals(Thread.State.WAITING, directBackup.getState(),
                "the direct backup must block on the shared persistence lock");
        assertEquals(Thread.State.WAITING, directRestore.getState(),
                "the direct restore must block on the shared persistence lock");
        assertEquals(0, fake.backupCalls, "the blocked backup must not have started");
        assertEquals(0, fake.restoreCalls, "the blocked restore must not have started");

        release.countDown();
        holder.join(10_000);
        directBackup.join(10_000);
        directRestore.join(10_000);
        assertFalse(directBackup.isAlive() || directRestore.isAlive(),
                "both direct operations must finish once the window closes");
        assertTrue(backupDone.get(), "the direct backup proceeds after the window closes");
        assertTrue(restoreDone.get(), "the direct restore proceeds after the window closes");
        assertEquals(1, fake.backupCalls);
        assertEquals(1, fake.restoreCalls);
    }

    @Test
    void controlledDirectoryRequiresSecureDirectoryHandlesOnThisPlatform() throws Exception {
        Path backups = Files.createDirectories(dir.resolve("backups"));
        try (var stream = Files.newDirectoryStream(backups)) {
            assertTrue(stream instanceof java.nio.file.SecureDirectoryStream,
                    "this platform's filesystem provider must support secure directory "
                            + "handles; the service fails closed without them");
        }
    }

    @Test
    void secureHandleOpeningFailsClosedWhenOpenedDirectoryDiffersFromExpected()
            throws Exception {
        Path expectedDir = Files.createDirectories(dir.resolve("backups"));
        Object expectedKey = Files.readAttributes(expectedDir, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        // Simulates the validated entry being swapped before the open: a DIFFERENT real
        // directory is opened while the validated identity is expected.
        Path swappedDir = Files.createTempDirectory("ace-swapped");
        BackupRestoreService service = service(new FakeLifecycle());

        IOException e = assertThrows(IOException.class,
                () -> service.openSecureHandle(swappedDir, expectedKey));

        assertTrue(e.getMessage().contains("changed"),
                "an identity mismatch must fail closed: " + e.getMessage());
    }

    @Test
    void secureHandleOpensWhenTheOpenedDirectoryMatchesTheValidatedIdentity()
            throws Exception {
        Path expectedDir = Files.createDirectories(dir.resolve("backups"));
        Object expectedKey = Files.readAttributes(expectedDir, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        BackupRestoreService service = service(new FakeLifecycle());

        try (var handle = service.openSecureHandle(expectedDir, expectedKey)) {
            assertNotNull(handle, "the matching directory must open as a verified handle");
        }
    }

    // ---------------- helpers ----------------

    private static final String SEEDED_OWNER = "11111111-2222-3333-4444-555555555555";

    private JsonPersistenceBackend seededRealBackend() {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("data-v2.json"));
        backend.initialize();
        UUID owner = UUID.fromString(SEEDED_OWNER);
        backend.create(owner, "Alice", Map.of("dollar", Fixtures.amt("50.00")));
        return backend;
    }

    private BackupRestoreService serviceForReal(JsonPersistenceBackend backend) {
        this.lifecycle = null; // real backend drives lifecycle here
        this.counting = new CountingLifecycle(backend);
        this.cacheResets = 0;
        return new BackupRestoreService(counting, dir, () -> false, () -> CURRENCIES,
                () -> cacheResets++, () -> Instant.parse("2026-08-24T09:30:00Z"),
                () -> "aaaa1111");
    }

    /** Restore invocations observed on the lifecycle the current test wired. */
    private int restoreCallsObserved() {
        if (lifecycle instanceof FakeLifecycle fake) {
            return fake.restoreCalls;
        }
        return counting == null ? -1 : counting.restoreCalls;
    }

    private void writeRawSnapshot(String id, String content) throws IOException {
        Path backups = Files.createDirectories(dir.resolve("backups"));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(backups.resolve(id + ".json"), bytes);
        Files.writeString(backups.resolve(id + ".ready"), readyMarker(bytes),
                StandardCharsets.US_ASCII);
    }

    private void writeRawSnapshotWithoutMarker(String id, String content) throws IOException {
        Path backups = Files.createDirectories(dir.resolve("backups"));
        Files.writeString(backups.resolve(id + ".json"), content, StandardCharsets.UTF_8);
    }

    private static String readyMarker(byte[] bytes) {
        try {
            return "sha256=" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)) + "\n";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("JDK must provide SHA-256", e);
        }
    }

    private int snapshotCount() throws IOException {
        Path backups = dir.resolve("backups");
        if (!Files.exists(backups)) {
            return 0;
        }
        try (var files = Files.list(backups)) {
            return (int) files.count();
        }
    }

    private static String seedSnapshotJson() {
        return "{\"schemaVersion\":1,\"accounts\":{\"" + SEEDED_OWNER + "\":{\"owner\":\""
                + SEEDED_OWNER + "\",\"ownerName\":\"Alice\",\"balances\":{\"dollar\":\"50.00\"}}},"
                + "\"transactions\":[],\"nonces\":{}}";
    }

    private static String txSnapshot(String id, String txFields) {
        return "{\"schemaVersion\":1,\"accounts\":{},\"transactions\":[{\"id\":\""
                + UUID.randomUUID() + "\",\"accountId\":\"" + SEEDED_OWNER + "\"," + txFields
                + "}],\"nonces\":{}}";
    }
}
