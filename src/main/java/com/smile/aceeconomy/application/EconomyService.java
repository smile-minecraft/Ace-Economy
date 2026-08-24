package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.AccountSnapshot;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionEvent;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.AuditException;
import com.smile.aceeconomy.ports.AuditSink;
import com.smile.aceeconomy.ports.Clock;
import com.smile.aceeconomy.ports.TransactionEventPublisher;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core economy use-case orchestrator. Depends only on domain types and port interfaces;
 * it imports no Bukkit, AceLib, Vault, PAPI, JDBC or v1 classes.
 *
 * <p>Contract invariants enforced here:</p>
 * <ul>
 *   <li>Non-positive amounts are rejected for deposit/withdraw/transfer.</li>
 *   <li>Withdraw/set respects the {@link DebtPolicy}; insufficient / over-limit returns a typed failure.</li>
 *   <li>Same-account transfer returns {@link EconomyError#SAME_ACCOUNT} with no mutation.</li>
 *   <li>Pre-commit events are fired BEFORE any balance mutation; cancellation blocks it.</li>
 *   <li>Balance mutation/outcome commits first, then audit records in a fixed order; audit
 *       failure is surfaced via {@link EconomyResult.Success#auditFailure()} rather than swallowed.</li>
 *   <li>Cross-account operations lock both accounts in deterministic (lexical UUID) order.</li>
 * </ul>
 */
public final class EconomyService {

    private final CurrencyRegistry currencies;
    private final DebtPolicy debtPolicy;
    private final Amount startBalance;
    private final AccountRepository accounts;
    private final AuditSink audit;
    private final Clock clock;
    private final TransactionEventPublisher events;
    private final LockRegistry locks = new LockRegistry();

    public EconomyService(CurrencyRegistry currencies, DebtPolicy debtPolicy, Amount startBalance,
                           AccountRepository accounts, AuditSink audit, Clock clock,
                           TransactionEventPublisher events) {
        this.currencies = currencies;
        this.debtPolicy = debtPolicy;
        this.startBalance = startBalance;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    // ---------- account lifecycle ----------

    public EconomyResult<AccountSnapshot> createAccount(UUID uuid, String ownerName) {
        Optional<Account> existing = accounts.load(uuid);
        if (existing.isPresent()) {
            return EconomyResult.success(existing.get().snapshot());
        }
        Map<String, Amount> initial = new HashMap<>();
        for (Currency c : currencies.all()) {
            initial.put(c.id(), c.id().equals(currencies.defaultCurrencyId()) ? startBalance : c.zero());
        }
        Account account = accounts.create(uuid, ownerName, initial);
        return EconomyResult.success(account.snapshot());
    }

    public EconomyResult<AccountSnapshot> load(UUID uuid) {
        Optional<Account> acc = accounts.load(uuid);
        if (acc.isEmpty()) {
            return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
        }
        return EconomyResult.success(acc.get().snapshot());
    }

    public boolean hasAccount(UUID uuid) {
        return accounts.exists(uuid);
    }

    // ---------- read ----------

    public EconomyResult<Amount> getBalance(UUID uuid, String currencyId) {
        if (!currencies.contains(currencyId)) {
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        Optional<Account> acc = accounts.load(uuid);
        if (acc.isEmpty()) {
            return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
        }
        Amount bal = acc.get().balanceOf(currencyId);
        return EconomyResult.success(bal != null ? bal : currencies.get(currencyId).zero());
    }

    // ---------- deposit ----------

    public EconomyResult<Amount> deposit(UUID uuid, String currencyId, Amount amount) {
        EconomyResult<Void> v = validateAmountPositive(amount);
        if (v.isFailure()) {
            return EconomyResult.failure(v.error(), v.message());
        }
        if (!currencies.contains(currencyId)) {
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        ReentrantLock lock = locks.lockFor(uuid);
        lock.lock();
        try {
            Optional<Account> acc = accounts.load(uuid);
            if (acc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
            }
            Amount before = orZero(acc.get().balanceOf(currencyId), amount);
            TransactionEvent event = new TransactionEvent(uuid, amount, TransactionType.DEPOSIT, before);
            events.publishPreCommit(event);
            if (event.isCancelled()) {
                return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED, "deposit cancelled");
            }
            Account updated = acc.get().deposit(currencyId, amount);
            accounts.save(updated);
            Amount after = updated.balanceOf(currencyId);
            return commitAudit(new Transaction(newId(), uuid, null, norm(currencyId), amount,
                    TransactionType.DEPOSIT, before, after, now(), "deposit"), after);
        } finally {
            lock.unlock();
        }
    }

    // ---------- banknote redeem (durable atomic with nonce) ----------

    /**
     * Redeem a banknote atomically: validates amount/currency, acquires the per-account lock,
     * fires the pre-commit event and, only if not cancelled, persists the credited balance, audit
     * record and consumed nonce together through {@code redemptionStore}. This preserves the full
     * deposit contract (lock, pre-commit cancellation, debt policy, audit semantics) while gaining
     * durable all-or-none and first-writer-wins on the nonce.
     */
    public EconomyResult<Amount> redeemBanknote(UUID nonce, UUID accountId, String currencyId, Amount amount,
                                                AtomicRedemptionStore redemptionStore) {
        if (nonce == null) {
            return EconomyResult.failure(EconomyError.INVALID_AMOUNT, "nonce null");
        }
        EconomyResult<Void> v = validateAmountPositive(amount);
        if (v.isFailure()) {
            return EconomyResult.failure(v.error(), v.message());
        }
        if (!currencies.contains(currencyId)) {
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        if (redemptionStore == null) {
            return EconomyResult.failure(EconomyError.AUDIT_FAILURE, "redemption store missing");
        }
        ReentrantLock lock = locks.lockFor(accountId);
        lock.lock();
        try {
            Optional<Account> acc = accounts.load(accountId);
            if (acc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + accountId);
            }
            Amount before = orZero(acc.get().balanceOf(currencyId), amount);
            TransactionEvent event = new TransactionEvent(accountId, amount, TransactionType.DEPOSIT, before);
            events.publishPreCommit(event);
            if (event.isCancelled()) {
                return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED, "deposit cancelled");
            }
            Account updated = acc.get().deposit(currencyId, amount);
            Amount after = updated.balanceOf(currencyId);
            if (!debtPolicy.allows(after)) {
                return EconomyResult.failure(EconomyError.DEBT_LIMIT_EXCEEDED, "debt limit exceeded");
            }
            Transaction tx = new Transaction(newId(), accountId, null, norm(currencyId), amount,
                    TransactionType.DEPOSIT, before, after, now(), "banknote-deposit");
            try {
                RedemptionResult r = redemptionStore.redeemPrepared(nonce, updated, tx);
                if (r.isCommitted()) {
                    return EconomyResult.success(after);
                }
                if (r.isReplay()) {
                    return EconomyResult.failure(EconomyError.REPLAY_DETECTED, "replay.detected");
                }
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "account missing at commit");
            } catch (PersistenceException e) {
                return EconomyResult.failure(EconomyError.AUDIT_FAILURE, e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------- withdraw ----------

    public EconomyResult<Amount> withdraw(UUID uuid, String currencyId, Amount amount) {
        EconomyResult<Void> v = validateAmountPositive(amount);
        if (v.isFailure()) {
            return EconomyResult.failure(v.error(), v.message());
        }
        if (!currencies.contains(currencyId)) {
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        ReentrantLock lock = locks.lockFor(uuid);
        lock.lock();
        try {
            Optional<Account> acc = accounts.load(uuid);
            if (acc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
            }
            Amount before = orZero(acc.get().balanceOf(currencyId), amount);
            TransactionEvent event = new TransactionEvent(uuid, amount, TransactionType.WITHDRAW, before);
            events.publishPreCommit(event);
            if (event.isCancelled()) {
                return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED, "withdraw cancelled");
            }
            Amount after = before.subtract(amount);
            if (!debtPolicy.allows(after)) {
                return rejectWithdraw();
            }
            Account updated = acc.get().withdraw(currencyId, amount);
            accounts.save(updated);
            return commitAudit(new Transaction(newId(), uuid, null, norm(currencyId), amount,
                    TransactionType.WITHDRAW, before, after, now(), "withdraw"), after);
        } finally {
            lock.unlock();
        }
    }

    // ---------- setBalance ----------

    public EconomyResult<Amount> setBalance(UUID uuid, String currencyId, Amount amount) {
        if (amount == null) {
            return EconomyResult.failure(EconomyError.INVALID_AMOUNT, "amount null");
        }
        if (!currencies.contains(currencyId)) {
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        ReentrantLock lock = locks.lockFor(uuid);
        lock.lock();
        try {
            Optional<Account> acc = accounts.load(uuid);
            if (acc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
            }
            Amount before = orZero(acc.get().balanceOf(currencyId), amount);
            TransactionEvent event = new TransactionEvent(uuid, amount, TransactionType.SET, before);
            events.publishPreCommit(event);
            if (event.isCancelled()) {
                return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED, "set cancelled");
            }
            if (!debtPolicy.allows(amount)) {
                if (!debtPolicy.isAllowNegative()) {
                    return EconomyResult.failure(EconomyError.DEBT_DISABLED, "negative balance not allowed");
                }
                return EconomyResult.failure(EconomyError.DEBT_LIMIT_EXCEEDED, "debt limit exceeded");
            }
            Account updated = acc.get().setBalance(currencyId, amount);
            accounts.save(updated);
            return commitAudit(new Transaction(newId(), uuid, null, norm(currencyId), amount,
                    TransactionType.SET, before, amount, now(), "set"), amount);
        } finally {
            lock.unlock();
        }
    }

    // ---------- transfer ----------

    public EconomyResult<TransferResult> transfer(UUID from, UUID to, String currencyId, Amount amount) {
        if (from.equals(to)) {
            return EconomyResult.failure(EconomyError.SAME_ACCOUNT, "cannot transfer to self");
        }
        EconomyResult<Void> v = validateAmountPositive(amount);
        if (v.isFailure()) {
            return EconomyResult.failure(v.error(), v.message());
        }
        if (!currencies.contains(currencyId)) {
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        locks.lockBoth(from, to);
        try {
            Optional<Account> fromAcc = accounts.load(from);
            Optional<Account> toAcc = accounts.load(to);
            if (fromAcc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + from);
            }
            if (toAcc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + to);
            }
            Amount fromBefore = orZero(fromAcc.get().balanceOf(currencyId), amount);
            Amount toBefore = orZero(toAcc.get().balanceOf(currencyId), amount);

            // pre-commit events: cancellation blocks the mutation entirely
            TransactionEvent outEvent = new TransactionEvent(from, amount, TransactionType.TRANSFER_OUT, fromBefore);
            events.publishPreCommit(outEvent);
            if (outEvent.isCancelled()) {
                return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED, "transfer cancelled (sender)");
            }
            TransactionEvent inEvent = new TransactionEvent(to, amount, TransactionType.TRANSFER_IN, toBefore);
            events.publishPreCommit(inEvent);
            if (inEvent.isCancelled()) {
                return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED, "transfer cancelled (receiver)");
            }

            Amount fromAfter = fromBefore.subtract(amount);
            if (!debtPolicy.allows(fromAfter)) {
                return EconomyResult.failure(rejectWithdrawError(), rejectWithdrawMessage());
            }
            // receiver balance only increases; debt policy only constrains the sender
            Amount toAfter = toBefore.add(amount);

            Account updatedFrom = fromAcc.get().withdraw(currencyId, amount);
            Account updatedTo = toAcc.get().deposit(currencyId, amount);
            accounts.save(updatedFrom);
            accounts.save(updatedTo);

            // deterministic audit: mutation/outcome first, then fixed-order records (out, in)
            Transaction outTx = new Transaction(newId(), from, to, norm(currencyId), amount,
                    TransactionType.TRANSFER_OUT, fromBefore, fromAfter, now(), "transfer-out");
            Transaction inTx = new Transaction(newId(), to, from, norm(currencyId), amount,
                    TransactionType.TRANSFER_IN, toBefore, toAfter, now(), "transfer-in");

            Throwable auditError = null;
            try {
                audit.record(outTx);
                audit.record(inTx);
            } catch (AuditException e) {
                auditError = e;
            }
            TransferResult result = new TransferResult(from, to, fromAfter, toAfter,
                    outTx.id(), inTx.id());
            return EconomyResult.success(result, auditError);
        } finally {
            locks.unlockBoth(from, to);
        }
    }

    // ---------- helpers ----------

    private EconomyResult<Void> validateAmountPositive(Amount amount) {
        if (amount == null) {
            return EconomyResult.failure(EconomyError.INVALID_AMOUNT, "amount null");
        }
        if (!amount.isPositive()) {
            return EconomyResult.failure(EconomyError.INVALID_AMOUNT, "amount must be positive");
        }
        return EconomyResult.success(null);
    }

    private EconomyResult<Amount> rejectWithdraw() {
        return EconomyResult.failure(rejectWithdrawError(), rejectWithdrawMessage());
    }

    private EconomyError rejectWithdrawError() {
        return debtPolicy.isAllowNegative() ? EconomyError.DEBT_LIMIT_EXCEEDED : EconomyError.INSUFFICIENT_FUNDS;
    }

    private String rejectWithdrawMessage() {
        return debtPolicy.isAllowNegative() ? "debt limit exceeded" : "insufficient funds";
    }

    private Amount orZero(Amount current, Amount fallback) {
        return current != null ? current : fallback.zero(fallback.scale());
    }

    private <T> EconomyResult<T> commitAudit(Transaction t, T value) {
        Throwable auditError = null;
        try {
            audit.record(t);
        } catch (AuditException e) {
            auditError = e;
        }
        return EconomyResult.success(value, auditError);
    }

    private String norm(String currencyId) {
        return Currency.normalizeId(currencyId);
    }

    private Instant now() {
        return clock.instant();
    }

    private UUID newId() {
        return UUID.randomUUID();
    }
}
