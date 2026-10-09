package com.smile.aceeconomy.infrastructure.integration.placeholder;

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
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;

import org.bukkit.OfflinePlayer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Balance placeholders must never perform storage I/O on the calling thread: a cache miss
 * replies with the fail-closed value immediately (instead of blocking on a load), and a
 * primed entry is served without touching storage.
 */
class PlaceholderResolverMissTest {

    /** Storage double that counts reads; rows can be seeded without priming the service cache. */
    static final class SeededAccounts implements AccountRepository {
        final Map<UUID, Account> store = new ConcurrentHashMap<>();
        final AtomicInteger loads = new AtomicInteger();

        void seed(Account account) {
            store.put(account.owner(), account);
        }

        @Override
        public boolean exists(UUID uuid) {
            return store.containsKey(uuid);
        }

        @Override
        public Optional<Account> load(UUID uuid) {
            loads.incrementAndGet();
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
            Account account = Account.create(uuid, ownerName, initialBalances);
            store.put(uuid, account);
            return account;
        }

        @Override
        public List<Account> listAll() {
            return List.copyOf(store.values());
        }
    }

    static final class Wiring {
        final CurrencyRegistry currencies = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
        final SeededAccounts accounts = new SeededAccounts();
        final EconomyService service = new EconomyService(currencies, DebtPolicy.disabled(),
                Amount.zero(2), accounts, new RecordingAuditSink(), new FixedClock(),
                new InMemoryTransactionEventPublisher());
        final EconomyApi api = new EconomyApiImpl(service,
                new InMemoryTransactionEventPublisher());
        // Production wiring shares one publisher; the resolver only needs the api here.
        final PlaceholderResolver resolver = new PlaceholderResolver(api, currencies);

        OfflinePlayer player(UUID uuid) {
            OfflinePlayer p = mock(OfflinePlayer.class);
            when(p.getUniqueId()).thenReturn(uuid);
            when(p.getName()).thenReturn("P");
            return p;
        }

        /** Storage row that the service cache has never seen: every resolve starts from a miss. */
        UUID unprimedAccountWith(double balance) {
            UUID uuid = UUID.randomUUID();
            Map<String, Amount> initial = Map.of(
                    currencies.defaultCurrencyId(), currencies.getDefault().amountOf(balance));
            accounts.seed(Account.create(uuid, "owner", initial));
            return uuid;
        }
    }

    @Test
    void missRepliesNullWithZeroCallerThreadReads() {
        Wiring w = new Wiring();
        UUID uuid = w.unprimedAccountWith(100);

        w.accounts.loads.set(0);
        assertNull(w.resolver.resolve(w.player(uuid), "balance"),
                "a cache miss must reply with the fail-closed value instead of blocking on storage");
        assertEquals(0, w.accounts.loads.get(),
                "the calling thread must perform zero storage reads for a balance placeholder");
    }

    @Test
    void formattedMissRepliesNullWithZeroCallerThreadReads() {
        Wiring w = new Wiring();
        UUID uuid = w.unprimedAccountWith(100);

        w.accounts.loads.set(0);
        assertNull(w.resolver.resolve(w.player(uuid), "balance_formatted"));
        assertEquals(0, w.accounts.loads.get());
    }

    @Test
    void offlineInvalidationMissesInsteadOfReloadingSynchronously() {
        Wiring w = new Wiring();
        UUID uuid = UUID.randomUUID();
        w.api.createAccount(uuid, "owner");
        w.api.deposit(uuid, w.currencies.defaultCurrencyId(),
                w.currencies.getDefault().amountOf(75));
        assertEquals("75.00", w.resolver.resolve(w.player(uuid), "balance"));

        // Simulates the quit listener: the departed player's entry is dropped.
        w.service.invalidateBalance(uuid);

        w.accounts.loads.set(0);
        assertNull(w.resolver.resolve(w.player(uuid), "balance"),
                "an offline entry must miss (fail closed) rather than reload on the calling thread");
        assertEquals(0, w.accounts.loads.get());
    }

    @Test
    void reloadInvalidationMissesInsteadOfReloadingSynchronously() {
        Wiring w = new Wiring();
        UUID uuid = UUID.randomUUID();
        w.api.createAccount(uuid, "owner");
        w.api.deposit(uuid, w.currencies.defaultCurrencyId(),
                w.currencies.getDefault().amountOf(75));

        // Simulates the admin reload hook.
        w.service.invalidateAllBalances();

        w.accounts.loads.set(0);
        assertNull(w.resolver.resolve(w.player(uuid), "balance"),
                "a post-reload miss must fail closed rather than reload on the calling thread");
        assertEquals(0, w.accounts.loads.get());
    }

    @Test
    void primedHitServesWithoutStorageReads() {
        Wiring w = new Wiring();
        UUID uuid = UUID.randomUUID();
        w.api.createAccount(uuid, "owner");
        w.api.deposit(uuid, w.currencies.defaultCurrencyId(),
                w.currencies.getDefault().amountOf(75));

        w.accounts.loads.set(0);
        assertEquals("75.00", w.resolver.resolve(w.player(uuid), "balance"));
        assertEquals("$75.00", w.resolver.resolve(w.player(uuid), "balance_formatted"));
        assertEquals(0, w.accounts.loads.get(),
                "a primed entry must be served from the cache without storage I/O");
    }
}
