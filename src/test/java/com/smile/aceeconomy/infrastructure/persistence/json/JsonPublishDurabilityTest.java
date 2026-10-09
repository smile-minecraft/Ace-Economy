package com.smile.aceeconomy.infrastructure.persistence.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Publish-path durability for the JSON backend, exercised through a scripted
 * {@link JsonFilePublisher} fake.
 *
 * <p>These tests simulate IO failures only. They do not power-cycle hardware
 * and therefore do not prove safety against arbitrary power loss.
 */
final class JsonPublishDurabilityTest {

    @TempDir
    Path dir;

    /** Fake that delegates to the real filesystem but fails on scripted publish phases. */
    private static final class ScriptedPublisher extends DefaultJsonFilePublisher {
        enum Mode {
            NORMAL,
            FAIL_BEFORE_PUBLISH,
            FAIL_TMP_SYNC,
            FAIL_TARGET_SYNC,
            FAIL_DIR_SYNC,
            FAIL_AFTER_PUBLISH,
            ATOMIC_UNSUPPORTED
        }

        private final Path target;
        volatile Mode mode = Mode.NORMAL;
        private volatile Path lastTmp;

        ScriptedPublisher(Path target) {
            this.target = target;
        }

        @Override
        public Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
            lastTmp = super.createTempFile(dir, prefix, suffix);
            return lastTmp;
        }

        @Override
        public void writeString(Path tmp, String content) throws IOException {
            if (mode == Mode.FAIL_BEFORE_PUBLISH) {
                throw new IOException("injected pre-publish write failure");
            }
            super.writeString(tmp, content);
        }

        @Override
        public void moveAtomic(Path tmp, Path target) throws IOException {
            if (mode == Mode.ATOMIC_UNSUPPORTED) {
                throw new AtomicMoveNotSupportedException(
                        tmp.toString(), target.toString(), "injected: filesystem has no atomic move");
            }
            super.moveAtomic(tmp, target);
            if (mode == Mode.FAIL_AFTER_PUBLISH) {
                // The replacement already happened; only the durability proof is missing.
                throw new JsonPostPublishException(
                        "injected post-publish sync failure",
                        new IOException("injected directory sync failure"));
            }
        }

        @Override
        public void syncFile(Path file) throws IOException {
            if (mode == Mode.FAIL_BEFORE_PUBLISH) {
                throw new IOException("injected pre-publish sync failure");
            }
            if (mode == Mode.FAIL_TMP_SYNC && file.equals(lastTmp)) {
                throw new IOException("injected tmp-file sync failure");
            }
            if (mode == Mode.FAIL_TARGET_SYNC && file.equals(target)) {
                throw new IOException("injected post-publish target sync failure");
            }
            super.syncFile(file);
        }

