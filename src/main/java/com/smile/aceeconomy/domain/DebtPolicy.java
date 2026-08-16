package com.smile.aceeconomy.domain;

/**
 * Debt / negative-balance policy.
 *
 * <ul>
 *   <li>{@link #disabled()} — balance can never drop below zero.</li>
 *   <li>{@link #enabled(Amount)} — balance may be negative but is bounded by the debt limit.</li>
 * </ul>
 *
 * <p>{@link #allows(Amount)} compares by numeric value (ignoring scale) so it is safe across
 * currencies with different scales.</p>
 */
public final class DebtPolicy {

    private final boolean allowNegative;
    private final Amount debtLimit; // zero when disabled

    private DebtPolicy(boolean allowNegative, Amount debtLimit) {
        this.allowNegative = allowNegative;
        this.debtLimit = debtLimit;
    }

    public static DebtPolicy disabled() {
        return new DebtPolicy(false, Amount.zero(0));
    }

    public static DebtPolicy enabled(Amount debtLimit) {
        if (debtLimit == null || debtLimit.isNegative()) {
            throw new IllegalArgumentException("debt limit must be non-negative");
        }
        return new DebtPolicy(true, debtLimit);
    }

    /** Whether the given resulting balance is permitted by this policy. */
    public boolean allows(Amount resultingBalance) {
        if (resultingBalance.isNonNegative()) {
            return true;
        }
        if (!allowNegative) {
            return false;
        }
        // resultingBalance >= -debtLimit  (compared by numeric value, scale-independent)
        return resultingBalance.value().compareTo(debtLimit.negate().value()) >= 0;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public Amount debtLimit() {
        return debtLimit;
    }
}
