package com.smile.aceeconomy.domain;

/**
 * Durable issuance progress for one banknote nonce.
 *
 * <p>Ordering on the happy path is {@code PREPARED} (durable record exists,
 * nothing deducted yet) {@code ->} {@code DEBIT_COMMITTED} (deduction and the
 * status flip committed together, carrying the debit proof) {@code ->}
 * {@code DELIVERY_STARTED} (marked durable before {@code addItem} runs)
 * {@code ->} {@code DELIVERED} (marked durable only after {@code addItem}
 * confirms). Terminal states are {@code DELIVERED}, {@code FINAL_VOID_REFUNDED},
 * {@code REDEEMED}, {@code ABORTED} and {@code QUARANTINED}.
 *
 * <p>{@code FINAL_VOID_REFUNDED} means the nonce was consumed (voided) and the
 * refund committed in the SAME storage transaction together with the issuance
 * final mark; {@code REDEEMED} means the note was redeemed through the existing
 * first-writer-wins redemption path; {@code ABORTED} means a {@code PREPARED}
 * record was confirmed deduction-free and closed with no money movement;
 * {@code QUARANTINED} means the record is kept for diagnosis with no money
 * movement (unknown, corrupt or proof-less state is isolated, never resolved
 * by inventing a receipt).
 *
 * <p>Old v2 notes issued before this record existed have no issuance row and
 * redeem exactly as before; the absence of a row never blocks redemption.
 */
public enum BanknoteIssuanceStatus {
    PREPARED,
    DEBIT_COMMITTED,
    DELIVERY_STARTED,
    DELIVERED,
    FINAL_VOID_REFUNDED,
    REDEEMED,
    ABORTED,
    QUARANTINED;

    /**
     * @return true once no further recovery action may change the outcome
     */
    public boolean isTerminal() {
        return switch (this) {
            case DELIVERED, FINAL_VOID_REFUNDED, REDEEMED, ABORTED, QUARANTINED -> true;
            case PREPARED, DEBIT_COMMITTED, DELIVERY_STARTED -> false;
        };
    }

    /**
     * @return true while the record may still need a recovery decision
     */
    public boolean isUnfinished() {
        return !isTerminal();
    }
}
