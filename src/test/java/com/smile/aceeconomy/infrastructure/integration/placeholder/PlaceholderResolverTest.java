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
}
