package com.smile.aceeconomy.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable registry of known currencies, keyed by normalized id.
 *
 * <p>Constructors require exactly one currency to be marked as default.</p>
 */
public final class CurrencyRegistry {

    private final Map<String, Currency> byNormalizedId;
    private final String defaultCurrencyId;

    private CurrencyRegistry(Map<String, Currency> currencies, String defaultCurrencyId) {
        this.byNormalizedId = Map.copyOf(currencies);
        this.defaultCurrencyId = defaultCurrencyId;
    }

    public static CurrencyRegistry of(Collection<Currency> currencies) {
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException("at least one currency is required");
        }
        Map<String, Currency> map = new LinkedHashMap<>();
        String def = null;
        for (Currency c : currencies) {
            map.put(c.id(), c);
            if (c.isDefault()) {
                if (def != null) {
                    throw new IllegalArgumentException("exactly one default currency is required");
                }
                def = c.id();
            }
        }
        if (def == null) {
            throw new IllegalArgumentException("exactly one default currency is required");
        }
        return new CurrencyRegistry(map, def);
    }

    public boolean contains(String currencyId) {
        return byNormalizedId.containsKey(Currency.normalizeId(currencyId));
    }

    public Currency get(String currencyId) {
        Currency c = byNormalizedId.get(Currency.normalizeId(currencyId));
        if (c == null) {
            throw new IllegalArgumentException("unknown currency: " + currencyId);
        }
        return c;
    }

    public Currency getDefault() {
        return byNormalizedId.get(defaultCurrencyId);
    }

    public String defaultCurrencyId() {
        return defaultCurrencyId;
    }

    public Collection<Currency> all() {
        return byNormalizedId.values();
    }
}
