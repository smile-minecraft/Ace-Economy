package com.smile.aceeconomy.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable registry of known currencies, keyed by normalized id.
 *
 * <p>Constructors require exactly one currency to be marked as default. Insertion order is
 * preserved so config-declared currency order drives stable completion and listing output.</p>
 */
public final class CurrencyRegistry {

    private final Map<String, Currency> byNormalizedId;
    private final String defaultCurrencyId;

    private CurrencyRegistry(Map<String, Currency> currencies, String defaultCurrencyId) {
        // Keep the caller's insertion order (unlike Map.copyOf, which discards it) so
        // known-currency listings stay deterministic for a given config declaration order.
        this.byNormalizedId = Collections.unmodifiableMap(new LinkedHashMap<>(currencies));
        this.defaultCurrencyId = defaultCurrencyId;
    }

    public static CurrencyRegistry of(Collection<Currency> currencies) {
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException("at least one currency is required");
        }
        Map<String, Currency> map = new LinkedHashMap<>();
        String def = null;
        for (Currency c : currencies) {
            // Ids are already normalized by Currency.define; a plain put would silently
            // overwrite a same-normalized-id entry, so duplicates are rejected explicitly.
            if (map.put(c.id(), c) != null) {
                throw new IllegalArgumentException("duplicate normalized currency id: " + c.id());
            }
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
