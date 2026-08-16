package com.smile.aceeconomy.infrastructure.item;

import com.smile.aceeconomy.ports.BanknoteClaim;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Outcome of {@link BanknoteValidator#validate(BanknoteClaim)}. On success carries the accepted
 * claim; on failure carries a stable reason code describing which boundary was violated.
 */
public final class ValidationResult {

    private enum Kind { SUCCESS, REJECTED }

    private final Kind kind;
    private final BanknoteClaim claim;
    private final String reasonCode;

    private ValidationResult(Kind kind, @Nullable BanknoteClaim claim, @Nullable String reasonCode) {
        this.kind = kind;
        this.claim = claim;
        this.reasonCode = reasonCode;
    }

    public static ValidationResult success(@NotNull BanknoteClaim claim) {
        return new ValidationResult(Kind.SUCCESS, claim, null);
    }

    public static ValidationResult rejected(@NotNull String reasonCode) {
        return new ValidationResult(Kind.REJECTED, null, reasonCode);
    }

    public boolean success() {
        return kind == Kind.SUCCESS;
    }

    public boolean rejected() {
        return kind == Kind.REJECTED;
    }

    public @NotNull BanknoteClaim claim() {
        if (claim == null) {
            throw new IllegalStateException("no claim for kind=" + kind);
        }
        return claim;
    }

    public @NotNull String reasonCode() {
        if (reasonCode == null) {
            throw new IllegalStateException("no reason for kind=" + kind);
        }
        return reasonCode;
    }

    @Override
    public String toString() {
        return "ValidationResult{" + kind + (reasonCode != null ? ", reason=" + reasonCode : "") + '}';
    }
}
