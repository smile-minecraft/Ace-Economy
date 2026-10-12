package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.BanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;
import com.smile.aceeconomy.ports.persistence.IssuanceRefundResult;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Startup recovery over durable issuance records.
 *
 * <p>Decided recovery table (implemented below):</p>
 * <ul>
 *   <li>{@code PREPARED} with no deduction {@code ->} {@code ABORTED}, no refund.</li>
 *   <li>{@code DEBIT_COMMITTED} carrying a debit proof {@code ->} refund plus
 *       nonce consumption plus refund audit plus issuance final mark in ONE
 *       storage transaction {@code ->} {@code FINAL_VOID_REFUNDED}.</li>
 *   <li>{@code DELIVERY_STARTED} carrying a debit proof, nonce still
 *       unconsumed {@code ->} same atomic void+refund; nonce already consumed
 *       (the note was redeemed) {@code ->} {@code REDEEMED}, no refund, so the
 *       money is never paid twice.</li>
 *   <li>{@code DELIVERED} {@code ->} no action, never refunded.</li>
 *   <li>Unknown, corrupt, proof-less or otherwise undecidable {@code ->}
 *       {@code QUARANTINED} with a loud diagnosis, no money movement, never a
 *       fabricated receipt.</li>
 *   <li>Repeat runs (restart, manual trigger) and concurrent redeem-vs-recovery
 *       resolve first-writer-wins: exactly one refund or one redemption per
 *       nonce, value conserved.</li>
 * </ul>
 *
 * <p>Every scanned record lands in exactly one counted bucket, so
 * {@code scanned == refunded + aborted + quarantined} always holds. A failed
 * scan keeps the barrier closed with a loud diagnosis and untouched pending
 * rows; per-record storage failures quarantine that record with a diagnosis
 * instead of aborting the whole run. The barrier flips true once a run
 * finishes (even with quarantines). No pre-commit event fires for the
 * system-initiated refund, so no listener can veto compensation.</p>
 */
public final class BanknoteIssuanceRecovery {

    private static final Logger LOG = Logger.getLogger(BanknoteIssuanceRecovery.class.getName());

    /**
     * One recovery decision for a single issuance record.
     */
    public enum RecoveryDecision {
        NO_REFUND_NO_ACTION,
        ABORT_WITHOUT_REFUND,
        REFUND_AND_VOID,
        ALREADY_FINAL,
        QUARANTINE
    }

    /**
     * Counted outcome of one {@link #recoverAll()} run. Re-running over the
     * same store must yield the same totals with no second refund.
     */
    public record RecoveryReport(int scanned, int refunded, int aborted, int quarantined,
                                 List<String> diagnoses) {
        public RecoveryReport {
            diagnoses = diagnoses == null ? List.of() : List.copyOf(diagnoses);
        }
    }

    private final BanknoteIssuanceStore issuances;
    private final DebtPolicy debtPolicy;
    private volatile boolean complete = false;
    private volatile RecoveryReport lastReport =
            new RecoveryReport(0, 0, 0, 0, List.of());

    public BanknoteIssuanceRecovery(BanknoteIssuanceStore issuances) {
        this(issuances, DebtPolicy.disabled());
    }

    /**
     * Production wiring passes the shared debt policy so the atomic
     * void+refund commits under the same rule as every other money path.
     * Tests use the single-argument constructor (debt disabled).
     */
    public BanknoteIssuanceRecovery(BanknoteIssuanceStore issuances, DebtPolicy debtPolicy) {
        this.issuances = Objects.requireNonNull(issuances, "issuances");
        this.debtPolicy = Objects.requireNonNull(debtPolicy, "debtPolicy");
    }

    /**
     * Pure decision for one record; writes nothing. Terminal records are
     * already final; {@code PREPARED} without a deduction aborts; proven
     * pending deductions refund and void; anything undecidable quarantines.
     *
     * @return the decision for one record; never null
     */
    public RecoveryDecision decideFor(BanknoteIssuance issuance) {
        Objects.requireNonNull(issuance, "issuance");
        BanknoteIssuanceStatus status = issuance.status();
        if (status.isTerminal()) {
            return RecoveryDecision.ALREADY_FINAL;
        }
        return switch (status) {
            case PREPARED -> RecoveryDecision.ABORT_WITHOUT_REFUND;
            case DEBIT_COMMITTED, DELIVERY_STARTED -> issuance.isRefundablePending()
                    ? RecoveryDecision.REFUND_AND_VOID
                    : RecoveryDecision.QUARANTINE;
            default -> RecoveryDecision.QUARANTINE;
        };
    }

