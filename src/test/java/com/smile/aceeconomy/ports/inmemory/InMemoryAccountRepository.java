package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.ports.AccountRepository;

import java.util.Map;
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
        store.put(account.owner(), account);
    }

    @Override
    public Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances) {
        Account a = Account.create(uuid, ownerName, initialBalances);
        store.put(uuid, a);
        return a;
    }
}