        @Override
        public void syncDirectory(Path directory) throws IOException {
            if (mode == Mode.FAIL_DIR_SYNC) {
                throw new IOException("injected post-publish directory sync failure");
            }
            if (mode == Mode.FAIL_AFTER_PUBLISH) {
                throw new JsonPostPublishException(
                        "injected post-publish directory-sync failure",
                        new IOException("injected directory sync failure"));
            }
            super.syncDirectory(directory);
        }
    }

    private JsonPersistenceBackend openBackend(ScriptedPublisher publisher, String name) {
        Path file = dir.resolve(name);
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file, publisher);
        backend.initialize();
        return backend;
    }

    private void assertNoLeftoverSnapshots() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (fileName.startsWith("ace-json-")) {
                    fail("leftover publish snapshot: " + entry);
                }
            }
        }
    }

    @Test
    void prePublishFailureKeepsOldLiveDataAndLeavesNoPartialSnapshot() throws Exception {
        ScriptedPublisher publisher = new ScriptedPublisher(dir.resolve("pre.json"));
        Path file = dir.resolve("pre.json");
        JsonPersistenceBackend backend = openBackend(publisher, "pre.json");
        try {
            UUID owner = UUID.randomUUID();
            backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            String before = Files.readString(file, StandardCharsets.UTF_8);

            publisher.mode = ScriptedPublisher.Mode.FAIL_BEFORE_PUBLISH;
            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> backend.save(backend.load(owner).orElseThrow(),
                            backend.load(owner).orElseThrow().deposit("dollar", Fixtures.amt("10.00"))));
            assertFalse(failure.isCommitted(), "a pre-publish failure must stay retryable");

            assertEquals(before, Files.readString(file, StandardCharsets.UTF_8),
                    "the live file must be byte-identical after a pre-publish failure");
            assertNoLeftoverSnapshots();
            assertEquals(0, Fixtures.amt("100.00")
                    .compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));

            publisher.mode = ScriptedPublisher.Mode.NORMAL;
            backend.save(backend.load(owner).orElseThrow(),
                    backend.load(owner).orElseThrow().deposit("dollar", Fixtures.amt("10.00")));
            assertEquals(0, Fixtures.amt("110.00")
                    .compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
        } finally {
            backend.close();
        }
    }

    @Test
    void postPublishSyncFailureIsNotDisguisedAsAnUncommittedWrite() throws Exception {
        ScriptedPublisher publisher = new ScriptedPublisher(dir.resolve("post.json"));
        Path file = dir.resolve("post.json");
        JsonPersistenceBackend backend = openBackend(publisher, "post.json");
        try {
            UUID owner = UUID.randomUUID();
            backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            UUID nonce = UUID.randomUUID();

            publisher.mode = ScriptedPublisher.Mode.FAIL_AFTER_PUBLISH;
            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> backend.consume(nonce));
            assertTrue(failure.isCommitted(),
                    "after the live file was replaced the failure must read as committed, never as a safe retry");

            Account live = backend.load(owner).orElseThrow();
            assertEquals(0, Fixtures.amt("100.00").compareTo(live.balanceOf("dollar")));
            assertTrue(backend.isConsumed(nonce),
                    "memory must agree with the published file instead of rolling back over it");
            JsonPersistenceBackend reopened = new JsonPersistenceBackend(file);
            try {
                reopened.initialize();
                assertTrue(reopened.isConsumed(nonce), "the published nonce must be visible after reopening");
            } finally {
                reopened.close();
            }

            PersistenceException halted = assertThrows(PersistenceException.class,
                    () -> backend.create(UUID.randomUUID(), "bob", Map.of("dollar", Fixtures.amt("1.00"))));
            assertTrue(halted.getMessage().contains("halted"),
                    "after an unconfirmed publish the backend must stop writes instead of diverging, got: "
                            + halted.getMessage());

            publisher.mode = ScriptedPublisher.Mode.NORMAL;
            backend.initialize();
            backend.create(UUID.randomUUID(), "bob", Map.of("dollar", Fixtures.amt("1.00")));
        } finally {
            backend.close();
        }
    }

    @Test
    void filesystemWithoutAtomicMoveGetsAnExplicitDiagnosis() throws Exception {
        ScriptedPublisher publisher = new ScriptedPublisher(dir.resolve("atomic.json"));
        Path file = dir.resolve("atomic.json");
        JsonPersistenceBackend backend = openBackend(publisher, "atomic.json");
        try {
            UUID owner = UUID.randomUUID();
            backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            String before = Files.readString(file, StandardCharsets.UTF_8);

            publisher.mode = ScriptedPublisher.Mode.ATOMIC_UNSUPPORTED;
            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> backend.save(backend.load(owner).orElseThrow(),
                            backend.load(owner).orElseThrow().deposit("dollar", Fixtures.amt("10.00"))));
            assertTrue(failure.getMessage().toLowerCase().contains("atomic"),
                    "the diagnosis must name the missing atomic replacement, got: " + failure.getMessage());

            assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
            assertNoLeftoverSnapshots();
            assertEquals(0, Fixtures.amt("100.00")
                    .compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
        } finally {
            backend.close();
        }
    }

    @Test
    void tmpSyncFailureIsPrePublishAndRetryable() throws Exception {
        ScriptedPublisher publisher = new ScriptedPublisher(dir.resolve("tmp-sync.json"));
        Path file = dir.resolve("tmp-sync.json");
        JsonPersistenceBackend backend = openBackend(publisher, "tmp-sync.json");
        try {
            UUID owner = UUID.randomUUID();
            backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            String before = Files.readString(file, StandardCharsets.UTF_8);

            publisher.mode = ScriptedPublisher.Mode.FAIL_TMP_SYNC;
            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> backend.save(backend.load(owner).orElseThrow(),
                            backend.load(owner).orElseThrow().deposit("dollar", Fixtures.amt("10.00"))));
            assertFalse(failure.isCommitted(), "a tmp-sync failure happens before the rename: still retryable");

            assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
            assertNoLeftoverSnapshots();
            assertEquals(0, Fixtures.amt("100.00")
                    .compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));

            publisher.mode = ScriptedPublisher.Mode.NORMAL;
            backend.save(backend.load(owner).orElseThrow(),
                    backend.load(owner).orElseThrow().deposit("dollar", Fixtures.amt("10.00")));
            assertEquals(0, Fixtures.amt("110.00")
                    .compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
        } finally {
            backend.close();
        }
    }

    @Test
    void targetSyncFailureIsPostPublish() throws Exception {
        assertPostPublishClassification(
                ScriptedPublisher.Mode.FAIL_TARGET_SYNC, "target-sync.json");
    }

    @Test
    void directorySyncFailureIsPostPublish() throws Exception {
        assertPostPublishClassification(
                ScriptedPublisher.Mode.FAIL_DIR_SYNC, "dir-sync.json");
    }

    /**
     * A plain {@link IOException} from a post-rename sync must be classified exactly
     * like an explicit post-publish failure: committed, memory kept, writes halted,
     * and the reopened file must match memory on accounts, transactions and nonces.
     */
    private void assertPostPublishClassification(ScriptedPublisher.Mode mode, String name) throws Exception {
        ScriptedPublisher publisher = new ScriptedPublisher(dir.resolve(name));
        Path file = dir.resolve(name);
        JsonPersistenceBackend backend = openBackend(publisher, name);
        try {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            backend.create(alice, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            backend.create(bob, "bob", Map.of("dollar", Fixtures.amt("50.00")));
            backend.transfer(alice, bob, "dollar", Fixtures.amt("30.00"),
                    com.smile.aceeconomy.domain.DebtPolicy.disabled());
            UUID firstNonce = UUID.randomUUID();
            assertTrue(backend.consume(firstNonce));

            java.util.List<UUID> expectedTxIds = new java.util.ArrayList<>();
            for (com.smile.aceeconomy.domain.Transaction t : backend.loadAll()) {
                expectedTxIds.add(t.id());
            }

            UUID failingNonce = UUID.randomUUID();
            publisher.mode = mode;
            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> backend.consume(failingNonce));
            assertTrue(failure.isCommitted(),
                    "a sync failure after the rename must read as committed, never as a safe retry");
            assertTrue(backend.isConsumed(failingNonce), "memory must keep the published state");

            PersistenceException halted = assertThrows(PersistenceException.class,
                    () -> backend.create(UUID.randomUUID(), "mallory", Map.of("dollar", Fixtures.amt("1.00"))));
            assertTrue(halted.getMessage().contains("halted"));

            JsonPersistenceBackend reopened = new JsonPersistenceBackend(file);
            try {
                reopened.initialize();
                assertEquals(0, Fixtures.amt("70.00")
                        .compareTo(reopened.load(alice).orElseThrow().balanceOf("dollar")));
                assertEquals(0, Fixtures.amt("80.00")
                        .compareTo(reopened.load(bob).orElseThrow().balanceOf("dollar")));
                java.util.List<UUID> reopenedTxIds = new java.util.ArrayList<>();
                for (com.smile.aceeconomy.domain.Transaction t : reopened.loadAll()) {
                    reopenedTxIds.add(t.id());
                }
                assertEquals(expectedTxIds, reopenedTxIds);
                assertTrue(reopened.isConsumed(firstNonce));
                assertTrue(reopened.isConsumed(failingNonce));
            } finally {
                reopened.close();
            }

            publisher.mode = ScriptedPublisher.Mode.NORMAL;
            backend.initialize();
            assertTrue(backend.consume(UUID.randomUUID()), "writes resume after re-initialization");
        } finally {
            backend.close();
        }
    }

    @Test
    void restoreOnAtomicUnsupportedKeepsOldState() throws Exception {
        ScriptedPublisher publisher = new ScriptedPublisher(dir.resolve("restore-atomic.json"));
        Path file = dir.resolve("restore-atomic.json");
        JsonPersistenceBackend backend = openBackend(publisher, "restore-atomic.json");
        try {
            UUID alice = UUID.randomUUID();
            backend.create(alice, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            java.io.ByteArrayOutputStream snapshot = new java.io.ByteArrayOutputStream();
            backend.backup(snapshot);

            UUID bob = UUID.randomUUID();
            backend.create(bob, "bob", Map.of("dollar", Fixtures.amt("50.00")));
            String before = Files.readString(file, StandardCharsets.UTF_8);

            publisher.mode = ScriptedPublisher.Mode.ATOMIC_UNSUPPORTED;
            PersistenceException failure = assertThrows(PersistenceException.class,
                    () -> {
                        try {
                            backend.restore(new java.io.ByteArrayInputStream(snapshot.toByteArray()));
                        } catch (IOException e) {
                            throw new AssertionError("restore must fail with the atomic diagnosis, not " + e, e);
                        }
                    });
            assertTrue(failure.getMessage().toLowerCase().contains("atomic"),
                    "the diagnosis must name the missing atomic replacement, got: " + failure.getMessage());
            assertFalse(failure.isCommitted(), "nothing was published: the failure must stay retryable");

            assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
            assertNoLeftoverSnapshots();
            assertTrue(backend.load(bob).isPresent(),
                    "a pre-publish restore failure must not swap memory to the candidate snapshot");
            assertEquals(0, Fixtures.amt("50.00")
                    .compareTo(backend.load(bob).orElseThrow().balanceOf("dollar")));

            publisher.mode = ScriptedPublisher.Mode.NORMAL;
            backend.restore(new java.io.ByteArrayInputStream(snapshot.toByteArray()));
            assertTrue(backend.load(bob).isEmpty(), "the retry with a capable filesystem restores the snapshot");
        } finally {
            backend.close();
        }
    }
}
