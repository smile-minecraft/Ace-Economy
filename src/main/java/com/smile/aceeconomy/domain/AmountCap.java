package com.smile.aceeconomy.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Public amount cap shared by every money entry: command parsing, the service
 * gates and the storage write guards all enforce this one limit.
 *
 * <p>The cap is 10^15 (1,000,000,000,000,000) of any currency, compared as a
 * plain numeric value so it applies identically at every currency scale:
 * {@code 1000000000000000.00} at scale 2 is exactly at the cap, while
 * {@code 1000000000000000.01} already exceeds it. The boundary is inclusive —
 * exactly the cap is a legal amount.</p>
 *
 * <p>Why 10^15: it carries over the long-standing command-entry limit, so every
 * entry now enforces one shared value instead of two coincidentally equal ones.
 * Its plain form is 16 digits (19 chars at scale 2, 25 at scale 8 — far below
 * the {@code VARCHAR(64)} amount column, which holds the cap up to scale 46
 * even with a minus sign). Comparison is always on exact decimals
 * ({@link BigDecimal}), never via {@code double}, so fractional units near the
 * cap stay exact.</p>
 *
 * <p>This class is pure and never throws on amount input: callers decide the
 * refusal type (typed service failure, typed command error, or storage
 * exception) from the returned diagnosis.</p>
 */
public final class AmountCap {

    /** The public cap: 10^15 of any currency, boundary inclusive. */
    public static final BigDecimal MAX = new BigDecimal("1000000000000000");

    /**
     * Width of the SQL amount columns ({@code VARCHAR(64)}). A plain decimal
     * string longer than this cannot be stored; the write guards refuse it
     * with a diagnosis instead of letting the database truncate it.
     */
    public static final int MAX_COLUMN_CHARS = 64;

    private AmountCap() {
    }

    /** The public cap value. */
    public static BigDecimal max() {
        return MAX;
    }

    /** True when the absolute numeric value is above the cap. */
    public static boolean exceeds(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        return value.abs().compareTo(MAX) > 0;
    }

    /** True when the amount's absolute numeric value is above the cap. */
    public static boolean exceeds(Amount amount) {
        Objects.requireNonNull(amount, "amount");
        return exceeds(amount.value());
    }

    /**
     * Admin-readable refusal for a requested amount above the cap. Names the
     * currency, the rejected value and the cap, and promises no mutation.
     */
    public static String describeRequest(String currencyId, Amount amount) {
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(amount, "amount");
        return "currency '" + currencyId + "': amount " + amount.value().toPlainString()
                + " exceeds maximum " + MAX.toPlainString()
                + " — transaction refused, nothing changed";
    }

    /**
     * Admin-readable refusal for a stored balance above the cap. Blames the
     * stored data (legacy row or hand edit), never the request, so the admin
     * knows to fix the data instead of retrying.
     */
    public static String describeStored(String currencyId, Amount stored) {
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(stored, "stored");
        return "currency '" + currencyId + "': stored balance " + stored.value().toPlainString()
                + " exceeds maximum " + MAX.toPlainString()
                + " — transaction refused, nothing changed;"
                + " fix the stored data instead of retrying";
    }

    /**
     * Admin-readable refusal for a resulting balance that would land above the
     * cap (a deposit or credit onto a near-cap balance).
     */
    public static String describeResult(String currencyId, Amount after) {
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(after, "after");
        return "currency '" + currencyId + "': resulting balance " + after.value().toPlainString()
                + " would exceed maximum " + MAX.toPlainString()
                + " — transaction refused, nothing changed";
    }

    /** True when the plain decimal string fits the amount column width. */
    public static boolean fitsColumn(String plainAmount) {
        Objects.requireNonNull(plainAmount, "plainAmount");
        return plainAmount.length() <= MAX_COLUMN_CHARS;
    }

    /**
     * Storage-entry check for a plain decimal amount string: refused when the
     * numeric value exceeds the cap or the text would not fit the amount
     * column. The raw text is abbreviated in the diagnosis so a hostile row
     * cannot flood the logs.
     */
    public static Optional<String> storableRefusal(String plainAmount) {
        Objects.requireNonNull(plainAmount, "plainAmount");
        BigDecimal numeric;
        try {
            numeric = new BigDecimal(plainAmount);
        } catch (NumberFormatException e) {
            return Optional.of("amount '" + abbreviate(plainAmount) + "' is not a decimal string"
                    + " — write refused, nothing changed");
        }
        if (exceeds(numeric)) {
            return Optional.of("amount " + abbreviate(numeric.toPlainString())
                    + " exceeds maximum " + MAX.toPlainString()
                    + " — write refused, nothing changed");
        }
        if (!fitsColumn(plainAmount)) {
            return Optional.of("amount text (" + plainAmount.length() + " chars) exceeds column width "
                    + MAX_COLUMN_CHARS + " — write refused, nothing changed;"
                    + " widen the currency scale down or migrate the column");
        }
        return Optional.empty();
    }

    private static String abbreviate(String value) {
        String sanitized = value.replaceAll("[\\p{Cntrl}]", "?");
        return sanitized.length() <= 48 ? sanitized : sanitized.substring(0, 45) + "...";
    }

    /**
     * Restore-time gate for one snapshot account's raw balance strings. Returns a
     * diagnosis when any balance exceeds the cap in magnitude or would not fit
     * the amount column; empty means the balances may be stored.
     *
     * <p>Only owed balances are checked: audit rows in the same snapshot stay
     * restorable as evidence, and plain reads stay lenient (a legacy over-cap
     * row still loads — the service gate diagnoses it on the next transaction).
     * Strings that are not parseable decimals are skipped here so the existing
     * record validation keeps reporting them with its own error.</p>
     */
    public static Optional<String> restoreRefusal(String ownerKey, Map<String, String> balances) {
        Objects.requireNonNull(ownerKey, "ownerKey");
        Objects.requireNonNull(balances, "balances");
        for (Map.Entry<String, String> e : balances.entrySet()) {
            String raw = e.getValue();
            if (raw == null) {
                continue;
            }
            BigDecimal numeric;
            try {
                numeric = new BigDecimal(raw);
            } catch (NumberFormatException notDecimal) {
                continue;
            }
            if (exceeds(numeric)) {
                return Optional.of("snapshot account " + ownerKey + " currency '" + e.getKey()
                        + "': balance " + abbreviate(numeric.toPlainString())
                        + " exceeds maximum " + MAX.toPlainString()
                        + " — restore refused, nothing changed");
            }
            if (!fitsColumn(raw)) {
                return Optional.of("snapshot account " + ownerKey + " currency '" + e.getKey()
                        + "': balance text (" + raw.length() + " chars) exceeds column width "
                        + MAX_COLUMN_CHARS + " — restore refused, nothing changed");
            }
        }
        return Optional.empty();
    }
}
