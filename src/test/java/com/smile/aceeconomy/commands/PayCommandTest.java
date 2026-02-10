package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.TestBase;
import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.data.Currency;
import com.smile.aceeconomy.manager.CurrencyManager;
import com.smile.aceeconomy.manager.PermissionManager;
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
 * PayCommand 單元測試。
 * <p>
 * 使用純 Mockito 模擬 Player、AceEconomy、以及所有 Manager。
 * 不依賴 MockBukkit，因此不會觸發 SharedConstants 錯誤。
 * </p>
 */
class PayCommandTest extends TestBase {

    private PayCommand payCommand;
    private CurrencyManager currencyManager;
    private PermissionManager permissionManager;

    private Player sender;
    private Player receiver;
    private UUID senderUuid;
    private UUID receiverUuid;

    private static final Currency DOLLAR = new Currency("dollar", "Dollar", "$", "#,##0.00", true);

    @BeforeEach
    void setUpCommand() {
        senderUuid = UUID.randomUUID();
        receiverUuid = UUID.randomUUID();

        // Create mock players
        sender = mockPlayer("Sender", senderUuid);
        receiver = mockPlayer("Receiver", receiverUuid);

        // Set up ConfigManager
        lenient().when(configManager.getCurrencies()).thenReturn(Map.of("dollar", DOLLAR));
        lenient().when(configManager.getDefaultCurrency()).thenReturn(DOLLAR);
        lenient().when(configManager.getCurrency("dollar")).thenReturn(DOLLAR);
        lenient().when(configManager.formatMoney(anyDouble(), anyString())).thenAnswer(inv -> {
            double amount = inv.getArgument(0);
            return "$" + String.format("%.2f", amount);
        });

        // Set up real CurrencyManager with mocked dependencies
        permissionManager = mock(PermissionManager.class);
        currencyManager = new CurrencyManager(plugin, permissionManager, storageHandler, configManager);
        lenient().when(plugin.getCurrencyManager()).thenReturn(currencyManager);

        // Default mock for storageHandler
        lenient().when(storageHandler.loadAccount(any(UUID.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(storageHandler.saveAccount(any(Account.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Register sender as online via Bukkit.getPlayer(name)
        bukkitMock.when(() -> Bukkit.getPlayer("Receiver")).thenReturn(receiver);
        // Sender is the command executor, no need to register via getPlayer

        // Instantiate the command under test
        payCommand = new PayCommand(plugin);
    }

    // ==================== 基本驗證 ====================

    @Test
    @DisplayName("非玩家不能使用 /pay")
    void testConsoleSender_Rejected() {
        org.bukkit.command.ConsoleCommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);

        payCommand.onCommand(console, command, "pay", new String[] { "Receiver", "100" });

        verify(messageManager).send(eq(console), eq("general.console-only-player"));
    }

    @Test
    @DisplayName("沒有權限時拒絕")
    void testNoPermission_Rejected() {
        when(sender.hasPermission("aceeconomy.pay")).thenReturn(false);

        payCommand.onCommand(sender, command, "pay", new String[] { "Receiver", "100" });

        verify(messageManager).send(eq(sender), eq("general.no-permission"));
    }

    @Test
    @DisplayName("參數不足時顯示使用方法")
    void testInsufficientArgs_ShowsUsage() {
        payCommand.onCommand(sender, command, "pay", new String[] { "Receiver" });

        verify(messageManager).send(eq(sender), eq("usage.pay"));
    }

    // ==================== 自我轉帳 ====================

    @Test
    @DisplayName("不能轉帳給自己")
    void testPaySelf_Fail() {
        payCommand.onCommand(sender, command, "pay", new String[] { "Sender", "100" });

        verify(messageManager).send(eq(sender), eq("economy.cannot-pay-self"));
    }

    // ==================== 金額驗證 ====================

    @Test
    @DisplayName("負數金額被拒絕")
    void testPayNegative_Fail() {
        payCommand.onCommand(sender, command, "pay", new String[] { "Receiver", "-50" });

        verify(messageManager).send(eq(sender), eq("general.amount-must-be-positive"));
    }

    @Test
    @DisplayName("零金額被拒絕")
    void testPayZero_Fail() {
        payCommand.onCommand(sender, command, "pay", new String[] { "Receiver", "0" });

        verify(messageManager).send(eq(sender), eq("general.amount-must-be-positive"));
    }

    @Test
    @DisplayName("無效金額格式被拒絕")
    void testPayInvalidAmount_Fail() {
        payCommand.onCommand(sender, command, "pay", new String[] { "Receiver", "abc" });

        verify(messageManager).send(eq(sender), eq("general.invalid-amount"), any());
    }

    // ==================== 餘額不足 ====================

    @Test
    @DisplayName("餘額不足時拒絕轉帳")
    void testPayInsufficientFunds_Fail() {
        // Sender has 50, trying to pay 100
        Account senderAccount = new Account(senderUuid, "Sender", 50.0);
        Account receiverAccount = new Account(receiverUuid, "Receiver", 500.0);
        currencyManager.cacheAccount(senderAccount);
        currencyManager.cacheAccount(receiverAccount);

        // Mock loadAccount to return a valid receiver account (required by
        // executeTransfer)
        lenient().when(storageHandler.loadAccount(eq(receiverUuid)))
                .thenReturn(CompletableFuture.completedFuture(receiverAccount));

        // Mock economyProvider to fail with InsufficientFundsException
        when(economyProvider.transfer(any(UUID.class), any(UUID.class), anyString(), anyDouble()))
                .thenReturn(CompletableFuture.failedFuture(
                        new com.smile.aceeconomy.exception.InsufficientFundsException("餘額不足")));

        payCommand.onCommand(sender, command, "pay", new String[] { "Receiver", "100" });

        // Wait for async chain (loadAccount → transfer → exceptionally)
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
        }

        // Verify sender.sendMessage called with Component (InsufficientFundsException
        // handler uses raw Component)
        verify(sender, atLeastOnce()).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    // ==================== 成功轉帳 (在線玩家) ====================

    @Test
    @DisplayName("成功轉帳給在線玩家")
    void testPaySuccess_OnlinePlayer() throws Exception {
        // Setup accounts
        Account senderAccount = new Account(senderUuid, "Sender", 1000.0);
        Account receiverAccount = new Account(receiverUuid, "Receiver", 500.0);
        currencyManager.cacheAccount(senderAccount);
        currencyManager.cacheAccount(receiverAccount);

        // Mock EconomyProvider.transfer -> successfully modifies balances
        when(economyProvider.transfer(senderUuid, receiverUuid, "dollar", 100.0))
                .thenAnswer(inv -> {
                    // Simulate actual transfer
                    currencyManager.withdraw(senderUuid, "dollar", 100.0, null);
                    currencyManager.deposit(receiverUuid, "dollar", 100.0);
                    return CompletableFuture.completedFuture(true);
                });

        // Mock storageHandler.loadAccount for the target (used in executeTransfer)
        when(storageHandler.loadAccount(receiverUuid))
                .thenReturn(CompletableFuture.completedFuture(receiverAccount));

        payCommand.onCommand(sender, command, "pay", new String[] { "Receiver", "100" });

        // Wait a moment for async CompletableFuture chains to complete
        Thread.sleep(200);

        // Verify transfer was called
        verify(economyProvider).transfer(senderUuid, receiverUuid, "dollar", 100.0);

        // Verify sender received success message
        verify(messageManager).send(eq(sender), eq("economy.payment-sent-currency"), any(), any(), any());

        // Verify balances changed
        assertEquals(900.0, currencyManager.getBalance(senderUuid), 0.01);
        assertEquals(600.0, currencyManager.getBalance(receiverUuid), 0.01);
    }

    // ==================== 離線玩家 ====================

    @Test
    @DisplayName("UserCacheManager 為 null 時拒絕離線轉帳")
    void testPayOffline_NullUserCacheManager() {
        // Sender has enough money
        Account senderAccount = new Account(senderUuid, "Sender", 200.0);
        currencyManager.cacheAccount(senderAccount);

        // OfflinePlayer is not online
        bukkitMock.when(() -> Bukkit.getPlayer("OfflinePlayer")).thenReturn(null);
        // UserCacheManager is null
        when(plugin.getUserCacheManager()).thenReturn(null);

        payCommand.onCommand(sender, command, "pay", new String[] { "OfflinePlayer", "50" });

        verify(messageManager).send(eq(sender), eq("general.offline-support-disabled"));
    }

    @Test
    @DisplayName("成功轉帳給離線玩家")
    void testPayOfflinePlayer_Success() throws Exception {
        UUID offlineUuid = UUID.randomUUID();

        // Sender has money
        Account senderAccount = new Account(senderUuid, "Sender", 200.0);
        currencyManager.cacheAccount(senderAccount);

        // Target is offline
        bukkitMock.when(() -> Bukkit.getPlayer("OfflinePlayer")).thenReturn(null);

        // Mock UUID lookup
        when(userCacheManager.getUUID("OfflinePlayer"))
                .thenReturn(CompletableFuture.completedFuture(offlineUuid));

        // Mock account load for offline player
        Account offlineAccount = new Account(offlineUuid, "OfflinePlayer", 100.0);
        when(storageHandler.loadAccount(offlineUuid))
                .thenReturn(CompletableFuture.completedFuture(offlineAccount));

        // Mock successful transfer
        when(economyProvider.transfer(senderUuid, offlineUuid, "dollar", 50.0))
                .thenReturn(CompletableFuture.completedFuture(true));

        payCommand.onCommand(sender, command, "pay", new String[] { "OfflinePlayer", "50" });

        // Wait for async chains
        Thread.sleep(300);

        // Verify UUID lookup was called
        verify(userCacheManager).getUUID("OfflinePlayer");

        // Verify account was loaded
        verify(storageHandler, atLeastOnce()).loadAccount(offlineUuid);

        // Verify transfer was attempted
        verify(economyProvider).transfer(senderUuid, offlineUuid, "dollar", 50.0);

        // Verify success message was sent
        verify(messageManager).send(eq(sender), eq("economy.payment-sent-currency"), any(), any(), any());
    }

    @Test
    @DisplayName("離線玩家找不到時回報錯誤")
    void testPayOfflinePlayer_NotFound() throws Exception {
        Account senderAccount = new Account(senderUuid, "Sender", 200.0);
        currencyManager.cacheAccount(senderAccount);

        bukkitMock.when(() -> Bukkit.getPlayer("Ghost")).thenReturn(null);

        // UUID lookup returns null
        when(userCacheManager.getUUID("Ghost"))
                .thenReturn(CompletableFuture.completedFuture(null));

        payCommand.onCommand(sender, command, "pay", new String[] { "Ghost", "50" });

        Thread.sleep(200);

        verify(userCacheManager).getUUID("Ghost");
        verify(messageManager).send(eq(sender), eq("general.player-not-found"), any());
    }

    // ==================== 不存在的貨幣 ====================

    @Test
    @DisplayName("不存在的貨幣被拒絕")
    void testPayUnknownCurrency_Fail() {
        Account senderAccount = new Account(senderUuid, "Sender", 1000.0);
        currencyManager.cacheAccount(senderAccount);

        payCommand.onCommand(sender, command, "pay", new String[] { "Receiver", "100", "fakecoin" });

        verify(messageManager).send(eq(sender), eq("general.unknown-currency"), any());
    }
}
