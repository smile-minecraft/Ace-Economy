package com.smile.aceeconomy.ports.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;

import java.util.List;
import java.util.UUID;

/**
 * Immutable plan describing how to reverse one or more committed transactions.
 *
 * <p>The {@link com.smile.aceeconomy.operations.RollbackService} builds this plan after it has
 * validated the candidate, determined its {@link com.smile.aceeconomy.operations.RollbackCategory}
 * and located any counterpart leg (transfers). The actual balance mutation and reversal audit
 * records are produced by an injected {@link ReversalExecutor}, keeping marker persistence
 * (the {@code markReverted} call) separate from reversal execution.</p>
 */
public final class ReversalPlan {

    /** A single signed balance mutation to apply to one account. */
    public record AccountDelta(UUID accountId, String currencyId, Amount delta) {
        public AccountDelta {
            if (accountId == null) {
                throw new IllegalArgumentException("AccountDelta.accountId must not be null");
            }
            if (currencyId == null) {
                throw new IllegalArgumentException("AccountDelta.currencyId must not be null");
            }
            if (delta == null) {
                throw new IllegalArgumentException("AccountDelta.delta must not be null");
            }
        }
    }

    private final List<Transaction> originals;
    private final List<AccountDelta> deltas;
    private final List<UUID> markerIds;
    private final com.smile.aceeconomy.operations.RollbackCategory category;

    public ReversalPlan(List<Transaction> originals, List<AccountDelta> deltas,
                        List<UUID> markerIds,
                        com.smile.aceeconomy.operations.RollbackCategory category) {
        this.originals = List.copyOf(originals);
        this.deltas = List.copyOf(deltas);
        this.markerIds = List.copyOf(markerIds);
        this.category = category;
    }

    public List<Transaction> originals() {
        return originals;
    }

    public List<AccountDelta> deltas() {
        return deltas;
    }

    public List<UUID> markerIds() {
        return markerIds;
    }

    public com.smile.aceeconomy.operations.RollbackCategory category() {
        return category;
    }
}
