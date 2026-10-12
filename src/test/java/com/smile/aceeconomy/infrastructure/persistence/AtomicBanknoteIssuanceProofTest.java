package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.IssuanceDebitResult;
import com.smile.aceeconomy.ports.persistence.IssuanceRefundResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debit-proof binding for the atomic issuance boundary (phase-1 red).
 *
 * <p>A debit proof is never an arbitrary string: the store must look up the
 * durable debit transaction by id and check type, account, currency, amount,
 * reason binding ({@code banknote:issue:&lt;nonce&gt;}) and authoritative
 * before/after, and one debit id must back at most one nonce. Every test below
 * seeds a REAL JSON backend fixture (account plus {@code PREPARED} record),
 * calls the atomic boundary, and asserts the decided kind plus that money did
 * not move on rejection. The phase-1 stubs always refuse with
 * {@code ACCOUNT_MISSING}, so every kind assertion fails until phase 2
 * verifies the binding inside the single storage transaction.</p>
 */
class AtomicBanknoteIssuanceProofTest {

    private static final DebtPolicy DEBT = DebtPolicy.disabled();

    private JsonPersistenceBackend seededBackend(Path dir, UUID account, UUID nonce,
                                                 Amount issuanceAmount) throws Exception {
        Path dataFile = dir.resolve("proof-" + nonce + ".json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
        AtomicBanknoteIssuanceStore store = backend;
        store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", issuanceAmount, Instant.now()));
        return backend;
    }

    private Transaction preparedDebit(UUID txId, UUID account, Amount amount,
                                      Amount before, Amount after, UUID nonce) {
        return new Transaction(txId, account, null, "dollar", amount,
                TransactionType.WITHDRAW, before, after, Instant.now(),
                BanknoteIssuanceReasons.issueReason(nonce));
    }

    private Amount balanceOf(JsonPersistenceBackend backend, UUID account) {
        return backend.load(account).orElseThrow().balanceOf("dollar");
    }

    @Test
    void forgedReasonBindingIsRejectedWithNoMoneyMovement(@TempDir Path dir) throws Exception {
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        JsonPersistenceBackend backend = seededBackend(dir, account, nonce, amount);
        try {
            Amount before = balanceOf(backend, account);
            Transaction forged = preparedDebit(UUID.randomUUID(), account, amount,
                    before, before.subtract(amount), UUID.randomUUID());
            IssuanceDebitResult result =
                    ((AtomicBanknoteIssuanceStore) backend).commitDebit(nonce, forged, DEBT);

            assertTrue(result.isInconsistentProof(),
                    "a debit whose reason binds another nonce must be INCONSISTENT_PROOF, got " + result);
            assertEquals(0, before.compareTo(balanceOf(backend, account)),
                    "a forged proof must never move money");
        } finally {
            backend.close();
        }
    }

    @Test
    void amountMismatchIsRejectedWithNoMoneyMovement(@TempDir Path dir) throws Exception {
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Amount issuanceAmount = Amount.of(100L, 2);
        JsonPersistenceBackend backend = seededBackend(dir, account, nonce, issuanceAmount);
        try {
            Amount before = balanceOf(backend, account);
            Amount wrong = Amount.of(200L, 2);
            Transaction mismatched = preparedDebit(UUID.randomUUID(), account, wrong,
                    before, before.subtract(wrong), nonce);
            IssuanceDebitResult result =
                    ((AtomicBanknoteIssuanceStore) backend).commitDebit(nonce, mismatched, DEBT);

            assertTrue(result.isInconsistentProof(),
                    "amount mismatch against the immutable issuance payload must be INCONSISTENT_PROOF,"
                            + " got " + result);
            assertEquals(0, before.compareTo(balanceOf(backend, account)),
                    "an amount-mismatched proof must never move money");
        } finally {
            backend.close();
        }
    }

    @Test
    void reusedDebitIdAcrossNoncesIsRejected(@TempDir Path dir) throws Exception {
        UUID account = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        Path dataFile = dir.resolve("reuse.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            AtomicBanknoteIssuanceStore store = backend;
            store.prepare(BanknoteIssuance.prepared(first, account, "dollar", amount, Instant.now()));
            store.prepare(BanknoteIssuance.prepared(second, account, "dollar", amount, Instant.now()));
            Amount before = balanceOf(backend, account);
            UUID debitId = UUID.randomUUID();
            Transaction firstTx = preparedDebit(debitId, account, amount,
                    before, before.subtract(amount), first);
            IssuanceDebitResult firstResult = store.commitDebit(first, firstTx, DEBT);
            assertTrue(firstResult.isCommitted(),
                    "the first binding of a debit id must commit, got " + firstResult);
            Amount afterFirst = before.subtract(amount);
            assertEquals(0, afterFirst.compareTo(firstResult.balanceAfter()),
                    "the first commit must charge exactly once");
            assertEquals(0, afterFirst.compareTo(balanceOf(backend, account)),
                    "durable balance must reflect exactly one charge after the first commit");

            Transaction reused = preparedDebit(debitId, account, amount,
                    before, before.subtract(amount), second);
            IssuanceRefundResult replayCheck = null;
            IssuanceDebitResult secondResult = store.commitDebit(second, reused, DEBT);
            assertTrue(secondResult.isInconsistentProof(),
                    "one debit id backing two nonces must be INCONSISTENT_PROOF, got " + secondResult);
            assertEquals(0, afterFirst.compareTo(balanceOf(backend, account)),
                    "a reused proof must never move money twice");
        } finally {
            backend.close();
        }
    }

    @Test
    void wrongCurrencyIsRejectedWithNoMoneyMovement(@TempDir Path dir) throws Exception {
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        JsonPersistenceBackend backend = seededBackend(dir, account, nonce, amount);
        try {
            Amount before = balanceOf(backend, account);
            Transaction wrongCurrency = new Transaction(UUID.randomUUID(), account, null,
                    "euro", amount, TransactionType.WITHDRAW, before, before, Instant.now(),
                    BanknoteIssuanceReasons.issueReason(nonce));
            IssuanceDebitResult result =
                    ((AtomicBanknoteIssuanceStore) backend).commitDebit(nonce, wrongCurrency, DEBT);

            assertTrue(result.isInconsistentProof(),
                    "currency mismatch must be INCONSISTENT_PROOF, got " + result);
            assertEquals(0, before.compareTo(balanceOf(backend, account)),
                    "a currency-mismatched proof must never move money");
        } finally {
            backend.close();
        }
    }

    @Test
    void wrongAccountIsRejectedWithNoMoneyMovement(@TempDir Path dir) throws Exception {
        UUID account = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        JsonPersistenceBackend backend = seededBackend(dir, account, nonce, amount);
        try {
            backend.create(other, "bob", Map.of("dollar", Amount.of(10000L, 2)));
            Amount before = balanceOf(backend, account);
            Transaction wrongAccount = preparedDebit(UUID.randomUUID(), other, amount,
                    Amount.of(10000L, 2), Amount.of(9900L, 2), nonce);
            IssuanceDebitResult result =
                    ((AtomicBanknoteIssuanceStore) backend).commitDebit(nonce, wrongAccount, DEBT);

            assertTrue(result.isInconsistentProof(),
                    "account mismatch must be INCONSISTENT_PROOF, got " + result);
            assertEquals(0, before.compareTo(balanceOf(backend, account)),
                    "an account-mismatched proof must never move the victim balance");
        } finally {
            backend.close();
        }
    }

    @Test
    void wrongTypeIsRejectedWithNoMoneyMovement(@TempDir Path dir) throws Exception {
        UUID account = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        Amount amount = Amount.of(100L, 2);
        JsonPersistenceBackend backend = seededBackend(dir, account, nonce, amount);
        try {
            Amount before = balanceOf(backend, account);
            Transaction wrongType = new Transaction(UUID.randomUUID(), account, null,
                    "dollar", amount, TransactionType.DEPOSIT, before,
                    before.add(amount), Instant.now(),
                    BanknoteIssuanceReasons.issueReason(nonce));
            IssuanceDebitResult result =
                    ((AtomicBanknoteIssuanceStore) backend).commitDebit(nonce, wrongType, DEBT);

            assertTrue(result.isInconsistentProof(),
                    "a DEPOSIT masquerading as a debit must be INCONSISTENT_PROOF, got " + result);
            assertEquals(0, before.compareTo(balanceOf(backend, account)),
                    "a type-mismatched proof must never move money");
        } finally {
            backend.close();
        }
    }
}
