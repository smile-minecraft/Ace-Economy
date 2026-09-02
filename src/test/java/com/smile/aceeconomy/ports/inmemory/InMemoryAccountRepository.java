package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link AccountRepository} for tests. No persistence, no vendor imports. */
public final class InMemoryAccountRepository implements AccountRepository {

    private final Map<UUID, Account> store = new ConcurrentHashMap<>();

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
