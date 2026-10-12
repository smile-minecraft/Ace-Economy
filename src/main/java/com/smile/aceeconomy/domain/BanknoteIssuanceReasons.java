package com.smile.aceeconomy.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable reason bindings for durable banknote issuance.
 *
 * <p>Phase-1 skeleton: only the string shapes are decided here so tests and
 * the atomic store speak the same binding. Phase 2 enforces them inside the
 * single storage transaction; no caller-supplied string is ever trusted.</p>
 *
 * <ul>
 *   <li>Debit (player withdraw) reason is {@code banknote:issue:&lt;nonce&gt;},
 *       type {@code WITHDRAW}, bound to one issuance nonce.</li>
 *   <li>Recovery refund reason is {@code recovery:refund-void:&lt;nonce&gt;},
 *       type {@code DEPOSIT}, operator {@code SYSTEM_RECOVERY}, bound to the
 *       same nonce the debit bound.</li>
 * </ul>
 *
 * <p>Audit structure is unchanged: the binding travels in the existing
 * {@link Transaction#reason()} text, never in a new column. A debit proof is
 * therefore not an arbitrary string: the store looks up the durable debit
 * transaction by id and checks type, account, currency, amount, reason binding
 * and authoritative before/after. One debit transaction id can back at most
 * one nonce; reusing it for a second nonce is {@code INCONSISTENT_PROOF}.</p>
 */
public final class BanknoteIssuanceReasons {

    /** Prefix of every issuance debit reason, followed by the canonical nonce. */
    public static final String ISSUE_PREFIX = "banknote:issue:";

    /** Prefix of every recovery void+refund reason, followed by the canonical nonce. */
    public static final String RECOVERY_REFUND_PREFIX = "recovery:refund-void:";

    /** Operator name stored on every recovery refund transaction. */
    public static final String SYSTEM_RECOVERY_OPERATOR = "SYSTEM_RECOVERY";

    private BanknoteIssuanceReasons() {
    }

    /**
     * @return the exact debit reason for {@code nonce}; never null
     */
    public static String issueReason(UUID nonce) {
        Objects.requireNonNull(nonce, "nonce");
        return ISSUE_PREFIX + nonce;
    }

    /**
     * @return the exact recovery refund reason for {@code nonce}; never null
     */
    public static String recoveryRefundReason(UUID nonce) {
        Objects.requireNonNull(nonce, "nonce");
        return RECOVERY_REFUND_PREFIX + nonce;
    }

    /**
     * @return true when {@code reason} is exactly the debit binding for {@code nonce}
     */
    public static boolean isIssueReasonFor(String reason, UUID nonce) {
        if (reason == null || nonce == null) {
            return false;
        }
        return reason.equals(issueReason(nonce));
    }

    /**
     * @return true when {@code reason} is exactly the recovery binding for {@code nonce}
     */
    public static boolean isRecoveryRefundReasonFor(String reason, UUID nonce) {
        if (reason == null || nonce == null) {
            return false;
        }
        return reason.equals(recoveryRefundReason(nonce));
    }
}
