package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.operations.RollbackError;
import com.smile.aceeconomy.operations.RollbackResult;
import com.smile.aceeconomy.operations.RollbackService;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;
import com.smile.aceeconomy.ports.persistence.AtomicReversalStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rollback debt-policy follow test (RED first): a rollback whose reversal would drive a
 * balance negative must follow {@code economy.allow-negative-balance} exactly like an
 * ordinary transaction — rejected with a clear reply when disabled, allowed when enabled.
 */
final class RollbackDebtPolicyTest {

    private static final Instant T = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final String CUR = "coin";
    private static final Currency COIN = Currency.define("coin", "Coin", "C", 2, true);

    /** Blind write-through accounts: saves overwrite without optimistic checks. */
    static final class BlindAccounts implements AccountRepository {
        final Map<UUID, Account> store = new ConcurrentHashMap<>();

        @Override
        public boolean exists(UUID uuid) {
            return store.containsKey(uuid);
        }

        @Override
        public Optional<Account> load(UUID uuid) {
            return Optional.ofNullable(store.get(uuid));
        }

        @Override
        public void save(Account account) {
            store.put(account.owner(), account);
        }

        @Override
        public void save(Account expected, Account updated) {
            store.put(updated.owner(), updated);
        }

        @Override
        public Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances) {
            Account a = Account.create(uuid, ownerName, initialBalances);
            store.put(uuid, a);
            return a;
        }

