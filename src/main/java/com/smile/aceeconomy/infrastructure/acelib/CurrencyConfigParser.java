package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the operator-owned {@code currencies} config section into a validated
 * {@link CurrencyRegistry}.
 *
 * <p>The section is a free-form map: any number of currencies beyond the shipped
 * {@code dollar}/{@code token} pair is legal as long as every entry satisfies the
 * contract below. The whole section is validated before any registry is constructed,
 * so a malformed config can never leave a partially applied state behind — the caller
 * receives either a complete registry or an exception.</p>
 *
 * <p>Per-currency contract (all four fields required, no silent fallbacks):</p>
 * <ul>
 *   <li>id: the map key; after trim + lower-case it must match {@code [a-z0-9_]+};
 *       normalized duplicates are rejected instead of silently overwriting.</li>
 *   <li>{@code name} / {@code symbol}: non-null strings.</li>
 *   <li>{@code scale}: a non-negative integral number (YAML integer node).</li>
 *   <li>{@code default}: a boolean; exactly one currency across the section is default.</li>
 * </ul>
 *
 * <p>Accepted input shapes: a Bukkit {@link ConfigurationSection} (what
 * {@code ConfigLangAdapter.getConfig("currencies")} returns) or a plain
 * {@code Map<String, Object>} (unit-test and tooling seam).</p>
 */
public final class CurrencyConfigParser {

    private CurrencyConfigParser() {
    }

    public static CurrencyRegistry parse(Object rawCurrencies) {
        Map<String, Object> entries = flattenMapping(rawCurrencies, "currencies");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "currencies must define at least one currency with name/symbol/scale/default");
        }
        List<Currency> parsed = new ArrayList<>(entries.size());
        Set<String> seenIds = new java.util.HashSet<>(entries.size());
        int defaultCount = 0;
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            String rawId = entry.getKey();
            String id = Currency.normalizeId(rawId);
            if (!id.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException(
                        "currency id must match [a-z0-9_]+ after trim/lower-case: " + rawId);
            }
            if (!seenIds.add(id)) {
                throw new IllegalArgumentException("duplicate normalized currency id: " + id);
            }
            Map<String, Object> fields = flattenMapping(entry.getValue(), "currencies." + id);
            String name = stringField(fields, "name", id);
            String symbol = stringField(fields, "symbol", id);
            int scale = scaleField(fields, id);
            boolean isDefault = booleanField(fields, "default", id);
            if (isDefault) {
                defaultCount++;
            }
            parsed.add(Currency.define(id, name, symbol, scale, isDefault));
        }
        if (defaultCount != 1) {
            throw new IllegalArgumentException(
                    "exactly one currency must set default: true (found " + defaultCount + ")");
        }
        return CurrencyRegistry.of(parsed);
    }

    /** Normalize a section-or-map node into a plain ordered map of direct children. */
    private static Map<String, Object> flattenMapping(Object raw, String what) {
        if (raw instanceof ConfigurationSection section) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                out.put(key, section.get(key));
            }
            return out;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(what + " keys must be text: " + entry.getKey());
                }
                out.put(key, entry.getValue());
            }
            return out;
        }
        throw new IllegalArgumentException(what + " must be a mapping");
    }

    private static String stringField(Map<String, Object> fields, String field, String id) {
        Object raw = require(fields, field, id);
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException(
                    "currencies." + id + "." + field + " must be text");
        }
        return value;
    }

    private static int scaleField(Map<String, Object> fields, String id) {
        Object raw = require(fields, "scale", id);
        // Only YAML integer nodes are accepted; "2" / 2.5 are type errors, not coerced values.
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(
                    "currencies." + id + ".scale must be a non-negative integer");
        }
        long scale = ((Number) raw).longValue();
        if (scale < 0 || scale > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "currencies." + id + ".scale must be a non-negative integer");
        }
        return (int) scale;
    }

    private static boolean booleanField(Map<String, Object> fields, String field, String id) {
        Object raw = require(fields, field, id);
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException(
                    "currencies." + id + "." + field + " must be true or false");
        }
        return value;
    }

    private static Object require(Map<String, Object> fields, String field, String id) {
        Object raw = fields.get(field);
        if (raw == null) {
            throw new IllegalArgumentException(
                    "currencies." + id + " is missing required field '" + field + "'");
        }
        return raw;
    }
}
