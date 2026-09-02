package com.smile.aceeconomy.ports;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence seam for accounts. No vendor imports; implement in infrastructure. */
public interface AccountRepository {

    boolean exists(UUID uuid);

    Optional<Account> load(UUID uuid);

    void save(Account account);

    /**
     * Persist {@code updated} only when {@code expected} still describes the live account.
     * Implementations must perform the comparison and write as one storage operation; a stale
     * expected snapshot is a conflict, never an implicit merge or overwrite.
     */
    void save(Account expected, Account updated);

    /** Create and persist a brand-new account with the given initial balances. */
    Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances);

    /** Snapshot of every known account, for read-only scans such as the leaderboard. */
    List<Account> listAll();
}
