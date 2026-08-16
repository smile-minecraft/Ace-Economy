package com.smile.aceeconomy.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Read-only projection of an {@link Account}. */
public final class AccountSnapshot {

    private final UUID owner;
    private final String ownerName;
    private final Map<String, Amount> balances;

    AccountSnapshot(UUID owner, String ownerName, Map<String, Amount> balances) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.balances = Collections.unmodifiableMap(new HashMap<>(balances));
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public Amount balanceOf(String currencyId) {
        return balances.get(Currency.normalizeId(currencyId));
    }

    public Map<String, Amount> balances() {
        return balances;
    }
}
