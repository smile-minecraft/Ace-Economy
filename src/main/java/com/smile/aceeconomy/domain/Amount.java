package com.smile.aceeconomy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary amount backed by {@link BigDecimal}.
 *
 * <p>v2 numeric contract (durable):</p>
 * <ul>
 *   <li>Never {@code null}; never non-finite. {@code NaN}/{@code Infinity} are rejected at the
 *       entry point ({@link #of(double, int)}).</li>
 *   <li>Carries an explicit {@code scale} derived from its currency definition.</li>
 *   <li>Input whose fractional precision exceeds the currency scale is rejected
 *       (equivalent to {@link RoundingMode#UNNECESSARY}); no implicit rounding ever occurs.</li>
 *   <li>Arithmetic ({@code add}/{@code subtract}/{@code negate}/{@code abs}) is pure and
 *       preserves the scale.</li>
 * </ul>
 */
public final class Amount {

    private final BigDecimal value;
    private final int scale;

    private Amount(BigDecimal value, int scale) {
        this.value = value;
        this.scale = scale;
    }

    /** Reject null or non-finite double input. */
    public static Amount of(double value, int scale) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Amount must be finite, got: " + value);
        }
        return of(BigDecimal.valueOf(value), scale);
    }

    public static Amount of(long value, int scale) {
        return of(BigDecimal.valueOf(value), scale);
    }

    public static Amount of(BigDecimal value, int scale) {
        if (value == null) {
            throw new IllegalArgumentException("Amount value must not be null");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("scale must be >= 0, got: " + scale);
        }
        // Reject implicit rounding: the input must already fit the currency scale exactly.
        if (value.scale() > scale) {
            throw new IllegalArgumentException(
                    "Amount scale " + value.scale() + " exceeds currency scale " + scale
                            + " (no implicit rounding allowed)");
        }
        // Normalize to exactly the currency scale without rounding (UNNECESSARY equivalent).
        BigDecimal normalized = value.setScale(scale, RoundingMode.UNNECESSARY);
        return new Amount(normalized, scale);
    }

    public static Amount zero(int scale) {
        return new Amount(BigDecimal.ZERO.setScale(scale), scale);
    }

    public BigDecimal value() {
        return value;
    }

    public int scale() {
        return scale;
    }

    public Amount add(Amount other) {
        requireSameScale(other);
        return new Amount(value.add(other.value), scale);
    }

    public Amount subtract(Amount other) {
        requireSameScale(other);
        return new Amount(value.subtract(other.value), scale);
    }

    public Amount negate() {
        return new Amount(value.negate(), scale);
    }

    public Amount abs() {
        return value.signum() < 0 ? negate() : this;
    }

    public int compareTo(Amount other) {
        requireSameScale(other);
        return value.compareTo(other.value);
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isNonPositive() {
        return value.signum() <= 0;
    }

    public boolean isNonNegative() {
        return value.signum() >= 0;
    }

    private void requireSameScale(Amount other) {
        if (other.scale != this.scale) {
            throw new IllegalArgumentException(
                    "Amount scale mismatch: " + this.scale + " vs " + other.scale);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Amount)) {
            return false;
        }
        Amount that = (Amount) o;
        return this.scale == that.scale && this.value.compareTo(that.value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, scale);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
