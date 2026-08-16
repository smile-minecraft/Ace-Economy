package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;

/**
 * Resolves the optional currency argument to a {@link CurrencyInfo}, falling back to the default
 * currency when the argument is absent. Throws a typed {@code ACELIB-CMD-UNKNOWN-CURRENCY}
 * {@link CommandException} when the supplied currency is unknown.
 */
public final class CurrencyArgResolver {

    private CurrencyArgResolver() {
    }

    public static CurrencyInfo resolve(EconomyCommandService economy, String rawCurrency, String defaultId) {
        if (rawCurrency == null || rawCurrency.isBlank()) {
            return economy.resolveCurrency(defaultId).orElseThrow(() ->
                    CommandException.custom("ACELIB-CMD-UNKNOWN-CURRENCY", "default currency is not configured"));
        }
        String id = rawCurrency.trim().toLowerCase();
        return economy.resolveCurrency(id).orElseThrow(() ->
                CommandException.custom("ACELIB-CMD-UNKNOWN-CURRENCY", "unknown currency: " + id));
    }
}