        @Override
        public List<Account> listAll() {
            return List.copyOf(store.values());
        }
    }

    private Transaction depositRecord(UUID id, UUID account, Amount amount, Amount before, Amount after) {
        return new Transaction(id, account, null, CUR, amount, TransactionType.DEPOSIT,
                before, after, T, "deposit");
    }

    private RollbackService serviceWith(BlindAccounts accounts, InMemoryTransactionRepository txRepo,
                                        AtomicReversalStore store, DebtPolicy debt) {
        return new RollbackService(txRepo,
                new StorageReversalExecutor(accounts, store, new FixedClock(), debt));
    }

    private AtomicReversalStore blindStore(BlindAccounts accounts, InMemoryTransactionRepository txRepo) {
        return (updated, records, markers) -> {
            for (Account a : updated) {
                accounts.save(a);
            }
            txRepo.appendBatch(records);
            for (UUID m : markers) {
                txRepo.markReverted(m);
            }
        };
    }

    @Test
    void disabledPolicyRejectsDepositRollbackThatWouldGoNegative() {
        BlindAccounts accounts = new BlindAccounts();
        UUID owner = UUID.randomUUID();
        accounts.create(owner, "alice", Map.of(CUR, Amount.of(2000L, 2)));
        InMemoryTransactionRepository txRepo = new InMemoryTransactionRepository();
        UUID originalId = UUID.randomUUID();
        txRepo.append(depositRecord(originalId, owner, Amount.of(10000L, 2),
                Amount.of(0L, 2), Amount.of(10000L, 2)));

        RollbackResult result = serviceWith(accounts, txRepo, blindStore(accounts, txRepo),
                DebtPolicy.disabled())
                .rollback(originalId);

        assertFalse(result.isSuccess(), "rollback to -80.00 must be rejected when debt is disabled");
        assertEquals(RollbackError.EXECUTION_FAILED, result.error());
        assertTrue(result.message() != null && result.message().contains("insufficient funds"),
                "rejection must carry a clear reply, got: " + result.message());
        assertEquals(0, Amount.of(2000L, 2)
                .compareTo(accounts.load(owner).orElseThrow().balanceOf(CUR)),
                "balance must be unchanged after the rejected rollback");
        assertFalse(txRepo.isReverted(originalId));
        assertEquals(1, txRepo.loadAll().size(), "no reversal record may be appended");
    }

    @Test
    void enabledPolicyAllowsSameRollbackToNegativeLikeOrdinaryWithdraw() {
        BlindAccounts accounts = new BlindAccounts();
        UUID owner = UUID.randomUUID();
        accounts.create(owner, "alice", Map.of(CUR, Amount.of(2000L, 2)));
        InMemoryTransactionRepository txRepo = new InMemoryTransactionRepository();
        UUID originalId = UUID.randomUUID();
        txRepo.append(depositRecord(originalId, owner, Amount.of(10000L, 2),
                Amount.of(0L, 2), Amount.of(10000L, 2)));

        // Ordinary-transaction reference with the same policy: withdrawing 100.00 from
        // 20.00 under DebtPolicy.enabled(500) lands on the same -80.00.
        DebtPolicy debt = DebtPolicy.enabled(Amount.of(50000L, 2));
        CurrencyRegistry currencies = CurrencyRegistry.of(List.of(COIN));
        BlindAccounts generalAccounts = new BlindAccounts();
        UUID generalOwner = UUID.randomUUID();
        EconomyService general = new EconomyService(currencies, debt, Amount.zero(2),
                generalAccounts, new RecordingAuditSink(), new FixedClock(),
                new InMemoryTransactionEventPublisher());
        general.createAccount(generalOwner, "bob");
        assertTrue(general.deposit(generalOwner, CUR, Amount.of(2000L, 2)).isSuccess());
        var withdraw = general.withdraw(generalOwner, CUR, Amount.of(10000L, 2));
        assertTrue(withdraw.isSuccess(), "ordinary withdraw must allow -80.00 when debt is enabled");

        RollbackResult result = serviceWith(accounts, txRepo, blindStore(accounts, txRepo), debt)
                .rollback(originalId);

        assertTrue(result.isSuccess(), "rollback to -80.00 must be allowed when debt is enabled");
        assertEquals(0, Amount.of(-8000L, 2)
                .compareTo(accounts.load(owner).orElseThrow().balanceOf(CUR)));
        assertEquals(0, withdraw.value()
                .compareTo(accounts.load(owner).orElseThrow().balanceOf(CUR)),
                "rollback result must match the ordinary-transaction result");
        assertTrue(txRepo.isReverted(originalId));
    }

    @Test
    void enabledPolicyBeyondLimitRejectsRollbackLikeOrdinaryWithdraw() {
        BlindAccounts accounts = new BlindAccounts();
        UUID owner = UUID.randomUUID();
        accounts.create(owner, "alice", Map.of(CUR, Amount.of(2000L, 2)));
        InMemoryTransactionRepository txRepo = new InMemoryTransactionRepository();
        UUID originalId = UUID.randomUUID();
        txRepo.append(depositRecord(originalId, owner, Amount.of(10000L, 2),
                Amount.of(0L, 2), Amount.of(10000L, 2)));

        // The same -80.00 outcome exceeds a 50.00 debt limit, so both the ordinary
        // withdraw and the rollback must refuse it.
        DebtPolicy debt = DebtPolicy.enabled(Amount.of(5000L, 2));
        CurrencyRegistry currencies = CurrencyRegistry.of(List.of(COIN));
        BlindAccounts generalAccounts = new BlindAccounts();
        UUID generalOwner = UUID.randomUUID();
        EconomyService general = new EconomyService(currencies, debt, Amount.zero(2),
                generalAccounts, new RecordingAuditSink(), new FixedClock(),
                new InMemoryTransactionEventPublisher());
        general.createAccount(generalOwner, "bob");
        assertTrue(general.deposit(generalOwner, CUR, Amount.of(2000L, 2)).isSuccess());
        assertTrue(general.withdraw(generalOwner, CUR, Amount.of(10000L, 2)).isFailure(),
                "ordinary withdraw must refuse -80.00 beyond the 50.00 debt limit");

        RollbackResult result = serviceWith(accounts, txRepo, blindStore(accounts, txRepo), debt)
                .rollback(originalId);

        assertFalse(result.isSuccess(), "rollback beyond the debt limit must be rejected");
        assertEquals(RollbackError.EXECUTION_FAILED, result.error());
        assertTrue(result.message() != null && result.message().contains("debt limit exceeded"),
                "rejection must name the debt limit, got: " + result.message());
        assertEquals(0, Amount.of(2000L, 2)
                .compareTo(accounts.load(owner).orElseThrow().balanceOf(CUR)),
                "balance must be unchanged after the rejected rollback");
        assertFalse(txRepo.isReverted(originalId));
        assertEquals(1, txRepo.loadAll().size(), "no reversal record may be appended");
    }

    @Test
    void inMemoryExecutorRejectsNegativeRollbackWhenDisabled() {
        BlindAccounts accounts = new BlindAccounts();
        UUID owner = UUID.randomUUID();
        accounts.create(owner, "alice", Map.of(CUR, Amount.of(2000L, 2)));
        InMemoryTransactionRepository txRepo = new InMemoryTransactionRepository();
        UUID originalId = UUID.randomUUID();
        txRepo.append(depositRecord(originalId, owner, Amount.of(10000L, 2),
                Amount.of(0L, 2), Amount.of(10000L, 2)));

        RollbackService svc = new RollbackService(txRepo,
                new InMemoryReversalExecutor(accounts, txRepo, new FixedClock(), DebtPolicy.disabled()));
        RollbackResult result = svc.rollback(originalId);

        assertFalse(result.isSuccess(), "in-memory rollback to -80.00 must be rejected when disabled");
        assertEquals(RollbackError.EXECUTION_FAILED, result.error());
        assertTrue(result.message() != null && result.message().contains("insufficient funds"),
                "rejection must carry a clear reply, got: " + result.message());
        assertEquals(0, Amount.of(2000L, 2)
                .compareTo(accounts.load(owner).orElseThrow().balanceOf(CUR)));
        assertFalse(txRepo.isReverted(originalId));
        assertEquals(1, txRepo.loadAll().size(), "no reversal record may be appended");
    }
}
