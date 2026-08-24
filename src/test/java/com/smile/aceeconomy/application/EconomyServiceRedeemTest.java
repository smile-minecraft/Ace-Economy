package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.api.v2.TransactionListener;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for {@link EconomyService#redeemBanknote} — the durable redeem path that must
 * preserve amount/currency validation, per-account lock, pre-commit cancellation, debt policy
 * and audit semantics while committing balance/audit/nonce atomically.
 */
class EconomyServiceRedeemTest {

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 2, true),
                Currency.define("token", "活動代幣", "ⓒ", 0, false)));
    }

    private EconomyService serviceWith(JsonPersistenceBackend backend,
                                       InMemoryTransactionEventPublisher publisher) {
        DebtPolicy debt = DebtPolicy.disabled();
        return new EconomyService(currencies(), debt, Amount.zero(2), backend,
                tx -> { try { backend.append(tx); } catch (Exception e) { throw new RuntimeException(e); } },
                () -> Instant.now(), publisher);
    }

    @Test
    void redeemCommitsAtomicallyAndIsReplayProtected(@TempDir Path dir) {
        Path file = dir.resolve("redeem.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Amount.of(100L, 2)));
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        EconomyService economy = serviceWith(backend, publisher);

        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> r = economy.redeemBanknote(nonce, owner, "dollar", Amount.of(50L, 2), backend);
        assertTrue(r.isSuccess());
        assertEquals(0, Amount.of(150L, 2).compareTo(r.value()));
        assertTrue(backend.isConsumed(nonce));

        // second attempt with same nonce must be detected as replay and not double-credit
        EconomyResult<Amount> second = economy.redeemBanknote(nonce, owner, "dollar", Amount.of(50L, 2), backend);
        assertTrue(second.isFailure());
        assertEquals(EconomyError.REPLAY_DETECTED, second.error());
        assertEquals(0, Amount.of(150L, 2).compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
    }

    @Test
    void redeemWithPreCommitCancellationDoesNotConsumeNonce(@TempDir Path dir) {
        Path file = dir.resolve("redeem.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Amount.of(100L, 2)));
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        // listener that cancels every deposit
        publisher.register((TransactionListener) event -> event.cancel());
        EconomyService economy = serviceWith(backend, publisher);

        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> r = economy.redeemBanknote(nonce, owner, "dollar", Amount.of(10L, 2), backend);
        assertTrue(r.isFailure());
        assertEquals(EconomyError.TRANSACTION_CANCELLED, r.error());
        assertFalse(backend.isConsumed(nonce), "cancelled redeem must not burn the nonce");
        assertEquals(0, Amount.of(100L, 2).compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
        assertTrue(backend.loadAll().isEmpty(), "no audit record on cancellation");
    }

    @Test
    void redeemWithUnknownAccountFailsWithoutConsumingNonce(@TempDir Path dir) {
        Path file = dir.resolve("redeem.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        EconomyService economy = serviceWith(backend, publisher);

        UUID stranger = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> r = economy.redeemBanknote(nonce, stranger, "dollar", Amount.of(10L, 2), backend);
        assertTrue(r.isFailure());
        assertEquals(EconomyError.ACCOUNT_NOT_FOUND, r.error());
        assertFalse(backend.isConsumed(nonce));
    }

    @Test
    void redeemWithUnknownCurrencyFailsWithoutConsumingNonce(@TempDir Path dir) {
        Path file = dir.resolve("redeem.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of());
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        EconomyService economy = serviceWith(backend, publisher);

        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> r = economy.redeemBanknote(nonce, owner, "euro", Amount.of(10L, 2), backend);
        assertTrue(r.isFailure());
        assertEquals(EconomyError.CURRENCY_NOT_FOUND, r.error());
        assertFalse(backend.isConsumed(nonce));
    }

    @Test
    void redeemWithNonPositiveAmountFails(@TempDir Path dir) {
        Path file = dir.resolve("redeem.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of());
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        EconomyService economy = serviceWith(backend, publisher);

        UUID nonce = UUID.randomUUID();
        EconomyResult<Amount> r = economy.redeemBanknote(nonce, owner, "dollar", Amount.of(0L, 2), backend);
        assertTrue(r.isFailure());
        assertEquals(EconomyError.INVALID_AMOUNT, r.error());
        assertFalse(backend.isConsumed(nonce));
    }

    @Test
    void redeemStorageFailureMapsToAuditFailureWithoutConsumingNonce(@TempDir Path dir) {
        Path file = dir.resolve("redeem.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Amount.of(0L, 2)));
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        EconomyService economy = serviceWith(backend, publisher);

        UUID nonce = UUID.randomUUID();
        // store that always throws
        com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore failing = new com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore() {
            @Override
            public com.smile.aceeconomy.ports.persistence.RedemptionResult redeem(UUID n, UUID a, String c, Amount amt) { throw new PersistenceException("fail"); }
            @Override
            public com.smile.aceeconomy.ports.persistence.RedemptionResult redeemPrepared(UUID n, com.smile.aceeconomy.domain.Account acc, com.smile.aceeconomy.domain.Transaction tx) { throw new PersistenceException("injected storage outage"); }
        };
        EconomyResult<Amount> r = economy.redeemBanknote(nonce, owner, "dollar", Amount.of(10L, 2), failing);
        assertTrue(r.isFailure());
        assertEquals(EconomyError.AUDIT_FAILURE, r.error());
        assertFalse(backend.isConsumed(nonce), "storage failure must not consume nonce");
        assertEquals(0, Amount.of(0L, 2).compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
    }

    @Test
    void redeemConcurrentSameNonceExactlyOneCommits(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("redeem.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of("dollar", Amount.of(0L, 2)));
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        EconomyService economy = serviceWith(backend, publisher);

        UUID nonce = UUID.randomUUID();
        int threads = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<EconomyResult<Amount>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> economy.redeemBanknote(nonce, owner, "dollar", Amount.of(10L, 2), backend)));
        }
        int successes = 0;
        for (java.util.concurrent.Future<EconomyResult<Amount>> f : futures) {
            EconomyResult<Amount> r = f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (r.isSuccess()) successes++;
            else assertEquals(EconomyError.REPLAY_DETECTED, r.error());
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, successes, "exactly one concurrent redeem may commit");
        assertEquals(0, Amount.of(10L, 2).compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
    }
}
