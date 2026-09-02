package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.application.TransferResult;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicTransferStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** In-memory {@link AccountRepository} for tests. No persistence, no vendor imports. */
public final class InMemoryAccountRepository implements AccountRepository, AtomicTransferStore {

    private final Map<UUID, Account> store = new ConcurrentHashMap<>();
    private final List<Transaction> transactions = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean failNextTransfer = false;
    private volatile boolean failOnSecondRecord = false;

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
        store.compute(account.owner(), (owner, current) -> {
            if (current == null || sameAccount(current, account)) {
                return account;
            }
            throw new PersistenceException("Optimistic account conflict for " + account.owner());
        });
    }

    @Override
    public void save(Account expected, Account updated) {
        if (expected == null || updated == null || !expected.owner().equals(updated.owner())) {
            throw new PersistenceException("Invalid expected account snapshot");
        }
        store.compute(updated.owner(), (owner, current) -> {
            if (current == null || !sameAccount(current, expected)) {
                throw new PersistenceException("Optimistic account conflict for " + updated.owner());
            }
            return updated;
        });
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

    public void setFailNextTransfer(boolean fail) { this.failNextTransfer = fail; }
    public void setFailOnSecondRecord(boolean fail) { this.failOnSecondRecord = fail; }
    public List<Transaction> recordedTransactions() { return List.copyOf(transactions); }

    @Override
    public TransferResult transfer(UUID from, UUID to, String currencyId, Amount amount, DebtPolicy debtPolicy)
            throws PersistenceException {
        String cid = Currency.normalizeId(currencyId);
        lock.lock();
        try {
            if (failNextTransfer) {
                failNextTransfer = false;
                throw new PersistenceException("injected transfer failure");
            }
            Account fromAcc = store.get(from);
            Account toAcc = store.get(to);
            if (fromAcc == null || toAcc == null) {
                throw new PersistenceException("account not found for transfer");
            }
            Amount fromBefore = fromAcc.balanceOf(cid);
            if (fromBefore == null) fromBefore = Amount.zero(amount.scale());
            Amount toBefore = toAcc.balanceOf(cid);
            if (toBefore == null) toBefore = Amount.zero(amount.scale());
            Amount fromAfter = fromBefore.subtract(amount);
            if (debtPolicy != null && !debtPolicy.allows(fromAfter)) {
                throw new AtomicTransferStore.DebtLimitExceededException("debt limit exceeded");
            }
            Amount toAfter = toBefore.add(amount);
            Account updatedFrom = fromAcc.withdraw(cid, amount);
            Account updatedTo = toAcc.deposit(cid, amount);
            UUID outId = UUID.randomUUID();
            UUID inId = UUID.randomUUID();
            Instant now = Instant.now();
            Transaction outTx = new Transaction(outId, from, to, cid, amount,
                    TransactionType.TRANSFER_OUT, fromBefore, fromAfter, now, "transfer-out");
            Transaction inTx = new Transaction(inId, to, from, cid, amount,
                    TransactionType.TRANSFER_IN, toBefore, toAfter, now, "transfer-in");
            // snapshot for atomic rollback when fault is injected after first mutation
            Map<UUID, Account> snapshotStore = Map.copyOf(store);
            List<Transaction> snapshotTx = List.copyOf(transactions);
            try {
                store.put(from, updatedFrom);
                // fault after first account has been mutated (proves second-leg failure is rolled back)
                if (failOnSecondRecord) {
                    failOnSecondRecord = false;
                    throw new PersistenceException("injected failure on second account");
                }
                store.put(to, updatedTo);
                transactions.add(outTx);
                // alternative fault after first audit record would also exercise the same rollback;
                // kept as same flag for simplicity - the mutation point is already after first account
                transactions.add(inTx);
                return new TransferResult(from, to, fromAfter, toAfter, outId, inId);
            } catch (PersistenceException e) {
                // rollback to snapshot to preserve all-or-none
                store.clear();
                store.putAll(snapshotStore);
                transactions.clear();
                transactions.addAll(snapshotTx);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private static boolean sameAccount(Account left, Account right) {
        if (!left.owner().equals(right.owner()) || !left.ownerName().equals(right.ownerName())
                || !left.balances().keySet().equals(right.balances().keySet())) {
            return false;
        }
        for (String currency : left.balances().keySet()) {
            if (left.balances().get(currency).compareTo(right.balances().get(currency)) != 0) {
                return false;
            }
        }
        return true;
    }
}
