package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.IssuanceDebitResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full atomic chain on REAL backends with proper seeds (phase-1 red).
 *
 * <p>Each test seeds a real backend fixture (account with balance plus
 * {@code PREPARED} record), then walks one breakpoint of
 * {@code prepare -> commitDebit -> markDeliveryStarted -> markDelivered ->
 * recover}. The phase-1 backends persist progress only transiently and their
 * atomic stubs always refuse with {@code ACCOUNT_MISSING}, so every commit
 * assertion fails until phase 2 lands the additive section / table with the
 * single-transaction commits. Balance assertions pin value conservation: a
 * refused commit must leave the balance exactly where it was.</p>
 */
class AtomicBanknoteIssuanceBackendTest {

    private static final DebtPolicy DEBT = DebtPolicy.disabled();

    private Transaction preparedDebit(UUID txId, UUID account, Amount amount,
                                      Amount before, Amount after, UUID nonce) {
        return new Transaction(txId, account, null, "dollar", amount,
                TransactionType.WITHDRAW, before, after, Instant.now(),
                BanknoteIssuanceReasons.issueReason(nonce));
    }

    @Test
    void jsonCommitDebitCommitsBalanceAndIssuanceTogether(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("atomic.json");
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            AtomicBanknoteIssuanceStore store = backend;
            store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
            Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
            Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                    before, before.subtract(amount), nonce);

            IssuanceDebitResult result = store.commitDebit(nonce, prepared, DEBT);

            assertTrue(result.isCommitted(),
                    "atomic debit must commit balance plus issuance together, got " + result);
            assertEquals(0, before.subtract(amount).compareTo(result.balanceAfter()),
                    "authoritative after-balance must be exactly before minus amount");
            assertEquals(BanknoteIssuanceStatus.DEBIT_COMMITTED, result.storedIssuance().status());
            assertEquals(0, before.subtract(amount).compareTo(
                    backend.load(account).orElseThrow().balanceOf("dollar")),
                    "durable balance must match the authoritative receipt");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqliteCommitDebitCommitsBalanceAndIssuanceTogether(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("atomic.db");
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        backend.initialize();
        try {
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            AtomicBanknoteIssuanceStore store = backend;
            store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
            Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
            Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                    before, before.subtract(amount), nonce);

            IssuanceDebitResult result = store.commitDebit(nonce, prepared, DEBT);

            assertTrue(result.isCommitted(),
                    "atomic debit must commit balance plus issuance together, got " + result);
            assertEquals(BanknoteIssuanceStatus.DEBIT_COMMITTED, result.storedIssuance().status());
        } finally {
            backend.close();
        }
    }

    @Test
    void replayWithIdenticalPayloadObservesReplay(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("replay.json");
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            AtomicBanknoteIssuanceStore store = backend;
            store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
            Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
            UUID debitId = UUID.randomUUID();
            Transaction prepared = preparedDebit(debitId, account, amount,
                    before, before.subtract(amount), nonce);
            IssuanceDebitResult first = store.commitDebit(nonce, prepared, DEBT);
            assertTrue(first.isCommitted(), "first commit must commit, got " + first);

            IssuanceDebitResult second = store.commitDebit(nonce, prepared, DEBT);
            assertTrue(second.isReplay(),
                    "identical replay must observe REPLAY with no second charge, got " + second);
            assertEquals(0, before.subtract(amount).compareTo(
                    backend.load(account).orElseThrow().balanceOf("dollar")),
                    "a replay must never charge twice");
        } finally {
            backend.close();
        }
    }

    @Test
    void payloadMismatchOnReplayIsRejected(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("mismatch.json");
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            AtomicBanknoteIssuanceStore store = backend;
            store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                    Amount.of(100L, 2), Instant.now()));

            com.smile.aceeconomy.ports.persistence.PersistenceException mismatch =
                    assertThrows(com.smile.aceeconomy.ports.persistence.PersistenceException.class,
                            () -> store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                                    Amount.of(200L, 2), Instant.now())),
                            "re-preparing the same nonce with a different amount must throw payload mismatch");
            assertTrue(mismatch.getMessage() != null
                            && mismatch.getMessage().contains("payload mismatch"),
                    "diagnosis must name payload mismatch, got: " + mismatch.getMessage());
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "a rejected re-prepare must never move money");
        } finally {
            backend.close();
        }
    }

    @Test
    void illegalDeliveryTransitionThrows(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("cas.json");
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            AtomicBanknoteIssuanceStore store = backend;
            store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                    amount, Instant.now()));

            boolean threw = false;
            try {
                store.markDelivered(nonce);
            } catch (com.smile.aceeconomy.ports.persistence.PersistenceException expected) {
                threw = true;
            }
            assertTrue(threw,
                    "PREPARED -> DELIVERED must throw illegal-transition instead of skipping"
                            + " the debit and delivery marks");
            Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
            Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                    before, before.subtract(amount), nonce);
            IssuanceDebitResult committed = store.commitDebit(nonce, prepared, DEBT);
            assertTrue(committed.isCommitted(),
                    "commitDebit must commit before the delivery marks, got " + committed);
            com.smile.aceeconomy.domain.BanknoteIssuance started =
                    store.markDeliveryStarted(nonce);
            assertEquals(com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DELIVERY_STARTED,
                    started.status(),
                    "DEBIT_COMMITTED -> DELIVERY_STARTED must succeed");
            com.smile.aceeconomy.domain.BanknoteIssuance delivered =
                    store.markDelivered(nonce);
            assertEquals(com.smile.aceeconomy.domain.BanknoteIssuanceStatus.DELIVERED,
                    delivered.status(),
                    "DELIVERY_STARTED -> DELIVERED must succeed");
            assertEquals(0, before.subtract(amount).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "the full delivery chain must keep the single committed charge");
        } finally {
            backend.close();
        }
    }
}
