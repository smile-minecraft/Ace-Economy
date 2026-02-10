package com.smile.aceeconomy.data;

import java.text.DecimalFormat;

/**
 * 貨幣資料物件。
 * <p>
 * 定義一種貨幣的屬性，包括 ID、名稱、符號、格式等。
 * </p>
 *
 * @author Smile
 */
public class Currency {
    private final String id;
    private final String name;
    private final String symbol;
    private final String format;
    private final boolean isDefault;
    private final DecimalFormat decimalFormat;

    /**
     * 建立貨幣資料物件。
     *
     * @param id        內部 ID (例如 "dollar", "token")
     * @param name      顯示名稱 (例如 "金幣", "代幣")
     * @param symbol    貨幣符號 (例如 "$", "ⓒ")
     * @param format    數字格式 (例如 "#,##0.00")
     * @param isDefault 是否為預設貨幣 (Vault 整合使用)
     */
    public Currency(String id, String name, String symbol, String format, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.symbol = symbol;
        this.format = format;
        this.isDefault = isDefault;
        this.decimalFormat = new DecimalFormat(symbol + format);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String symbol() {
        return symbol;
    }

    public String format() {
        return format;
    }

    public boolean isDefault() {
        return isDefault;
    }

    /**
     * 格式化金額。
     *
     * @param amount 金額
     * @return 格式化後的字串 (含符號)
     */
    public String format(double amount) {
        synchronized (decimalFormat) {
            return decimalFormat.format(amount);
        }
    }
}
