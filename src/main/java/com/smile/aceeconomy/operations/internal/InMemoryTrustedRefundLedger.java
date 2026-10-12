package com.smile.aceeconomy.operations.internal;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory single-server {@link TrustedRefundLedger}.
 *
 * <p>Each nonce moves through one claim lifecycle only: {@code AUTHORIZED} (a
 * proven deduction waiting for its refund: an observably failed mint, or an
 * uncertain handover waiting for its atomic void+refund) to {@code CLAIMED} (one refund in
 * flight) to exactly one terminal state — {@code SPENT} after a committed
 * refund, or {@code UNCERTAIN} after an outcome that was never proven
 * uncommitted. A nonce is remembered forever: authorizing a spent, uncertain,
 * claimed, or already-authorized nonce throws, and so does authorizing it
 * with different parameters, so a nonce can never be rebuilt into a second
 * claim. Concurrent claims on the same authorization admit at most one. The
 * only way back to {@code AUTHORIZED} is {@link #releaseClaim}, which the
 * refund path calls solely after a definitely-uncommitted storage failure,
 * with the exact same four fields; anything tampered is refused and the claim
 * stays claimed.
 *
 * <p>Lifecycle is single-server in memory: a restart clears pending
 * authorizations fail-closed (no double spend). The registry never grants a
 * general deposit bypass and is never exposed on the public
 * {@code EconomyApi}; issuance rights travel through the internal
 * Bootstrap/flow wiring, not through any public factory.
 */
public final class InMemoryTrustedRefundLedger implements TrustedRefundLedger {

    private enum ClaimState {
        AUTHORIZED,
        CLAIMED,
        SPENT,
        UNCERTAIN
    }

    private record Entry(UUID accountId, String currencyId, int scale, String plainAmount,
                         TrustedRefundLedger.RefundPurpose purpose, ClaimState state) {

        boolean matches(UUID accountId, String currencyId, Amount amount,
                        TrustedRefundLedger.RefundPurpose purpose) {
            if (this.purpose != purpose) {
                return false;
            }
            if (!this.accountId.equals(accountId)) {
                return false;
            }
            if (!this.currencyId.equals(Currency.normalizeId(currencyId))) {
                return false;
            }
            BigDecimal value = amount.value();
            return this.scale == amount.scale()
                    && this.plainAmount.equals(value.stripTrailingZeros().toPlainString());
        }
    }

    private final ConcurrentHashMap<UUID, Entry> claims = new ConcurrentHashMap<>();

    private static Entry authorizedEntry(UUID accountId, String currencyId, Amount amount,
                                         TrustedRefundLedger.RefundPurpose purpose) {
        BigDecimal value = amount.value();
        return new Entry(accountId, Currency.normalizeId(currencyId), amount.scale(),
                value.stripTrailingZeros().toPlainString(), purpose, ClaimState.AUTHORIZED);
    }

    @Override
    public void authorize(UUID nonce, UUID accountId, String currencyId, Amount amount,
                          TrustedRefundLedger.RefundPurpose purpose) {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(purpose, "purpose");
        AtomicReference<IllegalStateException> refused = new AtomicReference<>();
        claims.compute(nonce, (key, existing) -> {
            if (existing != null) {
                refused.set(new IllegalStateException(
                        "refund nonce is already " + existing.state.name().toLowerCase()
                                + "; a nonce authorizes at most once and is never rebuilt"));
                return existing;
            }
            return authorizedEntry(accountId, currencyId, amount, purpose);
        });
        if (refused.get() != null) {
            throw refused.get();
        }
    }

    @Override
    public boolean tryConsume(UUID nonce, UUID accountId, String currencyId, Amount amount,
                              TrustedRefundLedger.RefundPurpose purpose) {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(purpose, "purpose");
        AtomicBoolean granted = new AtomicBoolean(false);
        claims.compute(nonce, (key, existing) -> {
            if (existing == null || existing.state != ClaimState.AUTHORIZED
                    || !existing.matches(accountId, currencyId, amount, purpose)) {
                return existing;
            }
            granted.set(true);
            return new Entry(existing.accountId, existing.currencyId, existing.scale,
                    existing.plainAmount, existing.purpose, ClaimState.CLAIMED);
        });
        return granted.get();
    }

    @Override
    public void releaseClaim(UUID nonce, UUID accountId, String currencyId, Amount amount,
                             TrustedRefundLedger.RefundPurpose purpose) {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(purpose, "purpose");
        AtomicReference<IllegalStateException> refused = new AtomicReference<>();
        claims.compute(nonce, (key, existing) -> {
            if (existing == null || existing.state != ClaimState.CLAIMED
                    || !existing.matches(accountId, currencyId, amount, purpose)) {
                refused.set(new IllegalStateException(
                        "refund claim cannot be released: no matching claimed authorization"));
                return existing;
            }
            return new Entry(existing.accountId, existing.currencyId, existing.scale,
                    existing.plainAmount, existing.purpose, ClaimState.AUTHORIZED);
        });
        if (refused.get() != null) {
            throw refused.get();
        }
    }

    @Override
    public void markRefunded(UUID nonce) {
        Objects.requireNonNull(nonce, "nonce");
        settle(nonce, ClaimState.SPENT, "refunded");
    }

    @Override
    public void markUncertain(UUID nonce) {
        Objects.requireNonNull(nonce, "nonce");
        settle(nonce, ClaimState.UNCERTAIN, "uncertain");
    }

    private void settle(UUID nonce, ClaimState terminal, String what) {
        AtomicReference<IllegalStateException> refused = new AtomicReference<>();
        claims.compute(nonce, (key, existing) -> {
            if (existing == null || existing.state != ClaimState.CLAIMED) {
                refused.set(new IllegalStateException(
                        "refund claim cannot be settled as " + what
                                + ": no matching claimed authorization"));
                return existing;
            }
            return new Entry(existing.accountId, existing.currencyId, existing.scale,
                    existing.plainAmount, existing.purpose, terminal);
        });
        if (refused.get() != null) {
            throw refused.get();
        }
    }
}
