package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.TestBase;
import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.data.Currency;
import com.smile.aceeconomy.manager.CurrencyManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminCommand 單元測試。
 * <p>
 * 使用純 Mockito 測試 /aceeco give、take、set 等功能。
 * </p>
 */
class AdminCommandTest extends TestBase {

    private AdminCommand adminCommand;
    private CurrencyManager currencyManager;

    private Player admin;
    private Player target;
    private UUID adminUuid;
    private UUID targetUuid;

    private static final Currency DOLLAR = new Currency("dollar", "Dollar", "$", "#,##0.00", true);

    @BeforeEach
    void setUpCommand() {
        adminUuid = UUID.randomUUID();
        targetUuid = UUID.randomUUID();

        admin = mockPlayer("Admin", adminUuid);
        target = mockPlayer("Target", targetUuid);

        // ConfigManager setup
        lenient().when(configManager.getCurrencies()).thenReturn(Map.of("dollar", DOLLAR));
        lenient().when(configManager.getDefaultCurrency()).thenReturn(DOLLAR);
        lenient().when(configManager.getCurrency("dollar")).thenReturn(DOLLAR);
        lenient().when(configManager.formatMoney(anyDouble(), anyString())).thenAnswer(inv -> {
            double amount = inv.getArgument(0);
            return "$" + String.format("%.2f", amount);
        });

        // Real CurrencyManager
        currencyManager = new CurrencyManager(storageHandler, configManager);
        lenient().when(plugin.getCurrencyManager()).thenReturn(currencyManager);

        // Mock Logger
        java.util.logging.Logger mockLogger = mock(java.util.logging.Logger.class);
        lenient().when(plugin.getLogger()).thenReturn(mockLogger);

        // Default storage mock
        lenient().when(storageHandler.loadAccount(any(UUID.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(storageHandler.saveAccount(any(Account.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Register target as online
        bukkitMock.when(() -> Bukkit.getPlayer("Target")).thenReturn(target);
        bukkitMock.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(target);

        adminCommand = new AdminCommand(plugin);
    }

    // ==================== 權限與基本驗證 ====================

    @Test
    @DisplayName("沒有管理員權限時拒絕")
    void testNoPermission_Rejected() {
        when(admin.hasPermission("aceeconomy.admin")).thenReturn(false);

        adminCommand.onCommand(admin, command, "aceeco", new String[] { "give", "Target", "100" });

        verify(messageManager).send(eq(admin), eq("general.no-permission"));
    }

    @Test
    @DisplayName("無參數時顯示幫助")
    void testNoArgs_ShowsHelp() {
        adminCommand.onCommand(admin, command, "aceeco", new String[] {});

        verify(messageManager).send(eq(admin), eq("admin.help-header"));
    }

    @Test
    @DisplayName("參數不足時顯示幫助 (僅操作名)")
    void testInsufficientArgs_ShowsHelp() {
        adminCommand.onCommand(admin, command, "aceeco", new String[] { "give" });

        verify(messageManager).send(eq(admin), eq("admin.help-header"));
    }

    @Test
    @DisplayName("無效金額格式被拒絕")
    void testInvalidAmount_Rejected() {
        adminCommand.onCommand(admin, command, "aceeco", new String[] { "give", "Target", "abc" });

        verify(messageManager).send(eq(admin), eq("general.invalid-amount"), any());
    }

    @Test
    @DisplayName("負數金額對 give 被拒絕")
    void testNegativeAmount_Give_Rejected() {
        adminCommand.onCommand(admin, command, "aceeco", new String[] { "give", "Target", "-50" });

        verify(messageManager).send(eq(admin), eq("general.amount-must-be-positive"));
    }

    @Test
    @DisplayName("不存在的貨幣被拒絕")
    void testUnknownCurrency_Rejected() {
        adminCommand.onCommand(admin, command, "aceeco",
                new String[] { "give", "Target", "100", "fakecoin" });

        verify(messageManager).send(eq(admin), eq("general.unknown-currency"), any());
    }

    // ==================== Give 功能 ====================

    @Test
    @DisplayName("成功給予在線玩家金幣")
    void testGive_OnlinePlayer_Success() throws Exception {
        // Setup target account
        Account targetAccount = new Account(targetUuid, "Target", 100.0);
        currencyManager.cacheAccount(targetAccount);
        when(storageHandler.loadAccount(targetUuid))
                .thenReturn(CompletableFuture.completedFuture(targetAccount));

        // Mock deposit success
        when(economyProvider.deposit(targetUuid, "dollar", 50.0))
                .thenAnswer(inv -> {
                    currencyManager.deposit(targetUuid, "dollar", 50.0);
                    return CompletableFuture.completedFuture(true);
                });

        adminCommand.onCommand(admin, command, "aceeco", new String[] { "give", "Target", "50" });

        Thread.sleep(200);

        verify(economyProvider).deposit(targetUuid, "dollar", 50.0);
        verify(messageManager).send(eq(admin), eq("admin.give"), any(), any(), any());
        assertEquals(150.0, currencyManager.getBalance(targetUuid), 0.01);
    }

    @Test
    @DisplayName("成功給予離線玩家金幣")
    void testGive_OfflinePlayer_Success() throws Exception {
        UUID offlineUuid = UUID.randomUUID();

        // Target not online
        bukkitMock.when(() -> Bukkit.getPlayer("OfflineTarget")).thenReturn(null);

        // UUID lookup
        when(userCacheManager.getUUID("OfflineTarget"))
                .thenReturn(CompletableFuture.completedFuture(offlineUuid));

        // Account load
        Account offlineAccount = new Account(offlineUuid, "OfflineTarget", 100.0);
        when(storageHandler.loadAccount(offlineUuid))
                .thenReturn(CompletableFuture.completedFuture(offlineAccount));

        // Mock deposit
        when(economyProvider.deposit(offlineUuid, "dollar", 50.0))
                .thenReturn(CompletableFuture.completedFuture(true));

        adminCommand.onCommand(admin, command, "aceeco",
                new String[] { "give", "OfflineTarget", "50" });

        Thread.sleep(300);

        verify(userCacheManager).getUUID("OfflineTarget");
        verify(storageHandler, atLeastOnce()).loadAccount(offlineUuid);
        verify(economyProvider).deposit(offlineUuid, "dollar", 50.0);
        verify(messageManager).send(eq(admin), eq("admin.give"), any(), any(), any());
    }

    // ==================== Take 功能 ====================

    @Test
    @DisplayName("成功從在線玩家扣款")
    void testTake_OnlinePlayer_Success() throws Exception {
        Account targetAccount = new Account(targetUuid, "Target", 200.0);
        currencyManager.cacheAccount(targetAccount);
        when(storageHandler.loadAccount(targetUuid))
                .thenReturn(CompletableFuture.completedFuture(targetAccount));

        when(economyProvider.withdraw(targetUuid, "dollar", 50.0))
                .thenAnswer(inv -> {
                    currencyManager.withdraw(targetUuid, "dollar", 50.0, null);
                    return CompletableFuture.completedFuture(true);
                });

        adminCommand.onCommand(admin, command, "aceeco", new String[] { "take", "Target", "50" });

        Thread.sleep(200);

        verify(economyProvider).withdraw(targetUuid, "dollar", 50.0);
        verify(messageManager).send(eq(admin), eq("admin.take"), any(), any(), any());
        assertEquals(150.0, currencyManager.getBalance(targetUuid), 0.01);
    }

    // ==================== Set 功能 ====================

    @Test
    @DisplayName("成功設定在線玩家餘額")
    void testSet_OnlinePlayer_Success() throws Exception {
        Account targetAccount = new Account(targetUuid, "Target", 200.0);
        currencyManager.cacheAccount(targetAccount);
        when(storageHandler.loadAccount(targetUuid))
                .thenReturn(CompletableFuture.completedFuture(targetAccount));

        when(economyProvider.setBalance(targetUuid, "dollar", 999.0))
                .thenAnswer(inv -> {
                    currencyManager.setBalance(targetUuid, "dollar", 999.0);
                    return CompletableFuture.completedFuture(true);
                });

        adminCommand.onCommand(admin, command, "aceeco", new String[] { "set", "Target", "999" });

        Thread.sleep(200);

        verify(economyProvider).setBalance(targetUuid, "dollar", 999.0);
        verify(messageManager).send(eq(admin), eq("admin.set"), any(), any(), any());
        assertEquals(999.0, currencyManager.getBalance(targetUuid), 0.01);
    }

    // ==================== 離線玩家找不到 ====================

    @Test
    @DisplayName("離線玩家找不到時回報錯誤")
    void testGive_OfflinePlayer_NotFound() throws Exception {
        bukkitMock.when(() -> Bukkit.getPlayer("Ghost")).thenReturn(null);

        when(userCacheManager.getUUID("Ghost"))
                .thenReturn(CompletableFuture.completedFuture(null));

        adminCommand.onCommand(admin, command, "aceeco", new String[] { "give", "Ghost", "100" });

        Thread.sleep(200);

        verify(userCacheManager).getUUID("Ghost");
        verify(messageManager).send(eq(admin), eq("general.player-not-found"), any());
    }

    // ==================== Reload 功能 ====================

    @Test
    @DisplayName("具備權限時成功 reload")
    void testReload_Success() {
        adminCommand.onCommand(admin, command, "aceeco", new String[] { "reload" });

        verify(configManager).reload();
        verify(messageManager).send(eq(admin), eq("general.reload-success"));
    }

    @Test
    @DisplayName("沒有 reload 權限時拒絕")
    void testReload_NoPermission() {
        when(admin.hasPermission("aceeconomy.admin")).thenReturn(true);
        when(admin.hasPermission("aceeconomy.command.reload")).thenReturn(false);

        adminCommand.onCommand(admin, command, "aceeco", new String[] { "reload" });

        verify(messageManager).send(eq(admin), eq("general.no-permission"));
    }
}
