package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.operations.ReversalExecutor;
import com.smile.aceeconomy.ports.operations.ReversalOutcome;
import com.smile.aceeconomy.ports.operations.ReversalPlan;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Rollback boundary over the v2 transaction store.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li><b>Candidate selection</b> — the target is identified by transaction id; the matching
 *       record is located by scanning the store. Unknown ids are rejected with
 *       {@link RollbackError#UNKNOWN_TRANSACTION}.</li>
 *   <li><b>Category</b> — {@link #categorize(Transaction)} maps the record to a
 *       {@link RollbackCategory}; transfers additionally require locating the counterpart leg,
 *       which fails safely with {@link RollbackError#COUNTERPART_NOT_FOUND} when missing.</li>
 *   <li><b>Idempotency</b> — a transaction already marked reverted returns a safe
 *       {@link RollbackResult#alreadyReverted()} no-op without re-executing the reversal, so a
 *       re-run never produces duplicate effects.</li>
 *   <li><b>Failure policy</b> — an executor failure yields a typed {@link RollbackError#EXECUTION_FAILED}
 *       and the marker is NOT written, leaving the item retryable. Marker-persistence
 *       ({@code markReverted}) is kept separate from reversal execution (the injected executor).</li>
 * </ul>
 */
public final class RollbackService {

    private final TransactionRepository transactions;
    private final ReversalExecutor executor;

    public RollbackService(TransactionRepository transactions, ReversalExecutor executor) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public RollbackResult rollback(UUID transactionId) {
        if (transactionId == null) {
            return RollbackResult.failure(RollbackError.INVALID_REQUEST, "transactionId must not be null");
        }

        List<Transaction> all;
        try {
            all = transactions.loadAll();
        } catch (RuntimeException e) {
            return RollbackResult.failure(RollbackError.EXECUTION_FAILED,
                    "failed to load transactions: " + e.getMessage());
        }

        Transaction target = findById(all, transactionId);
        if (target == null) {
            return RollbackResult.failure(RollbackError.UNKNOWN_TRANSACTION,
                    "no transaction with id " + transactionId);
        }

        boolean already;
        try {
            already = transactions.isReverted(transactionId);
        } catch (PersistenceException e) {
            return RollbackResult.failure(RollbackError.EXECUTION_FAILED,
                    "failed to check reverted marker: " + e.getMessage());
        }
        if (already) {
            return RollbackResult.alreadyReverted();
        }

        RollbackCategory category = categorize(target);
        ReversalPlan plan;
        try {
            plan = buildPlan(target, category, all);
        } catch (CounterpartNotFoundException e) {
            return RollbackResult.failure(RollbackError.COUNTERPART_NOT_FOUND, e.getMessage());
        }

        ReversalOutcome outcome = executor.execute(plan);
        if (!outcome.isSuccess()) {
            // Executor failed: do NOT mark reverted, so the operation remains retryable.
            return RollbackResult.failure(RollbackError.EXECUTION_FAILED, outcome.message());
        }

        try {
            for (UUID markerId : plan.markerIds()) {
                transactions.markReverted(markerId);
            }
        } catch (PersistenceException e) {
            // Reversal already applied but the marker could not be persisted. Surface as MARK_FAILED
            // so operators know the effect occurred without durable bookkeeping.
            return RollbackResult.failure(RollbackError.MARK_FAILED,
                    "reversal applied but marker persist failed: " + e.getMessage());
        }

        return RollbackResult.success(outcome.reversalTransactionIds());
    }

    static RollbackCategory categorize(Transaction t) {
        return switch (t.type()) {
            case DEPOSIT -> RollbackCategory.DEPOSIT;
            case WITHDRAW -> RollbackCategory.WITHDRAW;
            case SET -> RollbackCategory.SET;
            case TRANSFER_OUT, TRANSFER_IN -> RollbackCategory.TRANSFER;
        };
    }

    private ReversalPlan buildPlan(Transaction target, RollbackCategory category, List<Transaction> all) {
        return switch (category) {
            case DEPOSIT -> new ReversalPlan(
                    List.of(target),
                    List.of(new ReversalPlan.AccountDelta(target.accountId(), target.currencyId(),
                            target.amount().negate())),
                    List.of(target.id()),
                    category);
            case WITHDRAW -> new ReversalPlan(
                    List.of(target),
                    List.of(new ReversalPlan.AccountDelta(target.accountId(), target.currencyId(),
                            target.amount())),
                    List.of(target.id()),
                    category);
            case SET -> {
                // Restore the prior balance: delta = balanceBefore - balanceAfter.
                var delta = target.balanceBefore().subtract(target.balanceAfter());
                yield new ReversalPlan(
                        List.of(target),
                        List.of(new ReversalPlan.AccountDelta(target.accountId(), target.currencyId(), delta)),
                        List.of(target.id()),
                        category);
            }
            case TRANSFER -> {
                Transaction counterpart = findCounterpart(target, all);
                if (counterpart == null) {
                    throw new CounterpartNotFoundException(
                            "counterpart leg not found for transfer " + target.id());
                }
                UUID sender = target.type() == TransactionType.TRANSFER_OUT ? target.accountId() : counterpart.accountId();
                UUID receiver = target.type() == TransactionType.TRANSFER_OUT ? counterpart.accountId() : target.accountId();
                List<Transaction> originals = target.type() == TransactionType.TRANSFER_OUT
                        ? List.of(target, counterpart) : List.of(counterpart, target);
                // Sender gets the amount back (+), receiver loses it (-).
                List<ReversalPlan.AccountDelta> deltas = new ArrayList<>();
                deltas.add(new ReversalPlan.AccountDelta(sender, target.currencyId(), target.amount()));
                deltas.add(new ReversalPlan.AccountDelta(receiver, target.currencyId(), target.amount().negate()));
                List<UUID> markers = List.of(target.id(), counterpart.id());
                yield new ReversalPlan(originals, deltas, markers, category);
            }
        };
    }

    private static Transaction findById(List<Transaction> all, UUID id) {
        for (Transaction t : all) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    private static Transaction findCounterpart(Transaction leg, List<Transaction> all) {
        TransactionType wanted = leg.type() == TransactionType.TRANSFER_OUT
                ? TransactionType.TRANSFER_IN : TransactionType.TRANSFER_OUT;
        UUID wantedAccount = leg.counterparty();
        UUID wantedCounterparty = leg.accountId();
        for (Transaction t : all) {
            if (t.type() != wanted) {
                continue;
            }
            if (!Objects.equals(t.accountId(), wantedAccount)) {
                continue;
            }
            if (!Objects.equals(t.counterparty(), wantedCounterparty)) {
                continue;
            }
            if (!Currency.normalizeId(t.currencyId()).equals(Currency.normalizeId(leg.currencyId()))) {
                continue;
            }
            if (t.amount().compareTo(leg.amount()) != 0) {
                continue;
            }
            if (!t.timestamp().equals(leg.timestamp())) {
                continue;
            }
            return t;
        }
        return null;
    }

    private static final class CounterpartNotFoundException extends RuntimeException {
        CounterpartNotFoundException(String message) {
            super(message);
        }
    }
}
