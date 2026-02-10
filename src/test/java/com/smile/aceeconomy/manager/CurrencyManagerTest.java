package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.data.Currency;
import com.smile.aceeconomy.storage.StorageHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CurrencyManager 單元測試。
 * <p>
 * 測試貨幣管理器的核心邏輯、執行緒安全性、以及 SSOT 架構。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurrencyManagerTest {

    @Mock
    private StorageHandler storageHandler;

    @Mock
    private Logger logger;

    @Mock
    private ConfigManager configManager;

    private CurrencyManager currencyManager;
    private UUID playerUuid;

    // 測試用貨幣
    private static final Currency DOLLAR = new Currency("dollar", "金幣", "$", "#,##0.00", true);
    private static final Currency TOKEN = new Currency("token", "代幣", "ⓒ", "#,##0", false);

    @BeforeEach
    void setUp() {
        currencyManager = new CurrencyManager(storageHandler, logger, 100.0);
        currencyManager.setConfigManager(configManager);
        playerUuid = UUID.randomUUID();

        // 預設的 ConfigManager 行為
        lenient().when(configManager.getCurrencies()).thenReturn(Map.of(
                "dollar", DOLLAR,
                "token", TOKEN));
        lenient().when(configManager.getDefaultCurrency()).thenReturn(DOLLAR);
    }

    // ==================== 核心業務邏輯測試 ====================

    @Nested
    @DisplayName("核心業務邏輯")
    class CoreBusinessLogicTests {

        @Test
        @DisplayName("測試存款功能")
        void testDeposit() {
            currencyManager.createAccount(playerUuid, "TestPlayer");

            boolean result = currencyManager.deposit(playerUuid, 100.0);

            assertTrue(result, "存款應該成功");
            assertEquals(200.0, currencyManager.getBalance(playerUuid), 0.001, "餘額應為 200.0");
        }

        @Test
        @DisplayName("測試提款成功")
        void testWithdrawSuccess() {
            currencyManager.createAccount(playerUuid, "TestPlayer");

            boolean result = currencyManager.withdraw(playerUuid, 50.0);

            assertTrue(result, "提款應該成功");
            assertEquals(50.0, currencyManager.getBalance(playerUuid), 0.001, "餘額應剩餘 50.0");
        }

        @Test
        @DisplayName("測試提款失敗 (餘額不足)")
        void testWithdrawFail() {
            currencyManager.createAccount(playerUuid, "TestPlayer");

            boolean result = currencyManager.withdraw(playerUuid, 200.0);

            assertFalse(result, "餘額不足應該提款失敗");
            assertEquals(100.0, currencyManager.getBalance(playerUuid), 0.001, "餘額應保持不變");
        }

        @Test
        @DisplayName("測試負數存款")
        void testNegativeDeposit() {
            currencyManager.createAccount(playerUuid, "TestPlayer");

            boolean result = currencyManager.deposit(playerUuid, -50.0);

            assertFalse(result, "不能存入負數金額");
        }

        @Test
        @DisplayName("測試負數提款")
        void testNegativeWithdraw() {
            currencyManager.createAccount(playerUuid, "TestPlayer");

            boolean result = currencyManager.withdraw(playerUuid, -10.0);

            assertFalse(result, "不能提款負數金額");
        }
    }

    // ==================== SSOT 架構測試 (Split-Brain 防護) ====================

    @Nested
    @DisplayName("SSOT 架構測試 (Split-Brain 防護)")
    class SsotArchitectureTests {

        @Test
        @DisplayName("currencyExists 應委託給 ConfigManager")
        void testCurrencyExists_DelegatesToConfigManager() {
            // Act
            boolean dollarExists = currencyManager.currencyExists("dollar");
            boolean tokenExists = currencyManager.currencyExists("token");
            boolean fakeExists = currencyManager.currencyExists("fake");

            // Assert
            assertTrue(dollarExists, "dollar 應該存在");
            assertTrue(tokenExists, "token 應該存在");
            assertFalse(fakeExists, "fake 不應該存在");

            // Verify delegation
            verify(configManager, atLeast(3)).getCurrencies();
        }

        @Test
        @DisplayName("getCurrency 應委託給 ConfigManager 並回傳正確貨幣")
        void testGetCurrency_DelegatesToConfigManager() {
            // Act
            Currency result = currencyManager.getCurrency("token");

            // Assert
            assertEquals(TOKEN, result, "應回傳 token 貨幣");
        }

        @Test
        @DisplayName("getCurrency 找不到時應回傳預設貨幣")
        void testGetCurrency_ReturnsDefaultWhenNotFound() {
            // Act
            Currency result = currencyManager.getCurrency("nonexistent");

            // Assert
            assertEquals(DOLLAR, result, "找不到時應回傳預設貨幣");
        }

        @Test
        @DisplayName("getDefaultCurrencyId 應委託給 ConfigManager")
        void testGetDefaultCurrencyId_DelegatesToConfigManager() {
            // Act
            String result = currencyManager.getDefaultCurrencyId();

            // Assert
            assertEquals("dollar", result, "預設貨幣 ID 應為 dollar");
        }

        @Test
        @DisplayName("getRegisteredCurrencies 應委託給 ConfigManager")
        void testGetRegisteredCurrencies_DelegatesToConfigManager() {
            // Act
            var result = currencyManager.getRegisteredCurrencies();

            // Assert
            assertEquals(2, result.size(), "應有 2 個貨幣");
            assertTrue(result.contains("dollar"), "應包含 dollar");
            assertTrue(result.contains("token"), "應包含 token");
        }
    }

    // ==================== 輸入淨化測試 (Ghost Character 防護) ====================

    @Nested
    @DisplayName("輸入淨化測試 (Ghost Character 防護)")
    class InputSanitizationTests {

        @Test
        @DisplayName("currencyExists 應處理前後空白 (CRITICAL)")
        void testCurrencyExists_TrimsWhitespace() {
            // Act & Assert
            assertTrue(currencyManager.currencyExists(" token "), "應忽略前後空白");
            assertTrue(currencyManager.currencyExists("token "), "應忽略尾部空白");
            assertTrue(currencyManager.currencyExists(" token"), "應忽略前置空白");
        }

        @Test
        @DisplayName("currencyExists 應忽略大小寫 (CRITICAL)")
        void testCurrencyExists_CaseInsensitive() {
            // Act & Assert
            assertTrue(currencyManager.currencyExists("TOKEN"), "應忽略大小寫 (全大寫)");
            assertTrue(currencyManager.currencyExists("Token"), "應忽略大小寫 (首字大寫)");
            assertTrue(currencyManager.currencyExists("tOkEn"), "應忽略大小寫 (混合)");
        }

        @Test
        @DisplayName("currencyExists 應同時處理空白和大小寫 (CRITICAL)")
        void testCurrencyExists_CombinedSanitization() {
            // Act & Assert
            assertTrue(currencyManager.currencyExists(" TOKEN "), "應同時處理空白和大小寫");
        }

        @Test
        @DisplayName("currencyExists 應拒絕 null")
        void testCurrencyExists_RejectsNull() {
            // Act & Assert
            assertFalse(currencyManager.currencyExists(null), "null 應回傳 false");
        }

        @Test
        @DisplayName("getCurrency 應處理空白和大小寫")
        void testGetCurrency_SanitizesInput() {
            // Act
            Currency result = currencyManager.getCurrency(" TOKEN ");

            // Assert
            assertEquals(TOKEN, result, "應透過淨化後的 ID 找到貨幣");
        }
    }

    // ==================== 併發安全測試 ====================

    @Nested
    @DisplayName("併發安全測試")
    class ConcurrencyTests {

        @Test
        @DisplayName("測試高併發存取安全性")
        void testConcurrency() throws InterruptedException {
            currencyManager.createAccount(playerUuid, "ConcurrencyPlayer");
            currencyManager.setBalance(playerUuid, 0.0);

            int threadCount = 10;
            int tasksPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount * tasksPerThread; i++) {
                executor.submit(() -> currencyManager.deposit(playerUuid, 1.0));
            }

            executor.shutdown();
            boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);

            assertTrue(finished, "所有併發任務應在 5 秒內完成");
            assertEquals(1000.0, currencyManager.getBalance(playerUuid), 0.001, "最終餘額應為 1000.0");
        }
    }

    // ==================== Fallback 測試 ====================

    @Nested
    @DisplayName("Fallback 行為測試")
    class FallbackTests {

        @Test
        @DisplayName("ConfigManager 為 null 時 currencyExists 應 fallback 到 dollar")
        void testCurrencyExists_FallbackWhenConfigManagerNull() {
            // Arrange - 建立沒有 ConfigManager 的 CurrencyManager
            CurrencyManager bareManager = new CurrencyManager(storageHandler, logger, 100.0);
            // 不呼叫 setConfigManager

            // Act & Assert
            assertTrue(bareManager.currencyExists("dollar"), "應 fallback 認可 dollar");
            assertTrue(bareManager.currencyExists("DOLLAR"), "應 fallback 認可 DOLLAR (忽略大小寫)");
            assertFalse(bareManager.currencyExists("token"), "沒有 ConfigManager 時 token 不應存在");
        }

        @Test
        @DisplayName("ConfigManager 為 null 時 getDefaultCurrencyId 應 fallback 到 dollar")
        void testGetDefaultCurrencyId_FallbackWhenConfigManagerNull() {
            // Arrange
            CurrencyManager bareManager = new CurrencyManager(storageHandler, logger, 100.0);

            // Act
            String result = bareManager.getDefaultCurrencyId();

            // Assert
            assertEquals("dollar", result, "沒有 ConfigManager 時應 fallback 到 dollar");
        }
    }
}
