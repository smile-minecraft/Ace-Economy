package com.smile.aceeconomy.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Stored-scale compatibility gate for persisted balances.
 *
 * <p>Persistence backends load amounts with the scale found in storage, not the configured
 * scale. When the two disagree — a scale change without a data migration, or a hand-edited
 * row — any arithmetic between the stored balance and a freshly built amount would throw an
 * unchecked {@code IllegalArgumentException} halfway through the operation. Callers check
 * first and refuse the whole transaction with a currency-naming diagnosis instead, leaving
 * the stored data untouched.</p>
 *
 * <p>The check is pure and never throws: an unknown currency yields empty (the caller
 * reports {@code CURRENCY_NOT_FOUND} first), and either amount side may be absent (no
 * stored entry yet, or operations without a request amount).</p>
 */
public final class CurrencyScaleCheck {

    /**
     * A refused scale diagnosis. The side that disagrees is recorded; when both disagree
     * the stored side is reported first because it is the one needing migration.
     */
    public record Mismatch(String currencyId, int configuredScale,
                           Integer storedScale, Integer requestScale) {
        public Mismatch {
            Objects.requireNonNull(currencyId, "currencyId");
        }

        /** Operator-facing diagnosis naming the currency; safe to surface to logs and replies. */
        public String describe() {
            if (storedScale != null) {
                return "currency '" + currencyId + "': stored scale " + storedScale
                        + " != configured scale " + configuredScale
                        + "; a restart alone does not migrate stored balances,"
                        + " data migration required — transaction refused, nothing changed";
            }
            return "currency '" + currencyId + "': request scale " + requestScale
                    + " != configured scale " + configuredScale
                    + " — transaction refused, nothing changed";
        }
    }

    private CurrencyScaleCheck() {
    }

    public static Optional<Mismatch> check(CurrencyRegistry currencies, String currencyId,
                                           Amount stored, Amount requested) {
        Objects.requireNonNull(currencies, "currencies");
        if (!currencies.contains(currencyId)) {
            return Optional.empty();
        }
        Currency currency = currencies.get(currencyId);
        int configured = currency.scale();
        Integer storedScale =
                stored != null && stored.scale() != configured ? stored.scale() : null;
        Integer requestScale =
                requested != null && requested.scale() != configured ? requested.scale() : null;
        if (storedScale == null && requestScale == null) {
            return Optional.empty();
        }
        return Optional.of(new Mismatch(currency.id(), configured, storedScale, requestScale));
    }
}
