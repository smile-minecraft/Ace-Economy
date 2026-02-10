package com.smile.aceeconomy.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Currency 格式化邏輯單元測試。
 * <p>
 * 測試不同格式下的數字格式化功能。
 * </p>
 */
class CurrencyTest {

    @Test
    @DisplayName("測試 Decimal 格式化 (#,##0.00)")
    void testFormatDecimal() {
        Currency dollar = new Currency("dollar", "金幣", "$", "#,##0.00", true);

        String result = dollar.format(1234.567);

        // 預期結果: "$1,234.57" (四捨五入)
        assertEquals("$1,234.57", result, "應正確格式化為帶兩位小數的金額");
    }

    @Test
    @DisplayName("測試 Integer 格式化 (#,##0)")
    void testFormatInteger() {
        Currency token = new Currency("token", "代幣", "ⓒ", "#,##0", false);

        String result = token.format(100.9);

        // 預期結果: "ⓒ101" (四捨五入取整)
        assertEquals("ⓒ101", result, "應正確四捨五入為整數");
    }

    @Test
    @DisplayName("測試零值格式化")
    void testFormatZero() {
        Currency dollar = new Currency("dollar", "金幣", "$", "#,##0.00", true);

        String result = dollar.format(0);

        assertEquals("$0.00", result, "零值應格式化為 $0.00");
    }

    @Test
    @DisplayName("測試負數格式化")
    void testFormatNegative() {
        Currency dollar = new Currency("dollar", "金幣", "$", "#,##0.00", true);

        String result = dollar.format(-1500.50);

        // 負數格式化視 DecimalFormat 預設行為
        assertTrue(result.contains("1,500.50"), "應包含格式化的數字");
    }

    @Test
    @DisplayName("測試大數字格式化")
    void testFormatLargeNumber() {
        Currency dollar = new Currency("dollar", "金幣", "$", "#,##0.00", true);

        String result = dollar.format(1_000_000_000.00);

        assertEquals("$1,000,000,000.00", result, "大數字應正確加入千分位符號");
    }

    @Test
    @DisplayName("測試 Currency Record 的 isDefault 判斷")
    void testIsDefault() {
        Currency dollar = new Currency("dollar", "金幣", "$", "#,##0.00", true);
        Currency token = new Currency("token", "代幣", "ⓒ", "#,##0", false);

        assertTrue(dollar.isDefault(), "dollar 應為預設貨幣");
        assertFalse(token.isDefault(), "token 不應為預設貨幣");
    }
}
