package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.PersistenceBackendFactory;
import com.smile.aceeconomy.infrastructure.persistence.StorageConfig;
import com.smile.aceeconomy.ports.persistence.ImportCommitResult;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red: one JSON import commits the key, the balance and the audit record together, or none
 * of them — including the create-missing path when the audit write itself fails.
 */
class JsonAtomicImportTest {

    private JsonPersistenceBackend backendFor(Path file) {
        return new JsonPersistenceBackend(file);
    }

    private Account preparedNew(UUID owner, String target) {
        return Account.create(owner, "imported-" + owner.toString().substring(0, 8),
                Map.of("dollar", Fixtures.amt(target)));
    }

    private Transaction preparedSet(UUID id, UUID owner, String target, String before) {
        return Fixtures.tx(id, owner, null, "dollar",
                Fixtures.amt(target), TransactionType.SET,
                Fixtures.amt(before), Fixtures.amt(target));
    }

    @Test
    void commitPersistsBalanceRecordAndNonceAndSurvivesRestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("import.json");
        JsonPersistenceBackend backend = backendFor(file);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        UUID nonce = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        Account prepared = backend.load(owner).orElseThrow()
                .setBalance("dollar", Fixtures.amt("500.00"));

        ImportCommitResult result = backend.importPrepared(nonce, prepared,
                preparedSet(txId, owner, "500.00", "100.00"), false);

        assertTrue(result.isCommitted());
        assertEquals(0, Fixtures.amt("100.00").compareTo(result.balanceBefore()));
        assertEquals(0, Fixtures.amt("500.00").compareTo(result.balanceAfter()));
        assertEquals(txId, result.transactionId());
        assertTrue(backend.isConsumed(nonce));
        backend.close();

