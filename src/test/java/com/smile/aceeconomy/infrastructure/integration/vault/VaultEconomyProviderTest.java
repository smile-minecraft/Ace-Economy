package com.smile.aceeconomy.infrastructure.integration.vault;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;

import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.OfflinePlayer;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VaultEconomyProviderTest {

    private final EconomyTestHarness harness = new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));
    private final EconomyApi api = new EconomyApiImpl(harness.service(), harness.publisher());
    private final VaultEconomyProvider provider = new VaultEconomyProvider(api, harness.currencies());

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
    void depositSuccessMapsToVaultSuccess() {
        UUID uuid = accountWith(0);
        EconomyResponse r = provider.depositPlayer(player(uuid, "Alice"), 50.0);
        assertTrue(r.transactionSuccess(), () -> "expected success, got: " + r.errorMessage);
        assertEquals(50.0, r.balance, 0.0001);
        assertEquals(50.0, r.amount, 0.0001);
    }

    @Test
    void depositMissingAccountMapsToFailureNoSuccess() {
        UUID uuid = UUID.randomUUID(); // not created
        EconomyResponse r = provider.depositPlayer(player(uuid, "None"), 50.0);
        assertFalse(r.transactionSuccess());
        assertEquals(0.0, r.amount, 0.0001);
        assertEquals(0.0, r.balance, 0.0001);
        assertNotNull(r.errorMessage);
    }

    @Test
    void withdrawSuccessMapsToVaultSuccess() {
        UUID uuid = accountWith(100);
        EconomyResponse r = provider.withdrawPlayer(player(uuid, "Bob"), 40.0);
        assertTrue(r.transactionSuccess(), () -> "expected success, got: " + r.errorMessage);
        assertEquals(60.0, r.balance, 0.0001);
    }

    @Test
    void withdrawInsufficientFundsMapsToFailure() {
        UUID uuid = accountWith(10);
        EconomyResponse r = provider.withdrawPlayer(player(uuid, "Bob"), 40.0);
        assertFalse(r.transactionSuccess());
        assertEquals(0.0, r.amount, 0.0001);
    }

    @Test
    void negativeAmountRejected() {
        UUID uuid = accountWith(0);
        EconomyResponse r = provider.depositPlayer(player(uuid, "Z"), -5.0);
        assertFalse(r.transactionSuccess());
    }

    @Test
    void balanceOfMissingAccountIsZero() {
        UUID uuid = UUID.randomUUID();
        assertEquals(0.0, provider.getBalance(player(uuid, "X")), 0.0001);
    }

    @Test
    void balanceReflectsDeposit() {
        UUID uuid = accountWith(75);
        assertEquals(75.0, provider.getBalance(player(uuid, "Y")), 0.0001);
    }

    @Test
    void hasFalseWhenInsufficient() {
        UUID uuid = accountWith(0);
        assertFalse(provider.has(player(uuid, "Y"), 1.0));
    }

    @Test
    void hasTrueWhenSufficient() {
        UUID uuid = accountWith(100);
        assertTrue(provider.has(player(uuid, "Y"), 50.0));
    }

    @Test
    void hasAccountTrueOnlyWhenCreated() {
        UUID uuid = accountWith(0);
        assertTrue(provider.hasAccount(player(uuid, "Y")));
        assertFalse(provider.hasAccount(player(UUID.randomUUID(), "Z")));
        assertFalse(provider.hasAccount("byName"));
    }

    @Test
    void createPlayerAccountDelegatesToApi() {
        UUID uuid = UUID.randomUUID();
        assertTrue(provider.createPlayerAccount(player(uuid, "New")));
        assertTrue(provider.hasAccount(player(uuid, "New")));
    }

    @Test
    void identityAndFormat() {
        assertEquals("AceEconomy", provider.getName());
        assertTrue(provider.isEnabled());
        assertFalse(provider.hasBankSupport());
        assertEquals(2, provider.fractionalDigits());
        assertTrue(provider.format(12.5).contains("12.50"));
    }
}
