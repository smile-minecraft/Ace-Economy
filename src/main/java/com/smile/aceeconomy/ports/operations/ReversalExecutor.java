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
}
