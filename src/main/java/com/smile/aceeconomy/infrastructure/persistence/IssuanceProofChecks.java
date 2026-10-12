package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.AmountCap;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;

import java.util.Objects;
import java.util.UUID;

/**
 * Shared, storage-agnostic proof checks for the atomic banknote issuance
 * boundary. Both durable backends (JSON, SQL) and the in-memory reference run
 * exactly these checks inside their single-transaction scope, so a forged,
 * reused or mismatched proof is refused identically everywhere with no money
 * movement.
 *
 * <p>A debit proof is never an arbitrary string: authority is the live stored
 * issuance plus the durable debit transaction bound to the nonce (type,
 * account, currency, amount, reason binding and authoritative before/after).
 * One debit transaction id backs at most one nonce. All checks are pure and
 * throw nothing; a non-null return names the refusal for the caller's typed
 * result.</p>
 */
public final class IssuanceProofChecks {

    private IssuanceProofChecks() {
    }

    /**
     * Check the caller-presented withdrawal against the immutable stored
     * payload for {@code nonce}.
     *
     * @return null when the binding holds; a fixed diagnosis otherwise
     */
    public static String checkDebitBinding(BanknoteIssuance stored, Transaction prepared,
                                           UUID nonce) {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(nonce, "nonce");
        if (prepared.type() != TransactionType.WITHDRAW) {
            return "debit type must be WITHDRAW";
        }
        if (!BanknoteIssuanceReasons.isIssueReasonFor(prepared.reason(), nonce)) {
            return "debit reason does not bind nonce";
        }
        if (prepared.amount() == null || !prepared.amount().equals(stored.amount())) {
            return "debit amount differs from the immutable issuance payload";
        }
        if (!Objects.equals(normalize(prepared.accountId()), normalize(stored.accountId()))) {
            return "debit account differs from the immutable issuance payload";
        }
        if (!normalizeCurrency(prepared.currencyId()).equals(normalizeCurrency(stored.currencyId()))) {
            return "debit currency differs from the immutable issuance payload";
        }
        return null;
    }

    /**
     * Check the caller-presented recovery refund against the immutable stored
     * payload for {@code nonce}, including the fixed system operator.
     *
     * @return null when the binding holds; a fixed diagnosis otherwise
     */
    public static String checkRefundBinding(BanknoteIssuance stored, Transaction prepared,
                                            UUID nonce) {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(nonce, "nonce");
        if (prepared.type() != TransactionType.DEPOSIT) {
            return "refund type must be DEPOSIT";
        }
        if (!BanknoteIssuanceReasons.isRecoveryRefundReasonFor(prepared.reason(), nonce)) {
            return "refund reason does not bind nonce";
        }
        if (prepared.operatorId() != null
                || !com.smile.aceeconomy.domain.BanknoteIssuanceReasons.SYSTEM_RECOVERY_OPERATOR
                        .equals(prepared.operatorName())) {
            return "refund operator must be the fixed system recovery identity";
        }
        if (prepared.amount() == null || !prepared.amount().equals(stored.amount())) {
            return "refund amount differs from the immutable issuance payload";
        }
        if (!Objects.equals(normalize(prepared.accountId()), normalize(stored.accountId()))) {
            return "refund account differs from the immutable issuance payload";
        }
        if (!normalizeCurrency(prepared.currencyId()).equals(normalizeCurrency(stored.currencyId()))) {
            return "refund currency differs from the immutable issuance payload";
        }
        return null;
    }

    /**
     * Check the durable debit transaction behind a stored issuance: it must
     * be the recorded debit id, a WITHDRAW bound to the nonce for the same
     * account, currency and amount, with an authoritative before/after that
     * proves the charge really left the account.
     *
     * @param stored the live stored issuance carrying the debit evidence
     * @param debit the durable debit transaction looked up by id, or null when absent
     * @param nonce the issuance nonce
     * @return null when the authority holds; a fixed diagnosis otherwise
     */
    public static String checkDebitAuthority(BanknoteIssuance stored, Transaction debit,
                                             UUID nonce) {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(nonce, "nonce");
        if (stored.debitTransactionId() == null || stored.debitProof() == null
                || stored.debitProof().isBlank()) {
            return "issuance carries no durable debit proof";
        }
        if (debit == null) {
            return "durable debit transaction is missing";
        }
        UUID expectedId;
        try {
            expectedId = UUID.fromString(stored.debitTransactionId());
        } catch (IllegalArgumentException e) {
            return "stored debit id is malformed";
        }
        if (!expectedId.equals(debit.id())) {
            return "durable debit id does not match the stored evidence";
        }
        if (debit.type() != TransactionType.WITHDRAW) {
            return "durable debit type must be WITHDRAW";
        }
        if (!BanknoteIssuanceReasons.isIssueReasonFor(debit.reason(), nonce)
                || !BanknoteIssuanceReasons.isIssueReasonFor(stored.debitProof(), nonce)) {
            return "durable debit reason does not bind nonce";
        }
        if (debit.amount() == null || !debit.amount().equals(stored.amount())) {
            return "durable debit amount differs from the issuance payload";
        }
        if (!Objects.equals(normalize(debit.accountId()), normalize(stored.accountId()))) {
            return "durable debit account differs from the issuance payload";
        }
        if (!normalizeCurrency(debit.currencyId()).equals(normalizeCurrency(stored.currencyId()))) {
            return "durable debit currency differs from the issuance payload";
        }
        if (debit.balanceBefore() == null || debit.balanceAfter() == null) {
            return "durable debit carries no authoritative balances";
        }
        Amount expectedAfter;
        try {
            expectedAfter = debit.balanceBefore().subtract(debit.amount());
        } catch (IllegalArgumentException scaleMismatch) {
            return "durable debit balances disagree in scale";
        }
        if (expectedAfter.compareTo(debit.balanceAfter()) != 0) {
            return "durable debit before/after does not prove the charge";
        }
        return null;
    }

    /**
     * Cross-field scale consistency for one atomic commit. Amount arithmetic
     * throws on mixed scales halfway through a commit, so any disagreement
     * refuses up front with no money movement. Registry-scale conformance is
     * enforced by the service pre-checks; the store enforces that the fields
     * it commits agree with each other.
     *
     * @return true when every non-null side shares one scale
     */
    public static boolean scalesAgree(Amount... sides) {
        Integer scale = null;
        for (Amount side : sides) {
            if (side == null) {
                continue;
            }
            if (scale == null) {
                scale = side.scale();
            } else if (side.scale() != scale) {
                return false;
            }
        }
        return true;
    }

    /**
     * Public cap over every balance the commit reads or writes: the stored
     * payload, the request and the live before/after.
     *
     * @return true when any non-null side exceeds the public cap
     */
    public static boolean capExceeded(Amount... sides) {
        for (Amount side : sides) {
            if (side != null && AmountCap.exceeds(side)) {
                return true;
            }
        }
        return false;
    }

    private static UUID normalize(UUID id) {
        return id;
    }

    private static String normalizeCurrency(String currencyId) {
        return currencyId == null ? "" : Currency.normalizeId(currencyId);
    }
}
