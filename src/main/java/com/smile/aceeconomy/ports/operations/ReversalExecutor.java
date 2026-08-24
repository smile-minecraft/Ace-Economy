package com.smile.aceeconomy.ports.operations;

import java.util.List;
import java.util.UUID;

/**
 * Executes a {@link ReversalPlan}. Implementations must apply every {@link ReversalPlan.AccountDelta}
 * and append the reversal audit records atomically: either all effects are visible or none is.
 *
 * <p>The production binding (wired in a later task) will perform the balance mutation through the
 * account/transaction repositories. This port keeps the operations layer free of vendor code and
 * lets tests supply an in-memory or failing executor.</p>
 */
public interface ReversalExecutor {

    ReversalOutcome execute(ReversalPlan plan);

    /**
     * Whether this executor persists the plan's reverted markers itself, inside the same
     * atomic storage commit as the balance effects and the reversal audit records.
     *
     * <p>The default ({@code false}) describes legacy executors that leave marker
     * persistence to the caller: {@code RollbackService} then performs its idempotent
     * {@code markReverted} pass after a successful execution and reports
     * {@code MARK_FAILED} when that pass fails. Executors backed by an atomic
     * cross-resource store must return {@code true}, so the service never issues a second,
     * separately-failing marker write for a reversal whose markers are already durable.</p>
     */
    default boolean ownsMarkerPersistence() {
        return false;
    }
}
