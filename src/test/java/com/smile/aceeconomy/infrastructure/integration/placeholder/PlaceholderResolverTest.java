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
}
