package com.smile.aceeconomy.infrastructure.integration.placeholder;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyResult;

import org.bukkit.OfflinePlayer;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Pure, vendor-free resolver for the v2 {@code aceeco} placeholder namespace.
 *
 * <p>Documented placeholder contract (all lower-case, evaluated against the default or a named
 * currency):</p>
 * <ul>
 *   <li>{@code %aceeco_balance%} — default-currency balance, raw (e.g. {@code 100.00}).</li>
 *   <li>{@code %aceeco_balance_formatted%} — default-currency balance with symbol (e.g. {@code $100.00}).</li>
 *   <li>{@code %aceeco_balance_<currency>%} — named-currency raw balance.</li>
 *   <li>{@code %aceeco_balance_<currency>_formatted%} — named-currency balance with symbol.</li>
 * </ul>
 *
 * <p>Fail-closed behavior: any unknown placeholder name, malformed currency id, unknown currency,
 * or unavailable account resolves to {@code null}. Returning {@code null} from PAPI leaves the
 * literal placeholder unexpanded rather than showing a wrong value.</p>
 */
public final class PlaceholderResolver {

    private static final Pattern CURRENCY_ID = Pattern.compile("[a-z0-9_]+");

    private final EconomyApi api;
    private final CurrencyRegistry currencies;

    public PlaceholderResolver(EconomyApi api, CurrencyRegistry currencies) {
        this.api = Objects.requireNonNull(api, "api");
        this.currencies = Objects.requireNonNull(currencies, "currencies");
    }

    /**
     * Resolve a placeholder. Returns {@code null} when the player is unavailable, the placeholder
     * name is unknown, the currency id is malformed/unknown, or the account is unavailable.
     */
    @Nullable
    public String resolve(@Nullable OfflinePlayer player, @Nullable String params) {
        if (player == null || params == null) {
            return null;
        }
        String p = params.toLowerCase(Locale.ROOT);
        String def = currencies.defaultCurrencyId();

        if (p.equals("balance")) {
            return rawBalance(player, def);
        }
        if (p.equals("balance_formatted")) {
            return formattedBalance(player, def);
        }
        if (p.startsWith("balance_")) {
            String rest = p.substring("balance_".length());
            boolean formatted = rest.endsWith("_formatted");
            String currencyId = formatted ? rest.substring(0, rest.length() - "_formatted".length()) : rest;
            if (currencyId.isEmpty() || !CURRENCY_ID.matcher(currencyId).matches()) {
                return null; // malformed currency id
            }
            if (!currencies.contains(currencyId)) {
                return null; // unknown currency
            }
            return formatted ? formattedBalance(player, currencyId) : rawBalance(player, currencyId);
        }
        return null; // unknown placeholder
    }

    @Nullable
    private String rawBalance(OfflinePlayer player, String currencyId) {
        EconomyResult<Amount> r = api.getBalance(player.getUniqueId(), currencyId);
        if (r.isFailure()) {
            return null; // unavailable account
        }
        return r.value().value().toPlainString();
    }

    @Nullable
    private String formattedBalance(OfflinePlayer player, String currencyId) {
        EconomyResult<Amount> r = api.getBalance(player.getUniqueId(), currencyId);
        if (r.isFailure()) {
            return null; // unavailable account
        }
        Currency c = currencies.get(currencyId);
        return c.symbol() + r.value().value().toPlainString();
    }
}
