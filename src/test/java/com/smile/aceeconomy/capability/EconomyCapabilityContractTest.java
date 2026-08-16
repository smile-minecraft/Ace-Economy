package com.smile.aceeconomy.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [TEST:P2] v2 核心經濟能力的契約測試。
 *
 * <p>這些測試只依賴 {@link EconomyCapability} 契約與測試專用的
 * {@link V1CurrencyManagerAdapter}；不指名任何 v1 實作類別
 * （CurrencyManager、ConfigManager 等）。adapter 是唯一的接縫（seam），
 * 把 v1 行為對應到契約；v2 重寫時只需替換 adapter，本測試保持有效。
 * 這正是「clean-slate、不鎖死 v1 class 名稱」的實務意涵。</p>
 */
class EconomyCapabilityContractTest {

    private final EconomyCapability eco = V1CurrencyManagerAdapter.createDebtDisabled();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    // ==================== Balance & account lifecycle ====================

    @Test
    @DisplayName("新帳戶以起始餘額初始化")
    void testAccountStartsAtStartBalance() {
        eco.createAccount(alice, "Alice");
        assertEquals(1000.0, eco.getBalance(alice), 0.001, "新帳戶餘額應為起始餘額 1000");
    }

    @Test
    @DisplayName("未建立帳戶時餘額為 0")
    void testNoAccountBalanceIsZero() {
        assertEquals(0.0, eco.getBalance(bob), 0.001, "無帳戶餘額應為 0");
        assertFalse(eco.hasAccount(bob), "bob 不應有帳戶");
    }

    // ==================== Deposit / Withdraw transactions ====================

    @Test
    @DisplayName("存款增加餘額")
    void testDepositIncreasesBalance() {
        eco.createAccount(alice, "Alice");
        assertTrue(eco.deposit(alice, 250.0), "存款應成功");
        assertEquals(1250.0, eco.getBalance(alice), 0.001);
    }

    @Test
    @DisplayName("負數存款被拒絕 (交易取消)")
    void testNegativeDepositRejected() {
        eco.createAccount(alice, "Alice");
        assertFalse(eco.deposit(alice, -50.0), "負數存款必須被拒絕");
        assertEquals(1000.0, eco.getBalance(alice), 0.001, "餘額不變");
    }

    @Test
    @DisplayName("提款減少餘額")
    void testWithdrawDecreasesBalance() {
        eco.createAccount(alice, "Alice");
        assertTrue(eco.withdraw(alice, 400.0), "提款應成功");
        assertEquals(600.0, eco.getBalance(alice), 0.001);
    }

    @Test
    @DisplayName("負數提款被拒絕 (交易取消)")
    void testNegativeWithdrawRejected() {
        eco.createAccount(alice, "Alice");
        assertFalse(eco.withdraw(alice, -10.0), "負數提款必須被拒絕");
        assertEquals(1000.0, eco.getBalance(alice), 0.001);
    }

    // ==================== Limit / Cancel (debt disabled) ====================

    @Nested
    @DisplayName("限制與取消 (債務關閉)")
    class DebtDisabledLimitTests {

        @Test
        @DisplayName("餘額不足時提款拋出例外且餘額不變 (交易取消)")
        void testWithdrawInsufficientThrows() {
            eco.createAccount(alice, "Alice");
            assertThrows(EconomyCapability.InsufficientFundsException.class,
                    () -> eco.withdraw(alice, 2000.0), "餘額不足必須取消交易");
            assertEquals(1000.0, eco.getBalance(alice), 0.001, "餘額必須保持不變");
        }

        @Test
        @DisplayName("債務關閉時餘額不得低於 0")
        void testNoNegativeBalance() {
            assertFalse(eco.isNegativeBalanceAllowed(), "此配置下債務應關閉");
            eco.createAccount(alice, "Alice");
            assertFalse(eco.setBalance(alice, -1.0), "不得設定負餘額");
            assertEquals(1000.0, eco.getBalance(alice), 0.001);
        }
    }

    // ==================== Limit / Cancel (debt enabled, bounded) ====================

    @Nested
    @DisplayName("限制與取消 (債務開啟，有上限)")
    class DebtEnabledLimitTests {

        @Test
        @DisplayName("債務開啟時允許負餘額但不超過上限")
        void testDebtBoundedByLimit() {
            try (V1CurrencyManagerAdapter debtEco = V1CurrencyManagerAdapter.createDebtEnabled(500.0)) {
                assertTrue(debtEco.isNegativeBalanceAllowed(), "此配置下債務應開啟");
                debtEco.createAccount(alice, "Alice");
                // 起始 1000，提款 1200 -> 餘額 -200，仍在 500 上限內
                assertTrue(debtEco.withdraw(alice, 1200.0), "在債務上限內應成功");
                assertEquals(-200.0, debtEco.getBalance(alice), 0.001);
            }
        }

        @Test
        @DisplayName("超過債務上限的提款被取消")
        void testDebtLimitEnforced() {
            try (V1CurrencyManagerAdapter debtEco = V1CurrencyManagerAdapter.createDebtEnabled(500.0)) {
                debtEco.createAccount(alice, "Alice");
                assertThrows(EconomyCapability.InsufficientFundsException.class,
                        () -> debtEco.withdraw(alice, 2000.0),
                        "超過債務上限必須取消交易");
                assertEquals(1000.0, debtEco.getBalance(alice), 0.001, "餘額不變");
            }
        }
    }

    // ==================== Currency surface ====================

    @Test
    @DisplayName("貨幣存在性檢查 (大小寫/空白不敏感)")
    void testCurrencyExists() {
        assertTrue(eco.currencyExists("dollar"));
        assertTrue(eco.currencyExists("DOLLAR"));
        assertTrue(eco.currencyExists(" token "));
        assertFalse(eco.currencyExists("ghost"));
        assertEquals("dollar", eco.getDefaultCurrencyId());
    }
}
