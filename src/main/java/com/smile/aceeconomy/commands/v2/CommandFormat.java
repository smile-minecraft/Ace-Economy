package com.smile.aceeconomy.commands.v2;

import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.domain.Amount;

/** Formats an amount for display using the currency's symbol and display name. */
public final class CommandFormat {

    private CommandFormat() {
    }

    public static String formatAmount(CurrencyInfo currency, Amount amount) {
        return currency.symbol() + amount.value().toPlainString() + " " + currency.displayName();
    }
}
