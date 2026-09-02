package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;

/**
 * Real, offline SQLite JDBC contract tests for {@link SqlBackend}. Uses a temporary on-disk database
 * so restart / reload behaviour is exercised against actual persisted state.
 */
final class SqlBackendContractTest {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on test classpath", e);
        }
    }

    @TempDir
    Path dir;

    private SqlBackend backendFor(Path dbFile) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        return new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
    }

    private Transaction sampleTx(UUID id, UUID account, String currency, String amount,
                                TransactionType type, String before, String after) {
        return Fixtures.tx(id, account, null, currency,
                Fixtures.amt(amount), type, Fixtures.amt(before), Fixtures.amt(after));
    }

    @Test
    void freshCreateInitializesSchema() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("fresh.db"));
        backend.initialize();
        assertTrue(backend.isInitialized());
        assertEquals(1, backend.schemaVersion());
        assertTrue(backend.loadAll().isEmpty());
        backend.close();
    }

    @Test
    void initializeIsIdempotentOnRestart() throws Exception {
        Path db = dir.resolve("restart.db");
        SqlBackend first = backendFor(db);
        first.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        first.append(sampleTx(id, account, "dollar", "10.00", TransactionType.DEPOSIT, "0.00", "10.00"));
        first.close();

        // Reopen the same file: initialize must not error and must see persisted data.
        SqlBackend second = backendFor(db);
        second.initialize();
        assertEquals(1, second.schemaVersion());
        List<Transaction> all = second.loadAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        second.close();
    }

    @Test
    void accountRoundTrip() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("account.db"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        Account created = backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        assertTrue(backend.exists(owner));
        Account loaded = backend.load(owner).orElseThrow();
        assertEquals("alice", loaded.ownerName());
        assertEquals(0, Fixtures.amt("100.00").compareTo(loaded.balances().get("dollar")));
        assertEquals(created.owner(), loaded.owner());
        backend.close();
    }

    @Test
    void transactionRoundTripAndLoadByAccount() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("tx.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "10.00", TransactionType.DEPOSIT, "0.00", "10.00"));

        List<Transaction> all = backend.loadAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        assertEquals(TransactionType.DEPOSIT, all.get(0).type());

        List<Transaction> byAccount = backend.loadByAccount(account);
        assertEquals(1, byAccount.size());
        assertEquals(id, byAccount.get(0).id());
        backend.close();
    }

    @Test
    void atomicBatchPersistsAll() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("batch.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        backend.appendBatch(List.of(
                sampleTx(UUID.randomUUID(), account, "dollar", "5.00", TransactionType.DEPOSIT, "0.00", "5.00"),
                sampleTx(UUID.randomUUID(), account, "dollar", "3.00", TransactionType.WITHDRAW, "5.00", "2.00")
        ));
        assertEquals(2, backend.loadAll().size());
        assertEquals(2, backend.loadByAccount(account).size());
        backend.close();
    }

    @Test
    void batchFailureRollsBackEntireBatch() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("batchfail.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID existing = UUID.randomUUID();
        backend.append(sampleTx(existing, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));

        UUID good = UUID.randomUUID();
        // Second entry duplicates an already-committed id -> the whole batch must fail and the
        // "good" record must NOT be visible (all-or-none).
        assertThrows(PersistenceException.class, () -> backend.appendBatch(List.of(
                sampleTx(good, account, "dollar", "2.00", TransactionType.DEPOSIT, "1.00", "3.00"),
                sampleTx(existing, account, "dollar", "9.00", TransactionType.DEPOSIT, "1.00", "10.00")
        )));

        List<Transaction> all = backend.loadAll();
        assertEquals(1, all.size());
        assertEquals(existing, all.get(0).id());
        backend.close();
    }

    @Test
    void duplicateAppendRejected() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("dup.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));
        assertThrows(PersistenceException.class,
                () -> backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00")));
        backend.close();
    }

    @Test
    void rollbackMarkerIsSetAndIdempotent() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("revert.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));

        assertFalse(backend.isReverted(id));
        backend.markReverted(id);
        assertTrue(backend.isReverted(id));
        // Idempotent: re-marking an existing record must not throw.
        backend.markReverted(id);
        assertTrue(backend.isReverted(id));
        // Unknown id must throw.
        assertThrows(PersistenceException.class, () -> backend.markReverted(UUID.randomUUID()));
        backend.close();
    }

    @Test
    void backupAndRestoreRoundTrip() throws Exception {
        Path db = dir.resolve("backup.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "10.00", TransactionType.DEPOSIT, "0.00", "10.00"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        backend.backup(out);
        byte[] snapshot = out.toByteArray();
        assertTrue(snapshot.length > 0);

        // Add an extra record, then restore from the snapshot: the extra must be gone.
        backend.append(sampleTx(UUID.randomUUID(), account, "dollar", "5.00", TransactionType.DEPOSIT, "10.00", "15.00"));
        assertEquals(2, backend.loadAll().size());

        backend.restore(new ByteArrayInputStream(snapshot));
        List<Transaction> restored = backend.loadAll();
        assertEquals(1, restored.size());
        assertEquals(id, restored.get(0).id());
        backend.close();
    }

    @Test
    void corruptBackupDoesNotDestroyLiveData() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("corrupt.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));

        assertThrows(PersistenceException.class,
                () -> backend.restore(new ByteArrayInputStream("this is not json{".getBytes())));

        // Live data must be untouched.
        List<Transaction> all = backend.loadAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        backend.close();
    }

    @Test
    void restoreRejectsSchemaVersionMismatch() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("mismatch.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));

        String bad = "{\"schemaVersion\":99,\"accounts\":{},\"transactions\":[]}";
        assertThrows(PersistenceException.class,
                () -> backend.restore(new ByteArrayInputStream(bad.getBytes())));

        List<Transaction> all = backend.loadAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        backend.close();
    }

    @Test
    void needsRecreationDetectsPartialInitAndFullInit() throws Exception {
        Path db = dir.resolve("recreate.db");
        SqlBackend backend = backendFor(db);
        // Fresh: no v2 tables at all -> no recreation needed.
        assertFalse(backend.needsRecreation());

        // Simulate a partial init: an accounts table exists but no schema-version table.
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + V2Schema.accountsTable() + " (owner TEXT PRIMARY KEY, owner_name TEXT)");
        }
        assertTrue(backend.needsRecreation());

        // Full init clears the partial state and makes recreation unnecessary.
        backend.initialize();
        assertFalse(backend.needsRecreation());
        assertEquals(1, backend.schemaVersion());
        backend.close();
    }

    @Test
    void truncateAndRecreateClearsData() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("truncate.db"));
        backend.initialize();
        UUID account = UUID.randomUUID();
        backend.append(sampleTx(UUID.randomUUID(), account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));
        assertEquals(1, backend.loadAll().size());

        backend.truncateAndRecreate();
        assertTrue(backend.loadAll().isEmpty());
        assertEquals(1, backend.schemaVersion());
        backend.close();
    }

    // ---------------- atomic reversal ----------------

    @Test
    void applyReversalPersistsBalancesRecordsAndMarkersAndSurvivesRestart() throws Exception {
        Path db = dir.resolve("reversal.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(sampleTx(originalId, owner, "dollar", "100.00",
                TransactionType.DEPOSIT, "0.00", "100.00"));

        Account reversed = backend.load(owner).orElseThrow()
                .withdraw("dollar", Fixtures.amt("100.00"));
        Transaction reversalRecord = Fixtures.tx(UUID.randomUUID(), owner, null, "dollar",
                Fixtures.amt("100.00"), TransactionType.WITHDRAW,
                Fixtures.amt("100.00"), Fixtures.amt("0.00"));

        backend.applyReversal(List.of(reversed), List.of(reversalRecord), List.of(originalId));

        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(2, backend.loadAll().size());
        assertTrue(backend.isReverted(originalId));
        backend.close();

        // Reopen the same file: every effect must have been committed together.
        SqlBackend reopened = backendFor(db);
        reopened.initialize();
        assertEquals(0, Fixtures.amt("0.00")
                .compareTo(reopened.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(2, reopened.loadAll().size());
        assertTrue(reopened.isReverted(originalId));
        reopened.close();
    }

    @Test
    void applyReversalWithUnknownMarkerRollsBackEverything() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("reversalfail.db"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(sampleTx(originalId, owner, "dollar", "100.00",
                TransactionType.DEPOSIT, "0.00", "100.00"));

        Account reversed = backend.load(owner).orElseThrow()
                .withdraw("dollar", Fixtures.amt("100.00"));
        Transaction reversalRecord = Fixtures.tx(UUID.randomUUID(), owner, null, "dollar",
                Fixtures.amt("100.00"), TransactionType.WITHDRAW,
                Fixtures.amt("100.00"), Fixtures.amt("0.00"));

        assertThrows(PersistenceException.class, () -> backend.applyReversal(
                List.of(reversed), List.of(reversalRecord), List.of(UUID.randomUUID())));

        // The JDBC transaction rolled back: no balance change, no record, no marker.
        assertEquals(0, Fixtures.amt("100.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(1, backend.loadAll().size());
        assertFalse(backend.isReverted(originalId));

        // Backend remains usable after the rollback.
        backend.append(sampleTx(UUID.randomUUID(), owner, "dollar", "1.00",
                TransactionType.DEPOSIT, "100.00", "101.00"));
        assertEquals(2, backend.loadAll().size());
        backend.close();
    }

    @Test
    void applyReversalWithDuplicateReversalIdRollsBackEverything() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("reversaldup.db"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        UUID originalId = UUID.randomUUID();
        backend.append(sampleTx(originalId, owner, "dollar", "100.00",
                TransactionType.DEPOSIT, "0.00", "100.00"));

        Account reversed = backend.load(owner).orElseThrow()
                .withdraw("dollar", Fixtures.amt("100.00"));
        Transaction duplicate = sampleTx(originalId, owner, "dollar", "100.00",
                TransactionType.WITHDRAW, "100.00", "0.00");

        assertThrows(PersistenceException.class, () -> backend.applyReversal(
                List.of(reversed), List.of(duplicate), List.of(originalId)));

        assertEquals(0, Fixtures.amt("100.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(1, backend.loadAll().size());
        assertFalse(backend.isReverted(originalId));
        backend.close();
    }

    // ---------------- durable nonce store ----------------

    @Test
    void nonceConsumeIsFirstWriterWinsAndSurvivesRestart() throws Exception {
        Path db = dir.resolve("nonce.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();
        UUID nonce = UUID.randomUUID();
        assertTrue(backend.consume(nonce), "first consume must win");
        assertFalse(backend.consume(nonce), "second consume of the same nonce is a replay");
        assertTrue(backend.isConsumed(nonce));
        backend.close();

        SqlBackend reopened = backendFor(db);
        reopened.initialize();
        assertTrue(reopened.isConsumed(nonce), "consumed nonce must survive a restart");
        assertFalse(reopened.consume(nonce), "replay after restart must still be rejected");
        assertTrue(reopened.consume(UUID.randomUUID()), "a fresh nonce is accepted after restart");
        reopened.close();
    }

    @Test
    void concurrentNonceConsumeHasExactlyOneWinner() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("nonceconc.db"));
        backend.initialize();
        UUID nonce = UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> backend.consume(nonce)));
        }
        int winners = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(10, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, winners, "exactly one concurrent consumer may win");
        assertTrue(backend.isConsumed(nonce));
        backend.close();
    }

    @Test
    void backupRestoreCarriesConsumedNonces() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("noncebackup.db"));
        backend.initialize();
        UUID consumed = UUID.randomUUID();
        assertTrue(backend.consume(consumed));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        backend.backup(out);

        UUID extra = UUID.randomUUID();
        assertTrue(backend.consume(extra));

        backend.restore(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(backend.isConsumed(consumed), "restored snapshot keeps earlier consumptions");
        assertFalse(backend.isConsumed(extra), "consumption after the snapshot must not survive restore");
        backend.close();
    }

    // ---------------- atomic redemption ----------------

    @Test
    void redeemCommitsBalanceRecordAndNonceAndSurvivesRestart() throws Exception {
        Path db = dir.resolve("redeem.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        UUID nonce = UUID.randomUUID();

        RedemptionResult result =
                backend.redeem(nonce, owner, "dollar", Fixtures.amt("50.00"));

        assertTrue(result.isCommitted(), "a fresh nonce with an existing account must commit");
        assertEquals(0, Fixtures.amt("100.00").compareTo(result.balanceBefore()));
        assertEquals(0, Fixtures.amt("150.00").compareTo(result.balanceAfter()));
        assertTrue(backend.isConsumed(nonce));
        backend.close();

        // Reopen: balance, audit record and nonce must all have been committed together.
        SqlBackend reopened = backendFor(db);
        reopened.initialize();
        assertEquals(0, Fixtures.amt("150.00")
                .compareTo(reopened.load(owner).orElseThrow().balances().get("dollar")));
        List<Transaction> records = reopened.loadByAccount(owner);
        assertEquals(1, records.size());
        assertEquals(TransactionType.DEPOSIT, records.get(0).type());
        assertEquals("banknote-deposit", records.get(0).reason());
        assertEquals(0, Fixtures.amt("50.00").compareTo(records.get(0).amount()));
        assertTrue(reopened.isConsumed(nonce), "consumed redemption nonce must survive a restart");
        reopened.close();
    }

    @Test
    void redeemDuplicateNonceIsReplayWithoutDoubleCredit() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("redeemdup.db"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("0.00")));
        UUID nonce = UUID.randomUUID();
        assertTrue(backend.redeem(nonce, owner, "dollar", Fixtures.amt("25.00")).isCommitted());

        RedemptionResult second =
                backend.redeem(nonce, owner, "dollar", Fixtures.amt("25.00"));

        assertTrue(second.isReplay(), "the same nonce must never credit twice");
        assertEquals(0, Fixtures.amt("25.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(1, backend.loadAll().size(), "no duplicate audit record may appear");
        backend.close();
    }

    @Test
    void redeemUnknownAccountLeavesNonceUsable() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("redeemmissing.db"));
        backend.initialize();
        UUID stranger = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();

        RedemptionResult result =
                backend.redeem(nonce, stranger, "dollar", Fixtures.amt("10.00"));

        assertTrue(result.isAccountMissing());
        assertFalse(backend.isConsumed(nonce),
                "an unknown account must not burn the note's nonce");

        // Once the account exists the same note redeems normally.
        backend.create(stranger, "late", Map.of());
        assertTrue(backend.redeem(nonce, stranger, "dollar", Fixtures.amt("10.00")).isCommitted());
        assertTrue(backend.isConsumed(nonce));
        backend.close();
    }

    @Test
    void concurrentRedeemSameNonceHasExactlyOneCommit() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("redeemconc.db"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("0.00")));
        UUID nonce = UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<RedemptionResult>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() ->
                    backend.redeem(nonce, owner, "dollar", Fixtures.amt("10.00"))));
        }
        int commits = 0;
        for (Future<RedemptionResult> f : futures) {
            if (f.get(10, TimeUnit.SECONDS).isCommitted()) {
                commits++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, commits, "exactly one concurrent redemption may commit");
        assertEquals(0, Fixtures.amt("10.00")
                .compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(1, backend.loadAll().size());
        backend.close();
    }

    // ---------------- prepared atomic redemption ----------------

    @Test
    void redeemPreparedCommitsAndSurvivesRestart() throws Exception {
        Path db = dir.resolve("prepared.db");
        SqlBackend backend = backendFor(db);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        Account base = backend.load(owner).orElseThrow();
        Account updated = base.deposit("dollar", Fixtures.amt("40.00"));
        Transaction tx = new Transaction(UUID.randomUUID(), owner, null, "dollar",
                Fixtures.amt("40.00"), TransactionType.DEPOSIT,
                Fixtures.amt("100.00"), Fixtures.amt("140.00"), java.time.Instant.now(), "banknote-deposit");
        UUID nonce = UUID.randomUUID();

        RedemptionResult r = backend.redeemPrepared(nonce, updated, tx);
        assertTrue(r.isCommitted());
        assertEquals(0, Fixtures.amt("140.00").compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertTrue(backend.isConsumed(nonce));
        backend.close();

        SqlBackend reopened = backendFor(db);
        reopened.initialize();
        assertEquals(0, Fixtures.amt("140.00").compareTo(reopened.load(owner).orElseThrow().balances().get("dollar")));
        assertTrue(reopened.isConsumed(nonce));
        reopened.close();
    }

    @Test
    void redeemPreparedReplayDoesNotDoubleCredit() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("preparedReplay.db"));
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("0.00")));
        Account base = backend.load(owner).orElseThrow();
        Account upd1 = base.deposit("dollar", Fixtures.amt("10.00"));
        Transaction tx1 = new Transaction(UUID.randomUUID(), owner, null, "dollar",
                Fixtures.amt("10.00"), TransactionType.DEPOSIT,
                Fixtures.amt("0.00"), Fixtures.amt("10.00"), java.time.Instant.now(), "banknote-deposit");
        UUID nonce = UUID.randomUUID();
        assertTrue(backend.redeemPrepared(nonce, upd1, tx1).isCommitted());

        Account upd2 = backend.load(owner).orElseThrow().deposit("dollar", Fixtures.amt("10.00"));
        Transaction tx2 = new Transaction(UUID.randomUUID(), owner, null, "dollar",
                Fixtures.amt("10.00"), TransactionType.DEPOSIT,
                Fixtures.amt("10.00"), Fixtures.amt("20.00"), java.time.Instant.now(), "banknote-deposit");
        RedemptionResult second = backend.redeemPrepared(nonce, upd2, tx2);
        assertTrue(second.isReplay());
        assertEquals(0, Fixtures.amt("10.00").compareTo(backend.load(owner).orElseThrow().balances().get("dollar")));
        assertEquals(1, backend.loadAll().size());
        backend.close();
    }

    @Test
    void redeemPreparedUnknownAccountLeavesNonceUnused() throws Exception {
        SqlBackend backend = backendFor(dir.resolve("preparedMissing.db"));
        backend.initialize();
        UUID stranger = UUID.randomUUID();
        Account fake = Account.create(stranger, "ghost", Map.of("dollar", Fixtures.amt("10.00")));
        Transaction tx = new Transaction(UUID.randomUUID(), stranger, null, "dollar",
                Fixtures.amt("10.00"), TransactionType.DEPOSIT,
                Fixtures.amt("0.00"), Fixtures.amt("10.00"), java.time.Instant.now(), "banknote-deposit");
        UUID nonce = UUID.randomUUID();
        RedemptionResult r = backend.redeemPrepared(nonce, fake, tx);
        assertTrue(r.isAccountMissing());
        assertFalse(backend.isConsumed(nonce));
        backend.close();
    }
}
