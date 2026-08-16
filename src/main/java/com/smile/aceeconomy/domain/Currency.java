package com.smile.aceeconomy.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Currency definition. The {@code id} is stored normalized (trimmed + case-folded) so that
 * lookups are case- and whitespace-insensitive, matching the frozen capability baseline.
 */
public final class Currency {

    private final String id;          // normalized (trimmed + lower-cased)
    private final String displayName;
    private final String symbol;
    private final int scale;
    private final boolean isDefault;

    private Currency(String id, String displayName, String symbol, int scale, boolean isDefault) {
        this.id = id;
        this.displayName = displayName;
        this.symbol = symbol;
        this.scale = scale;
        this.isDefault = isDefault;
    }

    public static Currency define(String id, String displayName, String symbol, int scale, boolean isDefault) {
        String normalized = normalizeId(id);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("currency id must not be blank");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("currency scale must be >= 0");
        }
        return new Currency(normalized, displayName, symbol, scale, isDefault);
    }

    /** Trim + case-fold (lower-case). A null id is treated as empty. */
    public static String normalizeId(String id) {
        if (id == null) {
            return "";
        }
        return id.trim().toLowerCase();
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String symbol() {
        return symbol;
    }

    public int scale() {
        return scale;
    }

    public boolean isDefault() {
        return isDefault;
    }

    /** Build an {@link Amount} for this currency, rejecting over-scale / non-finite input. */
    public Amount amountOf(BigDecimal value) {
        return Amount.of(value, scale);
    }

    public Amount amountOf(double value) {
        return Amount.of(value, scale);
    }

    public Amount amountOf(long value) {
        return Amount.of(value, scale);
    }

    public Amount zero() {
        return Amount.zero(scale);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Currency)) {
            return false;
        }
        Currency that = (Currency) o;
        return scale == that.scale && isDefault == that.isDefault
                && id.equals(that.id) && Objects.equals(symbol, that.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, scale, isDefault, symbol);
    }

    @Override
    public String toString() {
        return "Currency{" + id + ",scale=" + scale + ",default=" + isDefault + "}";
    }
}
