package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link TransactionRepository} for tests. Tracks appended transactions and the
 * reverted-marker set. No persistence, no vendor imports.
 */
public final class InMemoryTransactionRepository implements TransactionRepository {

    private final List<Transaction> store = new ArrayList<>();
    private final Set<UUID> reverted = ConcurrentHashMap.newKeySet();
    private volatile boolean failNextAppend = false;

    public synchronized List<Transaction> all() {
        return new ArrayList<>(store);
    }

    public void setFailNextAppend(boolean fail) {
        this.failNextAppend = fail;
    }

    @Override
    public synchronized void append(Transaction transaction) throws PersistenceException {
        if (failNextAppend) {
            throw new PersistenceException("simulated append failure");
        }
        store.add(transaction);
    }

    @Override
    public synchronized void appendBatch(List<Transaction> transactions) throws PersistenceException {
        if (failNextAppend) {
            throw new PersistenceException("simulated batch failure");
        }
        store.addAll(transactions);
    }

    @Override
    public synchronized void markReverted(UUID transactionId) throws PersistenceException {
        reverted.add(transactionId);
    }

    @Override
    public synchronized boolean isReverted(UUID transactionId) throws PersistenceException {
        return reverted.contains(transactionId);
    }

    @Override
    public synchronized List<Transaction> loadByAccount(UUID accountId) throws PersistenceException {
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : store) {
            if (t.accountId().equals(accountId)) {
                out.add(t);
            }
        }
        return out;
    }

    @Override
    public synchronized List<Transaction> loadAll() throws PersistenceException {
        return new ArrayList<>(store);
    }
}