        JsonPersistenceBackend reopened = backendFor(file);
        reopened.initialize();
        assertEquals(0, Fixtures.amt("500.00")
                .compareTo(reopened.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(1, reopened.loadByAccount(owner).size());
        assertTrue(reopened.isConsumed(nonce));
        reopened.close();
    }

    @Test
    void replayNeverDoubleApplies(@TempDir Path dir) {
        JsonPersistenceBackend backend = backendFor(dir.resolve("replay.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("0.00")));
        UUID nonce = UUID.randomUUID();
        Account prepared = backend.load(owner).orElseThrow()
                .setBalance("dollar", Fixtures.amt("25.00"));

        assertTrue(backend.importPrepared(nonce, prepared,
                preparedSet(UUID.randomUUID(), owner, "25.00", "0.00"), false).isCommitted());

        ImportCommitResult second = backend.importPrepared(nonce, prepared,
                preparedSet(UUID.randomUUID(), owner, "25.00", "25.00"), false);
        assertTrue(second.isReplay());
        assertEquals(0, Fixtures.amt("25.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(1, backend.loadAll().size());
        backend.close();
    }

    @Test
    void sameTransactionResubmittedReturnsReplayWithoutWrites(@TempDir Path dir) {
        JsonPersistenceBackend backend = backendFor(dir.resolve("same-tx-replay.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("0.00")));
        UUID nonce = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        Account prepared = backend.load(owner).orElseThrow()
                .setBalance("dollar", Fixtures.amt("25.00"));

        assertTrue(backend.importPrepared(nonce, prepared,
                preparedSet(txId, owner, "25.00", "0.00"), false).isCommitted());

        ImportCommitResult second = assertDoesNotThrow(() -> backend.importPrepared(nonce, prepared,
                preparedSet(txId, owner, "25.00", "0.00"), false));
        assertTrue(second.isReplay(),
                "a resubmitted prepared transaction must report REPLAY, not a duplicate-id failure");
        assertEquals(0, Fixtures.amt("25.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(1, backend.loadByAccount(owner).size(),
                "the replayed transaction must not add a second audit record");
        assertEquals(1, backend.loadAll().size());
        backend.close();
    }

    @Test
    void distinctNoncesOnOneAccountEachCommitOnce(@TempDir Path dir) {
        JsonPersistenceBackend backend = backendFor(dir.resolve("distinct.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("0.00")));
        Account first = backend.load(owner).orElseThrow()
                .setBalance("dollar", Fixtures.amt("10.00"));
        assertTrue(backend.importPrepared(UUID.randomUUID(), first,
                preparedSet(UUID.randomUUID(), owner, "10.00", "0.00"), false).isCommitted());

        Account second = backend.load(owner).orElseThrow()
                .setBalance("dollar", Fixtures.amt("15.00"));
        ImportCommitResult result = backend.importPrepared(UUID.randomUUID(), second,
                preparedSet(UUID.randomUUID(), owner, "15.00", "10.00"), false);

        assertTrue(result.isCommitted());
        assertEquals(0, Fixtures.amt("15.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(2, backend.loadByAccount(owner).size());
        backend.close();
    }

    @Test
    void missingWithoutCreateLeavesKeyUsable(@TempDir Path dir) {
        JsonPersistenceBackend backend = backendFor(dir.resolve("missing.json"));
        backend.initialize();
        UUID stranger = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();

        ImportCommitResult result = backend.importPrepared(nonce,
                preparedNew(stranger, "10.00"),
                preparedSet(UUID.randomUUID(), stranger, "10.00", "0.00"), false);

        assertTrue(result.isAccountMissing());
        assertFalse(backend.isConsumed(nonce));
        assertFalse(backend.exists(stranger));

        ImportCommitResult created = backend.importPrepared(nonce,
                preparedNew(stranger, "10.00"),
                preparedSet(UUID.randomUUID(), stranger, "10.00", "0.00"), true);
        assertTrue(created.isCommitted());
        assertTrue(backend.isConsumed(nonce));
        assertEquals(0, Fixtures.amt("10.00")
                .compareTo(backend.load(stranger).orElseThrow().balances().get("dollar")));
        backend.close();
    }

    @Test
    void auditFailureOnCreateLeavesNoAccountAndFreeKey(@TempDir Path dir) {
        JsonPersistenceBackend backend = backendFor(dir.resolve("createfail.json"));
        backend.initialize();
        UUID stranger = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        UUID duplicateId = UUID.randomUUID();
        backend.append(Fixtures.tx(duplicateId, UUID.randomUUID(), null, "dollar",
                Fixtures.amt("1.00"), TransactionType.DEPOSIT,
                Fixtures.amt("0.00"), Fixtures.amt("1.00")));

        assertThrows(PersistenceException.class, () -> backend.importPrepared(nonce,
                preparedNew(stranger, "10.00"),
                preparedSet(duplicateId, stranger, "10.00", "0.00"), true));

        assertFalse(backend.exists(stranger),
                "a failed audit write must not leave the created account behind");
        assertFalse(backend.isConsumed(nonce),
                "a failed audit write must not burn the key");
        assertEquals(1, backend.loadAll().size());
        backend.close();
    }

    @Test
    void auditFailureOnExistingLeavesBalanceAndKeyUntouched(@TempDir Path dir) {
        JsonPersistenceBackend backend = backendFor(dir.resolve("existingfail.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        UUID nonce = UUID.randomUUID();
        UUID duplicateId = UUID.randomUUID();
        backend.append(Fixtures.tx(duplicateId, owner, null, "dollar",
                Fixtures.amt("1.00"), TransactionType.DEPOSIT,
                Fixtures.amt("100.00"), Fixtures.amt("101.00")));
        Account prepared = backend.load(owner).orElseThrow()
                .setBalance("dollar", Fixtures.amt("500.00"));

        assertThrows(PersistenceException.class, () -> backend.importPrepared(nonce, prepared,
                preparedSet(duplicateId, owner, "500.00", "100.00"), false));

        assertEquals(0, Fixtures.amt("100.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertFalse(backend.isConsumed(nonce));
        assertEquals(1, backend.loadAll().size());
        backend.close();
    }

    @Test
    void derivesFromLiveRowInsteadOfStaleSnapshot(@TempDir Path dir) {
        JsonPersistenceBackend backend = backendFor(dir.resolve("live.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        // A general transaction commits first; the importer's pre-read is now stale.
        Account moved = backend.load(owner).orElseThrow()
                .deposit("dollar", Fixtures.amt("50.00"));
        backend.save(backend.load(owner).orElseThrow(), moved);
        Account stalePrepared = Account.create(owner, "alice",
                Map.of("dollar", Fixtures.amt("500.00")));
        UUID nonce = UUID.randomUUID();

        ImportCommitResult result = backend.importPrepared(nonce, stalePrepared,
                preparedSet(UUID.randomUUID(), owner, "500.00", "100.00"), false);

        assertTrue(result.isCommitted());
        assertEquals(0, Fixtures.amt("150.00").compareTo(result.balanceBefore()),
                "the audit must bracket the live movement, never the stale snapshot");
        assertEquals(0, Fixtures.amt("500.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        backend.close();
    }

    @Test
    void factoryWiringExposesSameBackendForImportStore(@TempDir Path dir) throws Exception {
        List<Runnable> cleanups = new ArrayList<>();
        var result = PersistenceBackendFactory.create(
                new StorageConfig.Json(dir.resolve("wired.json")), cleanups::add);
        try {
            assertTrue(result.imports() == result.accounts(),
                    "the atomic import store must be the same backend instance");
        } finally {
            for (int i = cleanups.size() - 1; i >= 0; i--) {
                cleanups.get(i).run();
            }
        }
    }

    @Test
    void concurrentSameKeyHasExactlyOneCommit(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend = backendFor(dir.resolve("conc.json"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("0.00")));
        UUID nonce = UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ImportCommitResult>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                Account prepared = Account.create(owner, "alice",
                        Map.of("dollar", Fixtures.amt("10.00")));
                return backend.importPrepared(nonce, prepared,
                        preparedSet(UUID.randomUUID(), owner, "10.00", "0.00"), false);
            }));
        }
        int commits = 0;
        for (Future<ImportCommitResult> f : futures) {
            if (f.get(10, TimeUnit.SECONDS).isCommitted()) {
                commits++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, commits, "exactly one concurrent import may commit");
        assertEquals(0, Fixtures.amt("10.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(1, backend.loadAll().size());
        backend.close();
    }
}
