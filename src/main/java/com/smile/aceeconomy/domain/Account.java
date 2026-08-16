package com.smile.aceeconomy.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable account entity. Balances are kept per normalized currency id as immutable
 * {@link Amount} values. All mutating operations return a new {@code Account}; nothing is
 * mutated in place, which keeps the domain free of hidden shared state.
 */
public final class Account {

    private final UUID owner;
    private final String ownerName;
    private final Map<String, Amount> balances; // normalized currencyId -> Amount

    private Account(UUID owner, String ownerName, Map<String, Amount> balances) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.balances = Collections.unmodifiableMap(new HashMap<>(balances));
    }

    public static Account create(UUID owner, String ownerName, Map<String, Amount> initialBalances) {
        return new Account(owner, ownerName, initialBalances);
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    /** Balance for the currency, or {@code null} when the account has no entry yet. */
    public Amount balanceOf(String currencyId) {
        return balances.get(Currency.normalizeId(currencyId));
    }

    public boolean hasCurrency(String currencyId) {
        return balances.containsKey(Currency.normalizeId(currencyId));
    }

    public Map<String, Amount> balances() {
        return balances;
    }

    public Account deposit(String currencyId, Amount amount) {
        String cid = Currency.normalizeId(currencyId);
        Amount current = balances.getOrDefault(cid, Amount.zero(amount.scale()));
        Map<String, Amount> next = new HashMap<>(balances);
        next.put(cid, current.add(amount));
        return new Account(owner, ownerName, next);
    }

    public Account withdraw(String currencyId, Amount amount) {
        String cid = Currency.normalizeId(currencyId);
        Amount current = balances.getOrDefault(cid, Amount.zero(amount.scale()));
        Map<String, Amount> next = new HashMap<>(balances);
        next.put(cid, current.subtract(amount));
        return new Account(owner, ownerName, next);
    }

    public Account setBalance(String currencyId, Amount amount) {
        String cid = Currency.normalizeId(currencyId);
        Map<String, Amount> next = new HashMap<>(balances);
        next.put(cid, amount);
        return new Account(owner, ownerName, next);
    }

    public AccountSnapshot snapshot() {
        return new AccountSnapshot(owner, ownerName, balances);
    }
}
