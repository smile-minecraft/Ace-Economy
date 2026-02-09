package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.storage.StorageHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * 測試貨幣管理器的核心邏輯與執行緒安全性。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class CurrencyManagerTest {

    @Mock
    private StorageHandler storageHandler;

    @Mock
    private Logger logger;

    // 我們不使用 @InjectMocks 自動注入，因為我們要手動控制建構子參數
    private CurrencyManager currencyManager;

    private UUID playerUuid;

    @BeforeEach
    void setUp() {
        // 設定 CurrencyManager 的初始狀態
        currencyManager = new CurrencyManager(storageHandler, logger, 100.0);
        playerUuid = UUID.randomUUID();
    }

    @Test
    @DisplayName("測試存款功能")
    void testDeposit() {
        // Arrange
        currencyManager.createAccount(playerUuid, "TestPlayer");

        // Act
        boolean result = currencyManager.deposit(playerUuid, 100.0);

        // Assert
        assertTrue(result, "存款應該成功");
        assertEquals(200.0, currencyManager.getBalance(playerUuid), 0.001, "餘額應為 200.0 (初始 100 + 存 100)");
    }

    @Test
    @DisplayName("測試提款成功")
    void testWithdrawSuccess() {
        // Arrange
        currencyManager.createAccount(playerUuid, "TestPlayer"); // 初始 100.0

        // Act
        boolean result = currencyManager.withdraw(playerUuid, 50.0);

        // Assert
        assertTrue(result, "提款應該成功");
        assertEquals(50.0, currencyManager.getBalance(playerUuid), 0.001, "餘額應剩餘 50.0");
    }

    @Test
    @DisplayName("測試提款失敗 (餘額不足)")
    void testWithdrawFail() {
        // Arrange
        currencyManager.createAccount(playerUuid, "TestPlayer"); // 初始 100.0

        // Act
        boolean result = currencyManager.withdraw(playerUuid, 200.0);

        // Assert
        assertFalse(result, "餘額不足應該提款失敗");
        assertEquals(100.0, currencyManager.getBalance(playerUuid), 0.001, "餘額應保持不變");
    }

    @Test
    @DisplayName("測試負數存款")
    void testNegativeDeposit() {
        // Arrange
        currencyManager.createAccount(playerUuid, "TestPlayer");

        // Act
        boolean result = currencyManager.deposit(playerUuid, -50.0);

        // Assert
        assertFalse(result, "不能存入負數金額");
    }

    @Test
    @DisplayName("測試負數提款")
    void testNegativeWithdraw() {
        // Arrange
        currencyManager.createAccount(playerUuid, "TestPlayer");

        // Act
        boolean result = currencyManager.withdraw(playerUuid, -10.0);

        // Assert
        assertFalse(result, "不能提款負數金額");
    }

    @Test
    @DisplayName("測試高併發存取安全性")
    void testConcurrency() throws InterruptedException {
        // Arrange
        currencyManager.createAccount(playerUuid, "ConcurrencyPlayer");
        // 重設餘額為 0 以方便計算
        currencyManager.setBalance(playerUuid, 0.0);

        int threadCount = 10;
        int tasksPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Act
        // 10 個執行緒，每個執行 100 次存款 1.0
        // 總共應該增加 1000.0
        for (int i = 0; i < threadCount * tasksPerThread; i++) {
            executor.submit(() -> {
                currencyManager.deposit(playerUuid, 1.0);
            });
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);

        // Assert
        assertTrue(finished, "所有併發任務應在 5 秒內完成");
        assertEquals(1000.0, currencyManager.getBalance(playerUuid), 0.001, "最終餘額應為 1000.0");
    }
}
