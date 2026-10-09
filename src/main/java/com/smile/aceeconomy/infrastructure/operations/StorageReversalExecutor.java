package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.DebtPolicy;
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
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(StorageReversalExecutor.class.getName());

    /**
     * Fixed player-safe message for pre-commit reversal outages. Nothing moved, so a
     * later retry is safe — but the message never carries backend details. The
     * original throwable, with its cause chain and suppressed cleanup failures, is
     * recorded on the diagnostic log instead.
     */
    private static final String STORAGE_UNAVAILABLE =
            "reversal storage unavailable; nothing was changed, please try again later";

    private final AccountRepository accounts;
    private final AtomicReversalStore store;
    private final Clock clock;
    /**
     * Debt policy shared with ordinary transactions ({@code economy.allow-negative-balance}).
     * Checked twice: an early diagnostic pre-check against the balances read here, and —
     * authoritatively — inside the store's atomic commit, which re-reads the live rows after
     * the markers-first idempotency check and before any write, so an interleaving spend
     * cannot smuggle a negative balance through. {@code null} means no enforcement (legacy
     * constructor only, preserving the pre-policy behaviour for existing callers);
     * production always passes the configured policy.
     */
    private final DebtPolicy debtPolicy;
    /**
     * Best-effort balance-cache invalidation for every account a reversal touches.
     * Production wires the application read cache here because this executor persists
     * directly and bypasses {@code EconomyService}; without it a cached Vault read keeps
     * serving the pre-rollback balance. Null means no cache is attached (legacy callers).
     */
    private final Consumer<UUID> cacheInvalidation;

    public StorageReversalExecutor(AccountRepository accounts, AtomicReversalStore store, Clock clock) {
        this(accounts, store, clock, null, null);
    }

    public StorageReversalExecutor(AccountRepository accounts, AtomicReversalStore store, Clock clock,
                                   Consumer<UUID> cacheInvalidation) {
        this(accounts, store, clock, cacheInvalidation, null);
    }

    public StorageReversalExecutor(AccountRepository accounts, AtomicReversalStore store, Clock clock,
                                   DebtPolicy debtPolicy) {
        this(accounts, store, clock, null, debtPolicy);
    }

    public StorageReversalExecutor(AccountRepository accounts, AtomicReversalStore store, Clock clock,
                                   Consumer<UUID> cacheInvalidation, DebtPolicy debtPolicy) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cacheInvalidation = cacheInvalidation;
        this.debtPolicy = debtPolicy;
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
                    notifyInvalidated(current.keySet());
                    return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED,
                            "account not found for reversal: " + d.accountId());
                }
            }
            Amount before = base.balanceOf(d.currencyId());
            if (before == null) {
                before = d.delta().zero(d.delta().scale());
            }
            Amount after = before.add(d.delta());
            if (debtPolicy != null && !debtPolicy.allows(after)) {
                notifyInvalidated(current.keySet());
                return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED,
                        debtRejectionMessage(d, after));
            }
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
        // The configured debt policy travels into the atomic scope: the store re-reads the
        // live rows after its markers-first idempotency check and validates every resulting
        // balance before any write, so the pre-check above stays an early diagnostic while
        // the store verdict is authoritative.
        try {
            store.applyReversal(new ArrayList<>(current.values()), reversalRecords, plan.markerIds(),
                    debtPolicy);
        } catch (AtomicReversalStore.DebtLimitExceededException debtRefusal) {
            // Atomic-scope debt refusal: nothing was committed (no balance effect, no record,
            // no marker), so the operation stays retryable. The store message already names the
            // governing setting, so it passes through as the clear console reply.
            notifyInvalidated(current.keySet());
            return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED, debtRefusal.getMessage());
        } catch (PersistenceException e) {
            if (e instanceof IndeterminatePersistenceException indeterminate) {
                // Uncertain outcome: neither proven committed nor proven rolled back.
                // It must propagate so callers warn against retrying instead of
                // reporting an ordinary retryable failure.
                notifyInvalidated(current.keySet());
                LOG.log(Level.WARNING,
                        "Reversal outcome is uncertain (commit response lost);"
                                + " propagating instead of reporting an ordinary failure",
                        e);
                throw indeterminate;
            }
            if (e.isCommitted()) {
                List<UUID> ids = new ArrayList<>();
                for (Transaction t : reversalRecords) {
                    ids.add(t.id());
                }
                notifyInvalidated(current.keySet());
                return ReversalOutcome.success(ids);
            }
            notifyInvalidated(current.keySet());
            LOG.log(Level.WARNING,
                    "Reversal failed before commit; answering EXECUTION_FAILED"
                            + " without changing anything",
                    e);
            return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED, STORAGE_UNAVAILABLE);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IndeterminatePersistenceException indeterminateCause) {
                // Same uncertain outcome behind a wrapper: it must propagate so callers
                // warn against retrying instead of reporting an ordinary failure.
                notifyInvalidated(current.keySet());
                LOG.log(Level.WARNING,
                        "Reversal outcome is uncertain (commit response lost);"
                                + " propagating instead of reporting an ordinary failure",
                        e);
                throw indeterminateCause;
            }
            if (cause instanceof PersistenceException pe && pe.isCommitted()) {
                List<UUID> ids = new ArrayList<>();
                for (Transaction t : reversalRecords) {
                    ids.add(t.id());
                }
                notifyInvalidated(current.keySet());
                return ReversalOutcome.success(ids);
            }
            notifyInvalidated(current.keySet());
            LOG.log(Level.WARNING,
                    "Reversal failed before commit; answering EXECUTION_FAILED"
                            + " without changing anything",
                    e);
            return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED, STORAGE_UNAVAILABLE);
        }

        List<UUID> ids = new ArrayList<>();
        for (Transaction t : reversalRecords) {
            ids.add(t.id());
        }
        notifyInvalidated(current.keySet());
        return ReversalOutcome.success(ids);
    }

    /**
     * Clear debt-policy refusal naming the governing setting, so the console reply reads like
     * the ordinary-transaction refusal ({@code insufficient funds} when negative balances are
     * disabled, {@code debt limit exceeded} when the configured limit would be passed).
     * Nothing has been written when this is reported, so a later retry stays safe.
     */
    private String debtRejectionMessage(ReversalPlan.AccountDelta delta, Amount after) {
        String where = "account " + delta.accountId()
                + " (" + Currency.normalizeId(delta.currencyId()) + ")";
        if (!debtPolicy.isAllowNegative()) {
            return "rollback rejected: reversal would make balance " + after + " for " + where
                    + ", which economy.allow-negative-balance=false forbids"
                    + " (insufficient funds); nothing was changed";
        }
        return "rollback rejected: reversal would make balance " + after + " for " + where
                + " exceeding debt limit " + debtPolicy.debtLimit()
                + " (debt limit exceeded); nothing was changed";
    }

    private void notifyInvalidated(java.util.Set<UUID> accountUuids) {
        if (cacheInvalidation == null || accountUuids == null) {
            return;
        }
        for (UUID uuid : accountUuids) {
            if (uuid == null) {
                continue;
            }
            try {
                cacheInvalidation.accept(uuid);
            } catch (RuntimeException ignored) {
                // Best-effort: cache housekeeping must never break the reversal outcome.
            }
        }
    }
}
