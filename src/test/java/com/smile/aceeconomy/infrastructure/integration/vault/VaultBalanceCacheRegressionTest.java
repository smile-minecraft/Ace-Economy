package com.smile.aceeconomy.infrastructure.integration.vault;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;

import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.OfflinePlayer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Synchronous Vault balance reads must not touch storage, writes stay
 * write-through, and offline / reload drop cached entries.
 */
class VaultBalanceCacheRegressionTest {

    /** Counts persistence reads so the test proves zero I/O on the sync path. */
    static final class CountingAccounts implements AccountRepository {
        final InMemoryAccountRepository delegate = new InMemoryAccountRepository();
        final AtomicInteger loads = new AtomicInteger();

        @Override
        public boolean exists(UUID uuid) {
            return delegate.exists(uuid);
        }

        @Override
        public Optional<Account> load(UUID uuid) {
            loads.incrementAndGet();
            return delegate.load(uuid);
        }

        @Override
        public void save(Account account) {
            delegate.save(account);
        }

        @Override
        public void save(Account expected, Account updated) {
            delegate.save(expected, updated);
        }

        @Override
        public Account create(UUID uuid, String ownerName, Map<String, Amount> initialBalances) {
            return delegate.create(uuid, ownerName, initialBalances);
        }

        @Override
        public List<Account> listAll() {
            return delegate.listAll();
        }
    }

    static final class Wiring {
        final CurrencyRegistry currencies = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
        final CountingAccounts accounts = new CountingAccounts();
        final RecordingAuditSink audit = new RecordingAuditSink();
        final FixedClock clock = new FixedClock();
        final InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        final EconomyService service = new EconomyService(currencies, DebtPolicy.disabled(),
                Amount.zero(2), accounts, audit, clock, publisher);
        final EconomyApi api = new EconomyApiImpl(service, publisher);
        final VaultEconomyProvider provider = new VaultEconomyProvider(api, currencies);

        UUID accountWith(double balance) {
            UUID uuid = UUID.randomUUID();
            api.createAccount(uuid, "acc");
            if (balance > 0) {
                api.deposit(uuid, currencies.defaultCurrencyId(),
                        currencies.getDefault().amountOf(balance));
            }
            return uuid;
        }

        OfflinePlayer player(UUID uuid) {
            OfflinePlayer p = mock(OfflinePlayer.class);
            when(p.getUniqueId()).thenReturn(uuid);
            when(p.getName()).thenReturn("P");
            return p;
        }
    }

    @Test
    void syncBalanceReadPerformsZeroStorageReadsOnCacheHit() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(75);
        w.accounts.loads.set(0);

        assertEquals(75.0, w.provider.getBalance(w.player(uuid)), 0.0001);
        assertEquals(0, w.accounts.loads.get(),
                "sync getBalance on a cached account must not hit storage");
    }

    @Test
    void depositThroughVaultIsVisibleOnNextSyncRead() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(75);
        OfflinePlayer p = w.player(uuid);

        EconomyResponse r = w.provider.depositPlayer(p, 25.0);
        assertTrue(r.transactionSuccess(), () -> "expected success, got: " + r.errorMessage);

        w.accounts.loads.set(0);
        assertEquals(100.0, w.provider.getBalance(p), 0.0001);
        assertEquals(0, w.accounts.loads.get(),
                "post-write sync read must be served from the refreshed cache");
    }

    @Test
    void withdrawThroughVaultIsVisibleOnNextSyncRead() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(100);
        OfflinePlayer p = w.player(uuid);

        EconomyResponse r = w.provider.withdrawPlayer(p, 40.0);
        assertTrue(r.transactionSuccess(), () -> "expected success, got: " + r.errorMessage);
        assertEquals(60.0, w.provider.getBalance(p), 0.0001);
    }

    @Test
    void transferIsVisibleOnNextSyncReadForBothParties() {
        Wiring w = new Wiring();
        UUID from = w.accountWith(100);
        UUID to = w.accountWith(10);
        String cur = w.currencies.defaultCurrencyId();

        assertTrue(w.api.transfer(from, to, cur, w.currencies.getDefault().amountOf(30)).isSuccess());

        w.accounts.loads.set(0);
        assertEquals(70.0, w.provider.getBalance(w.player(from)), 0.0001);
        assertEquals(40.0, w.provider.getBalance(w.player(to)), 0.0001);
        assertEquals(0, w.accounts.loads.get(),
                "post-transfer sync reads must not hit storage");
    }

    @Test
    void failedWithdrawKeepsPreviousCachedBalance() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(10);
        OfflinePlayer p = w.player(uuid);

        EconomyResponse r = w.provider.withdrawPlayer(p, 40.0);
        assertFalse(r.transactionSuccess(), "overdraft must fail under a disabled debt policy");
        assertEquals(10.0, w.provider.getBalance(p), 0.0001,
                "a failed write must not poison the cached balance");
    }

    @Test
    void offlineInvalidationDropsCachedBalanceWithoutStorageReads() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(75);
        OfflinePlayer p = w.player(uuid);
        assertEquals(75.0, w.provider.getBalance(p), 0.0001);

        // Simulates the quit listener: the departed player's entry is dropped.
        w.service.invalidateBalance(uuid);

        w.accounts.loads.set(0);
        assertEquals(0.0, w.provider.getBalance(p), 0.0001,
                "offline entries must miss and fall back to the safe default");
        assertEquals(0, w.accounts.loads.get(),
                "a post-offline miss must not block on storage either");
    }

    @Test
    void reloadInvalidationDropsAllCachedBalances() {
        Wiring w = new Wiring();
        UUID uuid = w.accountWith(75);
        assertEquals(75.0, w.provider.getBalance(w.player(uuid)), 0.0001);

        // Simulates the admin reload hook.
        w.service.invalidateAllBalances();

        assertEquals(0.0, w.provider.getBalance(w.player(uuid)), 0.0001,
                "reload must drop cached balances back to the safe default");
    }
}