    /**
     * Scan {@link BanknoteIssuanceStore#listUnfinished()}, apply
     * {@link #decideFor(BanknoteIssuance)} per record with the atomic
     * void+refund commit where decided, mark the barrier complete, and return
     * the counted report. Never throws: storage failures become loud
     * diagnoses with the barrier closed (scan failure) or quarantines
     * (per-record failure), and pending rows are always retained.
     *
     * @return the counted outcome; never null
     */
    public synchronized RecoveryReport recoverAll() {
        List<BanknoteIssuance> pending;
        try {
            pending = issuances.listUnfinished();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE,
                    "Banknote issuance recovery scan failed; keeping every pending row"
                            + " and keeping new managed issuance blocked", e);
            RecoveryReport report = new RecoveryReport(0, 0, 0, 0,
                    List.of("recovery scan failed (" + e.getClass().getSimpleName()
                            + "); pending rows retained, new managed issuance stays blocked"));
            lastReport = report;
            return report;
        }
        int refunded = 0;
        int aborted = 0;
        int quarantined = 0;
        List<String> diagnoses = new ArrayList<>();
        for (BanknoteIssuance issuance : pending) {
            UUID nonce = issuance.nonce();
            try {
                switch (decideFor(issuance)) {
                    case ABORT_WITHOUT_REFUND -> {
                        issuances.markAborted(nonce,
                                "recovery: prepared without deduction; aborting with no refund");
                        aborted++;
                    }
                    case REFUND_AND_VOID -> {
                        switch (refundOne(issuance, diagnoses)) {
                            case REFUNDED -> refunded++;
                            case QUARANTINED -> quarantined++;
                        }
                    }
                    case QUARANTINE -> {
                        quarantinedMark(nonce,
                                "recovery: undecidable issuance state with no durable debit"
                                        + " proof; quarantined with no money movement",
                                diagnoses);
                        quarantined++;
                    }
                    default -> {
                        quarantined++;
                        String diagnosis = "recovery: unexpected terminal record in unfinished"
                                + " scan for nonce " + nonce + "; left untouched";
                        diagnoses.add(diagnosis);
                        LOG.log(Level.WARNING, diagnosis);
                    }
                }
            } catch (RuntimeException e) {
                String diagnosis = "recovery: storage failure on nonce " + nonce
                        + " (" + e.getClass().getSimpleName() + "); row retained for retry";
                diagnoses.add(diagnosis);
                LOG.log(Level.SEVERE, diagnosis, e);
                quarantined++;
                try {
                    issuances.markQuarantined(nonce,
                            "recovery: storage failure during recovery; quarantined for diagnosis");
                } catch (RuntimeException ignored) {
                    // The count and the diagnosis already stand; the row stays
                    // unfinished and a later run retries it.
                }
            }
        }
        int scanned = pending.size();
        RecoveryReport report =
                new RecoveryReport(scanned, refunded, aborted, quarantined, diagnoses);
        lastReport = report;
        complete = true;
        if (!diagnoses.isEmpty()) {
            LOG.log(Level.WARNING,
                    "Banknote issuance recovery finished with diagnoses: {0}", diagnoses);
        }
        return report;
    }

    /**
     * @return true once a startup recovery run has finished (even with
     *         quarantines); false while recovery never ran or its scan failed
     */
    public boolean isRecoveryComplete() {
        return complete;
    }

    /**
     * Counted progress of the last run for loud diagnostics.
     *
     * @return the counted outcome; never null
     */
    public RecoveryReport lastReport() {
        return lastReport;
    }

    /**
     * One void+refund disposition: either the value was refunded (committed
     * or replayed) or the record needs attention (quarantined or kept pending
     * with a loud diagnosis). Every scanned record lands in exactly one
     * counted bucket.
     */
    private enum RefundDisposition {
        REFUNDED,
        QUARANTINED
    }

    /**
     * Void+refund one proven pending issuance through the atomic store.
     *
     * @return how the record counts; never null
     */
    private RefundDisposition refundOne(BanknoteIssuance issuance, List<String> diagnoses) {
        UUID nonce = issuance.nonce();
        if (!(issuances instanceof AtomicBanknoteIssuanceStore atomic)) {
            quarantinedMark(nonce,
                    "recovery: proven deduction without an atomic store; quarantined"
                            + " with no money movement",
                    diagnoses);
            return RefundDisposition.QUARANTINED;
        }
        Transaction prepared = new Transaction(UUID.randomUUID(), issuance.accountId(), null,
                issuance.currencyId(), issuance.amount(), TransactionType.DEPOSIT, null, null,
                Instant.now(), BanknoteIssuanceReasons.recoveryRefundReason(nonce), null,
                BanknoteIssuanceReasons.SYSTEM_RECOVERY_OPERATOR);
        IssuanceRefundResult result;
        try {
            result = atomic.commitRecoveryRefund(nonce, prepared, debtPolicy);
        } catch (IndeterminatePersistenceException e) {
            String diagnosis = "recovery: uncertain void+refund outcome for nonce " + nonce
                    + "; kept pending, never retried blindly";
            diagnoses.add(diagnosis);
            LOG.log(Level.SEVERE, diagnosis, e);
            return RefundDisposition.QUARANTINED;
        }
        if (result.isCommitted() || result.isReplay()) {
            return RefundDisposition.REFUNDED;
        }
        if (result.isAlreadyRedeemed()) {
            String diagnosis = "recovery: nonce " + nonce
                    + " was already redeemed; stabilised with no refund";
            diagnoses.add(diagnosis);
            LOG.log(Level.INFO, diagnosis);
            return RefundDisposition.QUARANTINED;
        }
        if (result.isAccountMissing()) {
            String diagnosis = "recovery: account missing for nonce " + nonce
                    + "; left pending with no money movement";
            diagnoses.add(diagnosis);
            LOG.log(Level.WARNING, diagnosis);
            return RefundDisposition.QUARANTINED;
        }
        String kind = result.kind().name();
        quarantinedMark(nonce,
                "recovery: void+refund refused (" + kind + ") for nonce " + nonce
                        + "; quarantined with no money movement",
                diagnoses);
        return RefundDisposition.QUARANTINED;
    }

    private void quarantinedMark(UUID nonce, String diagnosis, List<String> diagnoses) {
        diagnoses.add(diagnosis);
        LOG.log(Level.WARNING, diagnosis);
        try {
            issuances.markQuarantined(nonce, diagnosis);
        } catch (RuntimeException e) {
            String followUp = "recovery: quarantine mark failed for nonce " + nonce
                    + " (" + e.getClass().getSimpleName() + "); row retained for retry";
            diagnoses.add(followUp);
            LOG.log(Level.SEVERE, followUp, e);
        }
    }
}
