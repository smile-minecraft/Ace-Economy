package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.operations.RollbackCategory;
import com.smile.aceeconomy.operations.RollbackError;
import com.smile.aceeconomy.ports.Clock;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.operations.ReversalOutcome;
import com.smile.aceeconomy.ports.operations.ReversalPlan;
import com.smile.aceeconomy.ports.operations.ReversalExecutor;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Vendor-free {@link ReversalExecutor} backed by the account and transaction repositories.
 *
 * <p>Applies every {@link ReversalPlan.AccountDelta} to its account, appends the reversal audit
 * records atomically via {@code appendBatch}, then persists the updated accounts. The reversal
 * records are constructed here (with correct {@code balanceBefore}/{@code balanceAfter}) so the
 * marker persistence in {@code RollbackService} stays separate from reversal execution.</p>
 *
 * <p>Production note: a real deployment should wrap the balance mutation and record append in a
 * single storage transaction; this in-memory implementation performs them sequentially and is
 * atomic for in-memory repositories.</p>
 */
public final class InMemoryReversalExecutor implements ReversalExecutor {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final Clock clock;

    public InMemoryReversalExecutor(AccountRepository accounts, TransactionRepository transactions, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ReversalOutcome execute(ReversalPlan plan) {
        // 1. Validate every referenced account exists and compute updated accounts locally.
        Map<UUID, Account> updated = new HashMap<>();
        Map<UUID, Account> original = new HashMap<>();
        for (ReversalPlan.AccountDelta d : plan.deltas()) {
            Account current = original.get(d.accountId());
            if (current == null) {
                current = accounts.load(d.accountId()).orElse(null);
                if (current != null) {
                    original.put(d.accountId(), current);
                }
            }
            if (current == null) {
                return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED,
                        "account not found for reversal: " + d.accountId());
            }
            Account base = updated.getOrDefault(d.accountId(), current);
            Account next = base.deposit(d.currencyId(), d.delta());
            if (d.delta().isNegative()) {
                next = base.withdraw(d.currencyId(), d.delta().abs());
            }
            updated.put(d.accountId(), next);
        }

        // 2. Build reversal audit records (one per delta), mapping counterparty from the originals.
        Map<UUID, UUID> deltaCounterparty = new HashMap<>();
        for (Transaction orig : plan.originals()) {
            deltaCounterparty.put(orig.accountId(), orig.counterparty());
        }
        List<Transaction> reversalRecords = new ArrayList<>();
        Map<UUID, Account> recordState = new HashMap<>();
        for (ReversalPlan.AccountDelta d : plan.deltas()) {
            Account base = recordState.getOrDefault(d.accountId(), original.get(d.accountId()));
            Amount before = base.balanceOf(d.currencyId());
            if (before == null) {
                before = d.delta().zero(d.delta().scale());
            }
            Amount after = before.add(d.delta());
            recordState.put(d.accountId(), d.delta().isNegative()
                    ? base.withdraw(d.currencyId(), d.delta().abs())
                    : base.deposit(d.currencyId(), d.delta()));
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
                    before, after, clock.instant(), "rollback:" + plan.category().name().toLowerCase()));
        }

        // 3. Append reversal records atomically, then persist updated balances.
        try {
            transactions.appendBatch(reversalRecords);
        } catch (PersistenceException e) {
            return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED,
                    "failed to append reversal records: " + e.getMessage());
        }
        try {
            for (Account a : updated.values()) {
                accounts.save(original.get(a.owner()), a);
            }
        } catch (RuntimeException e) {
            return ReversalOutcome.failure(RollbackError.EXECUTION_FAILED,
                    "failed to persist reversed balances: " + e.getMessage());
        }

        List<UUID> ids = new ArrayList<>();
        for (Transaction t : reversalRecords) {
            ids.add(t.id());
        }
        return ReversalOutcome.success(ids);
    }
}
