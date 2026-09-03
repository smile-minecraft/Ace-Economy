package com.smile.aceeconomy.infrastructure.integration.vault;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.EconomyApiImpl;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;

import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.MockedStatic;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Name-based Vault lookups must resolve through the Bukkit player cache and then
 * delegate to the UUID core path. Unknown names must fail loudly on mutations instead
 * of hiding behind a generic stub message.
 */
class VaultPlayerNameLookupTest {

    private final EconomyTestHarness harness = new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));
    private final EconomyApi api = new EconomyApiImpl(harness.service(), harness.publisher());
    private final VaultEconomyProvider provider = new VaultEconomyProvider(api, harness.currencies());

    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void mockServer() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
    }

    @AfterEach
    void releaseServer() {
        bukkit.close();
    }

    private Player online(UUID uuid, String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(uuid);
        when(p.getName()).thenReturn(name);
        return p;
    }

    private UUID accountWith(String name, double balance) {
        UUID uuid = UUID.randomUUID();
        api.createAccount(uuid, name);
        if (balance > 0) {
            api.deposit(uuid, harness.currencies().defaultCurrencyId(),
                    harness.currencies().getDefault().amountOf(balance));
        }
        Player p = online(uuid, name);
        bukkit.when(() -> Bukkit.getPlayerExact(name)).thenReturn(p);
        return uuid;
    }

    @Test
    void knownNameBalanceDelegatesToCorePath() {
        accountWith("Alice", 75);
        assertEquals(75.0, provider.getBalance("Alice"), 0.0001);
    }

    @Test
    void knownNameHasDelegatesToCorePath() {
        accountWith("Alice", 100);
        assertTrue(provider.has("Alice", 50.0));
        assertFalse(provider.has("Alice", 500.0));
    }

    @Test
    void knownNameHasAccountDelegatesToCorePath() {
        accountWith("Alice", 0);
        assertTrue(provider.hasAccount("Alice"));
        assertFalse(provider.hasAccount("Nobody"));
    }

    @Test
    void knownNameDepositDelegatesToCorePath() {
        accountWith("Alice", 10);
        EconomyResponse r = provider.depositPlayer("Alice", 25.0);
        assertTrue(r.transactionSuccess(), () -> "expected success, got: " + r.errorMessage);
        assertEquals(35.0, r.balance, 0.0001);
    }

    @Test
    void knownNameWithdrawDelegatesToCorePath() {
        accountWith("Alice", 100);
        EconomyResponse r = provider.withdrawPlayer("Alice", 40.0);
        assertTrue(r.transactionSuccess(), () -> "expected success, got: " + r.errorMessage);
        assertEquals(60.0, r.balance, 0.0001);
    }

    @Test
    void knownNameCreateAccountDelegatesToCorePath() {
        UUID uuid = UUID.randomUUID();
        Player newcomer = online(uuid, "Newbie");
        bukkit.when(() -> Bukkit.getPlayerExact("Newbie")).thenReturn(newcomer);
        assertTrue(provider.createPlayerAccount("Newbie"));
    }

    @Test
    void nameLookupIsCaseInsensitive() {
        Player alice = online(accountWith("Alice", 0), "Alice");
        bukkit.when(() -> Bukkit.getPlayerExact("alice")).thenReturn(null);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(alice));
        assertTrue(provider.hasAccount("alice"));
        assertTrue(provider.hasAccount("ALICE"));
    }

    @Test
    void worldOverloadDelegatesToGlobalBalance() {
        accountWith("Alice", 75);
        assertEquals(75.0, provider.getBalance("Alice", "world"), 0.0001);
        assertTrue(provider.has("Alice", "world", 50.0));
        assertTrue(provider.hasAccount("Alice", "world"));
    }

    @Test
    void unknownNameDepositNamesTheProblem() {
        EconomyResponse r = provider.depositPlayer("Nobody", 10.0);
        assertFalse(r.transactionSuccess());
        assertNotNull(r.errorMessage);
        assertTrue(r.errorMessage.toLowerCase(Locale.ROOT).contains("unknown"),
                () -> "mutation must say the name is unknown, got: " + r.errorMessage);
    }

    @Test
    void unknownNameWithdrawNamesTheProblem() {
        EconomyResponse r = provider.withdrawPlayer("Nobody", 10.0);
        assertFalse(r.transactionSuccess());
        assertNotNull(r.errorMessage);
        assertTrue(r.errorMessage.toLowerCase(Locale.ROOT).contains("unknown"),
                () -> "mutation must say the name is unknown, got: " + r.errorMessage);
    }

    @Test
    void blankNameDepositNamesTheProblem() {
        EconomyResponse r = provider.depositPlayer("   ", 10.0);
        assertFalse(r.transactionSuccess());
        assertNotNull(r.errorMessage);
        assertTrue(r.errorMessage.toLowerCase(Locale.ROOT).contains("blank"),
                () -> "mutation must say the name is blank, got: " + r.errorMessage);
    }

    @Test
    void renamedPlayerStillHitsSameUuidAccount() {
        UUID uuid = accountWith("Alice", 60);
        OfflinePlayer renamed = mock(OfflinePlayer.class);
        when(renamed.getUniqueId()).thenReturn(uuid);
        when(renamed.getName()).thenReturn("AliceTheSecond");
        when(renamed.hasPlayedBefore()).thenReturn(true);
        bukkit.when(() -> Bukkit.getPlayerExact("AliceTheSecond")).thenReturn(null);
        bukkit.when(() -> Bukkit.getOfflinePlayerIfCached("AliceTheSecond")).thenReturn(renamed);
        assertEquals(60.0, provider.getBalance("AliceTheSecond"), 0.0001);
    }

    @Test
    void injectedResolverIsUsedAndTrimsCaseInsensitively() {
        UUID uuid = UUID.randomUUID();
        api.createAccount(uuid, "Alice");
        api.deposit(uuid, harness.currencies().defaultCurrencyId(),
                harness.currencies().getDefault().amountOf(20));
        PlayerIdentityResolver fake = name -> {
            if (name != null && name.equalsIgnoreCase("alice")) {
                OfflinePlayer p = mock(OfflinePlayer.class);
                when(p.getUniqueId()).thenReturn(uuid);
                when(p.getName()).thenReturn("Alice");
                return java.util.Optional.of(p);
            }
            return java.util.Optional.empty();
        };
        VaultEconomyProvider injected = new VaultEconomyProvider(api, harness.currencies(), fake);
        assertEquals(20.0, injected.getBalance("  ALICE  "), 0.0001);
        assertTrue(injected.hasAccount("alice"));
        assertFalse(injected.hasAccount("Nobody"));
    }

    @Test
    void nullResolverFallsBackToBukkitCache() {
        accountWith("Alice", 30);
        VaultEconomyProvider fallback = new VaultEconomyProvider(api, harness.currencies(), null);
        assertEquals(30.0, fallback.getBalance("Alice"), 0.0001);
        assertTrue(fallback.hasAccount("Alice"));
    }
}
