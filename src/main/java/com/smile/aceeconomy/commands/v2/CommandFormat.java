package com.smile.aceeconomy.commands.v2;

import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import java.util.Map;

/** Formats an amount for display using the currency's symbol and display name, controlled by language file. */
public final class CommandFormat {

    private CommandFormat() {
    }

    public static String formatAmount(CurrencyInfo currency, Amount amount) {
        return formatAmount(null, currency, amount);
    }

    public static String formatAmount(ConfigLangAdapter messages, CurrencyInfo currency, Amount amount) {
        if (messages != null) {
            String rendered = messages.plainMessage("command.amount-format",
                    Map.of("symbol", currency.symbol(),
                           "amount", amount.value().toPlainString(),
                           "currency", currency.displayName()));
            if (rendered != null && !rendered.isBlank() && !rendered.startsWith("Missing translation")) {
                return rendered;
            }
        }
        return currency.symbol() + amount.value().toPlainString() + " " + currency.displayName();
    }
}
