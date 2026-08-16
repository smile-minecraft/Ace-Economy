package com.smile.aceeconomy.ports;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence seam for accounts. No vendor imports; implement in infrastructure (Task 06+). */
public interface AccountRepository {

    boolean exists(UUID uuid);

    Optional<Account> load(UUID uuid);

    void save(Account account);

    /** Create and persist a brand-new account with the given initial balances. */
    Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances);
}
