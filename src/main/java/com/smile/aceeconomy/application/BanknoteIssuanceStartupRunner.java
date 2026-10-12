package com.smile.aceeconomy.application;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Startup recovery runner for durable banknote issuance.
 *
 * <p>Decided wiring:</p>
 * <ul>
 *   <li>Runs once in the presentation tail after persistence and application
 *       are ready, over the existing IO scheduler (no new pool, no region
 *       blocking). Scheduled exactly once; a second schedule is a no-op.</li>
 *   <li>The recovery barrier starts closed before the durable atomic store is
 *       bound to the banknote flow and the GUI use case. New managed issuance
 *       and managed redemption wait on {@link #isRecoveryComplete()} instead
 *       of silently passing while recovery is incomplete; queries and legacy
 *       v2 notes (no issuance row) stay allowed.</li>
 *   <li>The run scans with observable progress (scanned, refunded,
 *       aborted, quarantined plus loud diagnoses). Success opens the barrier
 *       only at batch end; failure never throws the plugin down — it keeps
 *       the plugin enabled with a loud diagnosis, keeps every pending row,
 *       and keeps blocking the related new issuance / managed redemption
 *       instead of discarding data or silently passing.</li>
 *   <li>Recovery converges to refund+void for proven pending records
 *       ({@code DEBIT_COMMITTED} or {@code DELIVERY_STARTED} with a durable
 *       debit proof and an unconsumed nonce); it never reissues automatically.
 *       {@code DELIVERED} never refunds; already-redeemed stabilises to
 *       {@code REDEEMED} with no money write; proof-less or undecidable
 *       quarantines with no money movement; deduction-free {@code PREPARED}
 *       aborts with no money movement.</li>
 * </ul>
 */
public final class BanknoteIssuanceStartupRunner {

    private static final Logger LOG =
            Logger.getLogger(BanknoteIssuanceStartupRunner.class.getName());

    /** Bounded wait for the single startup run; recovery scans are local. */
    private static final long RUN_TIMEOUT_SECONDS = 60;

    private final BanknoteIssuanceRecovery recovery;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private volatile boolean complete = false;
    private volatile BanknoteIssuanceRecovery.RecoveryReport lastReport =
            new BanknoteIssuanceRecovery.RecoveryReport(0, 0, 0, 0, List.of());

    public BanknoteIssuanceStartupRunner(BanknoteIssuanceRecovery recovery) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    /**
     * Schedule {@link BanknoteIssuanceRecovery#recoverAll()} once on the
     * existing IO scheduler and wait bounded for it to finish. Exactly-once:
     * a second call is a no-op reporting the current barrier state. A failed
     * run keeps the plugin enabled with a loud diagnosis, retains every
     * pending row, and leaves the barrier closed so new managed issuance and
     * managed redemption stay blocked.
     *
     * @param io the existing IO scheduler; never {@code null}
     * @return true once the startup run has finished
     */
    public boolean scheduleOnce(ScheduledExecutorService io) {
        Objects.requireNonNull(io, "io");
        if (!scheduled.compareAndSet(false, true)) {
            return complete;
        }
        Future<?> run;
        try {
            run = io.submit(this::runRecovery);
        } catch (RuntimeException e) {
            String diagnosis = "Banknote issuance startup recovery could not be scheduled"
                    + " (" + e.getClass().getSimpleName() + "); pending rows retained,"
                    + " new managed issuance stays blocked";
            LOG.log(Level.SEVERE, diagnosis, e);
            lastReport = new BanknoteIssuanceRecovery.RecoveryReport(0, 0, 0, 0,
                    List.of(diagnosis));
            return false;
        }
        try {
            run.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String diagnosis = "Banknote issuance startup recovery wait was interrupted;"
                    + " pending rows retained, new managed issuance stays blocked";
            LOG.log(Level.SEVERE, diagnosis, e);
            lastReport = new BanknoteIssuanceRecovery.RecoveryReport(0, 0, 0, 0,
                    List.of(diagnosis));
            return false;
        } catch (Exception e) {
            String diagnosis = "Banknote issuance startup recovery run failed"
                    + " (" + e.getClass().getSimpleName() + "); pending rows retained,"
                    + " new managed issuance stays blocked";
            LOG.log(Level.SEVERE, diagnosis, e);
            lastReport = new BanknoteIssuanceRecovery.RecoveryReport(0, 0, 0, 0,
                    List.of(diagnosis));
            return false;
        }
        return complete;
    }

    /**
     * @return true once the startup recovery run has finished (even with
     *         quarantines); false while recovery never ran or its scan failed
     */
    public boolean isRecoveryComplete() {
        return complete;
    }

    /**
     * @return true once {@link #scheduleOnce} has been called
     */
    public boolean isScheduled() {
        return scheduled.get();
    }

    /**
     * Counted progress of the last run for loud diagnostics.
     *
     * @return the counted outcome; never null
     */
    public BanknoteIssuanceRecovery.RecoveryReport lastReport() {
        return lastReport;
    }

    private void runRecovery() {
        try {
            BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();
            lastReport = report;
            complete = recovery.isRecoveryComplete();
            if (!report.diagnoses().isEmpty()) {
                LOG.log(Level.WARNING,
                        "Banknote issuance startup recovery finished with diagnoses: {0}",
                        report.diagnoses());
            }
        } catch (Throwable t) {
            String diagnosis = "Banknote issuance startup recovery threw"
                    + " (" + t.getClass().getSimpleName() + "); pending rows retained,"
                    + " new managed issuance stays blocked";
            LOG.log(Level.SEVERE, diagnosis, t);
            lastReport = new BanknoteIssuanceRecovery.RecoveryReport(0, 0, 0, 0,
                    List.of(diagnosis));
        }
    }
}
