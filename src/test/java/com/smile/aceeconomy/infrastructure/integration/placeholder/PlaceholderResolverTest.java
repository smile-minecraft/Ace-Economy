package com.smile.aceeconomy.infrastructure.integration.placeholder;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;

import org.bukkit.OfflinePlayer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaceholderResolverTest {

    private final EconomyTestHarness harness = new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));
    private final EconomyApi api = new EconomyApiImpl(harness.service(), harness.publisher());
    private final PlaceholderResolver resolver = new PlaceholderResolver(api, harness.currencies());

    private OfflinePlayer player(UUID uuid, String name) {
        OfflinePlayer p = mock(OfflinePlayer.class);
        when(p.getUniqueId()).thenReturn(uuid);
        when(p.getName()).thenReturn(name);
        return p;
    }

    private UUID accountWith(double balance) {
        UUID uuid = UUID.randomUUID();
        api.createAccount(uuid, "acc");
        if (balance > 0) {
            api.deposit(uuid, harness.currencies().defaultCurrencyId(), harness.currencies().getDefault().amountOf(balance));
        }
        return uuid;
    }

    @Test
    void validDefaultBalance() {
        UUID uuid = accountWith(100);
        assertEquals("100.00", resolver.resolve(player(uuid, "A"), "balance"));
    }

    @Test
    void validDefaultBalanceFormatted() {
        UUID uuid = accountWith(100);
        assertEquals("$100.00", resolver.resolve(player(uuid, "A"), "balance_formatted"));
    }

    @Test
    void validNamedCurrencyBalance() {
        UUID uuid = accountWith(0);
        api.deposit(uuid, "token", harness.currencies().get("token").amountOf(7));
        assertEquals("7", resolver.resolve(player(uuid, "A"), "balance_token"));
    }

    @Test
    void validNamedCurrencyFormatted() {
        UUID uuid = accountWith(0);
        api.deposit(uuid, "token", harness.currencies().get("token").amountOf(7));
        assertEquals("T7", resolver.resolve(player(uuid, "A"), "balance_token_formatted"));
    }

    @Test
    void unknownPlaceholderReturnsNull() {
        UUID uuid = accountWith(0);
        assertNull(resolver.resolve(player(uuid, "A"), "rank"));
    }

    @Test
    void malformedCurrencyIdReturnsNull() {
        UUID uuid = accountWith(0);
        assertNull(resolver.resolve(player(uuid, "A"), "balance_Invalid-Currency!"));
        assertNull(resolver.resolve(player(uuid, "A"), "balance_"));
    }

    @Test
    void unknownCurrencyReturnsNull() {
        UUID uuid = accountWith(0);
        assertNull(resolver.resolve(player(uuid, "A"), "balance_gem"));
    }

    @Test
    void unavailableAccountReturnsNull() {
        UUID uuid = UUID.randomUUID(); // not created
        assertNull(resolver.resolve(player(uuid, "A"), "balance"));
    }

    @Test
    void nullPlayerOrParamsReturnsNull() {
        assertNull(resolver.resolve(null, "balance"));
        UUID uuid = accountWith(0);
        assertNull(resolver.resolve(player(uuid, "A"), null));
    }

    @Test
    void namedPlaceholderWorksForArbitraryConfiguredCurrency() {
        // A registry shaped like CurrencyConfigParser output (config-defined third currency)
        // must drive the named placeholder path without any hardcoded dollar/token assumption.
        com.smile.aceeconomy.domain.CurrencyRegistry currencies =
                com.smile.aceeconomy.domain.CurrencyRegistry.of(java.util.List.of(
                        com.smile.aceeconomy.domain.Currency.define("dollar", "Dollar", "$", 2, true),
                        com.smile.aceeconomy.domain.Currency.define("gem", "Gem", "*", 1, false)));
        com.smile.aceeconomy.application.EconomyService service =
                new com.smile.aceeconomy.application.EconomyService(currencies, DebtPolicy.disabled(),
                        com.smile.aceeconomy.domain.Amount.zero(2),
                        new com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository(),
                        new com.smile.aceeconomy.ports.inmemory.RecordingAuditSink(),
                        new com.smile.aceeconomy.ports.inmemory.FixedClock(),
                        new com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher());
        EconomyApi gemApi = new EconomyApiImpl(service, new com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher());
        PlaceholderResolver gemResolver = new PlaceholderResolver(gemApi, currencies);

        UUID uuid = UUID.randomUUID();
        gemApi.createAccount(uuid, "acc");
        gemApi.deposit(uuid, "gem", currencies.get("gem").amountOf(new java.math.BigDecimal("1.5")));

        assertEquals("1.5", gemResolver.resolve(player(uuid, "A"), "balance_gem"));
        assertEquals("*1.5", gemResolver.resolve(player(uuid, "A"), "balance_gem_formatted"));
    }

    // --- Rank / top / currency placeholders (snapshot-backed, zero I/O) ---

    private PlaceholderResolver snapshotResolver(
            com.smile.aceeconomy.infrastructure.operations.LeaderboardCache cache,
            java.time.Duration ttl,
            com.smile.aceeconomy.ports.Clock clock,
            EconomyApi boundApi) {
        return new PlaceholderResolver(boundApi, harness.currencies(), cache, ttl, clock);
    }

    private com.smile.aceeconomy.infrastructure.operations.LeaderboardCache seededCache(
            String currencyId, java.time.Instant now, UUID first, UUID second) {
        com.smile.aceeconomy.infrastructure.operations.LeaderboardCache cache =
                new com.smile.aceeconomy.infrastructure.operations.LeaderboardCache();
        cache.put(currencyId, java.util.List.of(
                new com.smile.aceeconomy.operations.LeaderboardEntry(1, first, "Alice",
                        harness.currencies().get(currencyId).amountOf(new java.math.BigDecimal("300"))),
                new com.smile.aceeconomy.operations.LeaderboardEntry(2, second, "Bob",
                        harness.currencies().get(currencyId).amountOf(new java.math.BigDecimal("100")))),
                now);
        return cache;
    }

    @Test
    void rankResolvesFromSnapshot() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.EPOCH.plusSeconds(60);
        PlaceholderResolver r = snapshotResolver(
                seededCache("dollar", now, first, second),
                java.time.Duration.ofMinutes(5), () -> now, api);
        assertEquals("1", r.resolve(player(first, "Alice"), "rank"));
        assertEquals("2", r.resolve(player(second, "Bob"), "rank"));
    }

    @Test
    void rankNamedCurrencyResolvesFromSnapshot() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.EPOCH.plusSeconds(60);
        com.smile.aceeconomy.infrastructure.operations.LeaderboardCache cache =
                new com.smile.aceeconomy.infrastructure.operations.LeaderboardCache();
        cache.put("token", java.util.List.of(
                new com.smile.aceeconomy.operations.LeaderboardEntry(1, first, "Alice",
                        harness.currencies().get("token").amountOf(9)),
                new com.smile.aceeconomy.operations.LeaderboardEntry(2, second, "Bob",
                        harness.currencies().get("token").amountOf(3))),
                now);
        PlaceholderResolver r = snapshotResolver(
                cache, java.time.Duration.ofMinutes(5), () -> now, api);
        assertEquals("2", r.resolve(player(second, "Bob"), "rank_token"));
    }

    @Test
    void rankPlayerAbsentFromSnapshotReturnsNull() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.EPOCH.plusSeconds(60);
        PlaceholderResolver r = snapshotResolver(
                seededCache("dollar", now, first, second),
                java.time.Duration.ofMinutes(5), () -> now, api);
        assertNull(r.resolve(player(UUID.randomUUID(), "Ghost"), "rank"));
    }

    @Test
    void topNameAndBalanceResolveFromSnapshot() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.EPOCH.plusSeconds(60);
        PlaceholderResolver r = snapshotResolver(
                seededCache("dollar", now, first, second),
                java.time.Duration.ofMinutes(5), () -> now, api);
        assertEquals("Alice", r.resolve(player(first, "Alice"), "top_name_1"));
        assertEquals("Bob", r.resolve(player(first, "Alice"), "top_name_2"));
        assertEquals("300.00", r.resolve(player(first, "Alice"), "top_balance_1"));
        assertEquals("100.00", r.resolve(player(first, "Alice"), "top_balance_2_dollar"));
        assertEquals("Alice", r.resolve(player(first, "Alice"), "top_name_1_dollar"));
    }

    @Test
    void topIndexOutOfRangeOrOversizedReturnsNull() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.EPOCH.plusSeconds(60);
        PlaceholderResolver r = snapshotResolver(
                seededCache("dollar", now, first, second),
                java.time.Duration.ofMinutes(5), () -> now, api);
        OfflinePlayer p = player(first, "Alice");
        assertNull(r.resolve(p, "top_name_0"));
        assertNull(r.resolve(p, "top_name_3"));
        assertNull(r.resolve(p, "top_name_101"));
        assertNull(r.resolve(p, "top_balance_0"));
        assertNull(r.resolve(p, "top_balance_999"));
        assertNull(r.resolve(p, "top_name_abc"));
        assertNull(r.resolve(p, "top_name_"));
        assertNull(r.resolve(p, "top_balance_-1"));
    }

    @Test
    void unknownCurrencyForRankAndTopReturnsNull() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.EPOCH.plusSeconds(60);
        PlaceholderResolver r = snapshotResolver(
                seededCache("dollar", now, first, second),
                java.time.Duration.ofMinutes(5), () -> now, api);
        OfflinePlayer p = player(first, "Alice");
        assertNull(r.resolve(p, "rank_gem"));
        assertNull(r.resolve(p, "top_name_1_gem"));
        assertNull(r.resolve(p, "top_balance_1_gem"));
    }

    @Test
    void currencyNameAndSymbolResolve() {
        java.time.Instant now = java.time.Instant.EPOCH.plusSeconds(60);
        PlaceholderResolver r = snapshotResolver(
                new com.smile.aceeconomy.infrastructure.operations.LeaderboardCache(),
                java.time.Duration.ofMinutes(5), () -> now, api);
        OfflinePlayer p = player(UUID.randomUUID(), "A");
        assertEquals("Dollar", r.resolve(p, "currency_name_dollar"));
        assertEquals("$", r.resolve(p, "currency_symbol_dollar"));
        assertEquals("Token", r.resolve(p, "currency_name_token"));
        assertEquals("T", r.resolve(p, "currency_symbol_token"));
        assertNull(r.resolve(p, "currency_name_gem"));
        assertNull(r.resolve(p, "currency_symbol_gem"));
        assertNull(r.resolve(p, "currency_name_"));
        assertNull(r.resolve(p, "currency_symbol_Bad-Id!"));
    }

    @Test
    void rankAndTopCacheMissReturnsNullWithoutTouchingApi() {
        EconomyApi apiMock = mock(EconomyApi.class);
        java.time.Instant now = java.time.Instant.EPOCH.plusSeconds(60);
        PlaceholderResolver r = snapshotResolver(
                new com.smile.aceeconomy.infrastructure.operations.LeaderboardCache(),
                java.time.Duration.ofMinutes(5), () -> now, apiMock);
        OfflinePlayer p = player(UUID.randomUUID(), "A");
        assertNull(r.resolve(p, "rank"));
        assertNull(r.resolve(p, "top_name_1"));
        assertNull(r.resolve(p, "top_balance_1"));
        verifyNoInteractions(apiMock);
    }

    @Test
    void expiredSnapshotReturnsNullWithoutTouchingApi() {
        EconomyApi apiMock = mock(EconomyApi.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        java.time.Instant then = java.time.Instant.EPOCH;
        java.time.Instant now = then.plus(java.time.Duration.ofMinutes(6));
        PlaceholderResolver r = snapshotResolver(
                seededCache("dollar", then, first, second),
                java.time.Duration.ofMinutes(5), () -> now, apiMock);
        assertNull(r.resolve(player(first, "Alice"), "rank"));
        assertNull(r.resolve(player(first, "Alice"), "top_name_1"));
        verifyNoInteractions(apiMock);
    }

    @Test
    void malformedRankAndTopPlaceholdersReturnNull() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.EPOCH.plusSeconds(60);
        PlaceholderResolver r = snapshotResolver(
                seededCache("dollar", now, first, second),
                java.time.Duration.ofMinutes(5), () -> now, api);
        OfflinePlayer p = player(first, "Alice");
        assertNull(r.resolve(p, "rank_"));
        assertNull(r.resolve(p, "rank_Bad-Id!"));
        assertNull(r.resolve(p, "top_name_1_Bad-Id!"));
        assertNull(r.resolve(p, "top_names_1"));
        assertNull(r.resolve(p, "tops_1"));
        assertNull(r.resolve(p, "rank2"));
        assertDoesNotThrow(() -> r.resolve(p, "top_name_1_dollar"));
    }
}
