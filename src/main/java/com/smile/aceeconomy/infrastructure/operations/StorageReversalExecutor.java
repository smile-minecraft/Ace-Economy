package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.operations.RollbackCategory;
import com.smile.aceeconomy.operations.RollbackError;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.Clock;
import com.smile.aceeconomy.ports.operations.ReversalExecutor;
import com.smile.aceeconomy.ports.operations.ReversalOutcome;
import com.smile.aceeconomy.ports.operations.ReversalPlan;
import com.smile.aceeconomy.ports.persistence.AtomicReversalStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Production {@link ReversalExecutor}: validates the plan against the live accounts, builds the
 * reversal audit records, then commits balances + audit records + reverted markers through a
 * single {@link AtomicReversalStore} transaction, so a failure leaves no half-applied reversal
 * behind and a retry can never duplicate balance effects or reversal records. Because the
 * markers are part of that atomic commit, it declares {@link #ownsMarkerPersistence()} and the
 * service never writes them again.
 *
 * <p>{@link InMemoryReversalExecutor} remains available as a test double only; it applies the
 * same validation and record-building semantics but persists sequentially.</p>
 */
public final class StorageReversalExecutor implements ReversalExecutor {

    private final AccountRepository accounts;
    private final AtomicReversalStore store;
    private final Clock clock;

    public StorageReversalExecutor(AccountRepository accounts, AtomicReversalStore store, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean ownsMarkerPersistence() {
        return true;
    }

    @Override
    public ReversalOutcome execute(ReversalPlan plan) {
        // Apply the deltas sequentially to a per-account working state: repeated deltas on
        // the same account/currency accumulate (100 -> 90 -> 80) instead of each recomputing
        // from the original balance and overwriting the previous mutation. The audit record
        // for every delta is built from this evolving state, so its before/after chain
        // matches exactly what is applied. No write happens until the whole plan is proven
        // applicable; an unknown account fails before any storage state is touched.
        Map<UUID, Account> current = new HashMap<>();
        Map<UUID, UUID> deltaCounterparty = new HashMap<>();
        for (Transaction orig : plan.originals()) {
            deltaCounterparty.put(orig.accountId(), orig.counterparty());
        }
        List<Transaction> reversalRecords = new ArrayList<>();
        for (ReversalPlan.AccountDelta d : plan.deltas()) {
            Account base = current.get(d.accountId());
            if (base == null) {
                base = accounts.load(d.accountId()).orElse(null);
                if (base == null) {
                    return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED,
                            "account not found for reversal: " + d.accountId());
                }
            }
            Amount before = base.balanceOf(d.currencyId());
            if (before == null) {
                before = d.delta().zero(d.delta().scale());
            }
            Amount after = before.add(d.delta());
            Account next = d.delta().isNegative()
                    ? base.withdraw(d.currencyId(), d.delta().abs())
                    : base.deposit(d.currencyId(), d.delta());
            current.put(d.accountId(), next);

            TransactionType type;
            Amount recordedAmount;
            if (plan.category() == RollbackCategory.SET) {
                type = TransactionType.SET;
                recordedAmount = after; // the restored balance
            } else if (d.delta().isNonNegative()) {
                type = TransactionType.DEPOSIT;
                recordedAmount = d.delta().abs();
            } else {
                type = TransactionType.WITHDRAW;
                recordedAmount = d.delta().abs();
            }
            reversalRecords.add(new Transaction(
                    UUID.randomUUID(), d.accountId(), deltaCounterparty.get(d.accountId()),
                    Currency.normalizeId(d.currencyId()), recordedAmount, type,
                    before, after, clock.instant(),
                    "rollback:" + plan.category().name().toLowerCase()));
        }

        // Commit balances + reversal records + reverted markers as ONE storage transaction.
        // Any persistence failure leaves the previous state fully intact, so a retry
        // re-executes the whole plan without duplicating effects. When the storage reports
        // a failure after the data was already committed (post-commit auto-commit restore
        // failure), the effect is durable and must be surfaced as success so a retry does not
        // duplicate the debit.
        try {
            store.applyReversal(new ArrayList<>(current.values()), reversalRecords, plan.markerIds());
        } catch (PersistenceException e) {
            if (e.isCommitted()) {
                List<UUID> ids = new ArrayList<>();
                for (Transaction t : reversalRecords) {
                    ids.add(t.id());
                }
                return ReversalOutcome.success(ids);
            }
            return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED,
                    "failed to apply reversal atomically: " + e.getMessage());
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PersistenceException pe && pe.isCommitted()) {
                List<UUID> ids = new ArrayList<>();
                for (Transaction t : reversalRecords) {
                    ids.add(t.id());
                }
                return ReversalOutcome.success(ids);
            }
            return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED,
                    "failed to apply reversal atomically: " + e.getMessage());
        }

        List<UUID> ids = new ArrayList<>();
        for (Transaction t : reversalRecords) {
            ids.add(t.id());
        }
        return ReversalOutcome.success(ids);
    }
}
