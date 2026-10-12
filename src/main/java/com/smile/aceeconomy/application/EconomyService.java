package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.AccountSnapshot;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.AmountCap;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.CurrencyScaleCheck;
import com.smile.aceeconomy.domain.DebtPolicy;import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionEvent;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.AuditException;
import com.smile.aceeconomy.ports.AuditSink;
import com.smile.aceeconomy.ports.Clock;
import com.smile.aceeconomy.ports.TransactionEventPublisher;
import com.smile.aceeconomy.ports.persistence.AdminMutationResult;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.AtomicAdminMutationStore;
import com.smile.aceeconomy.ports.persistence.AtomicTransferStore;
import com.smile.aceeconomy.ports.persistence.CommittedAdminMutationException;
import com.smile.aceeconomy.ports.persistence.CommittedTransferException;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(EconomyService.class.getName());

    /**
     * Fixed player-safe message for pre-commit storage outages. Nothing moved, so a
     * later retry is safe — but the message never carries backend details (file
     * paths, connection state). The original throwable, with its cause chain and
     * suppressed cleanup failures, is recorded on the diagnostic log instead. The
     * wording matches the command boundary's fixed uncommitted message.
     */
    private static final String STORAGE_UNAVAILABLE =
            "storage unavailable; nothing was changed, please try again later";

    private volatile CurrencyRegistry currencies;
    private final DebtPolicy debtPolicy;
    private final Amount startBalance;
    private final AccountRepository accounts;
    private final AuditSink audit;
    private final Clock clock;
    private final TransactionEventPublisher events;
    private final AtomicTransferStore transferStore;
    private final AtomicAdminMutationStore adminStore;
    private final LockRegistry locks = new LockRegistry();
    /**
     * Read-only balance acceleration. Populated only from successful persistence
     * reads/writes and dropped on offline, write failure or reload — never a source of truth.
     */
    private final AccountBalanceCache balanceCache = new AccountBalanceCache();

    public EconomyService(CurrencyRegistry currencies, DebtPolicy debtPolicy, Amount startBalance,
                           AccountRepository accounts, AuditSink audit, Clock clock,
                           TransactionEventPublisher events) {
        this(currencies, debtPolicy, startBalance, accounts, audit, clock, events, null);
    }

    public EconomyService(CurrencyRegistry currencies, DebtPolicy debtPolicy, Amount startBalance,
                           AccountRepository accounts, AuditSink audit, Clock clock,
                           TransactionEventPublisher events, AtomicTransferStore transferStore) {
        this(currencies, debtPolicy, startBalance, accounts, audit, clock, events, transferStore, null);
    }

    /**
     * Full wiring for the audited administrative path. When {@code adminStore} is null the
     * service reuses the backend instance behind {@code accounts} when it implements
     * {@link AtomicAdminMutationStore}; production JSON/SQL backends always do, so the
     * administrative mutations commit balance and audit in one storage transaction there.
     * When no atomic store is available the audited overloads fail closed with
     * {@code AUDIT_FAILURE} and no balance, record or notification side effect; they
     * never fall back to separate save plus audit commits and never compensate a
     * half-applied balance with a second write.
     * The audited overloads commit through {@code adminStore} (live-derived balances, debt
     * enforced inside the commit, post-commit notification only after success).
     */
    public EconomyService(CurrencyRegistry currencies, DebtPolicy debtPolicy, Amount startBalance,
                           AccountRepository accounts, AuditSink audit, Clock clock,
                           TransactionEventPublisher events, AtomicTransferStore transferStore,
                           AtomicAdminMutationStore adminStore) {
        this.currencies = currencies;
        this.debtPolicy = debtPolicy;
        this.startBalance = startBalance;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
        this.transferStore = transferStore != null ? transferStore
                : (accounts instanceof AtomicTransferStore ats ? ats : null);
        this.adminStore = adminStore != null ? adminStore
                : (accounts instanceof AtomicAdminMutationStore aams ? aams : null);
    }

    // ---------- account lifecycle ----------

    /**
     * Hot-swap display-only currency metadata (name / symbol) after a validated reload.
     * Ids, scales and the default currency must be identical; the guard rejects anything
     * else so transactional behaviour can never change under a reload.
     */
    public void replaceCurrencyDisplay(CurrencyRegistry candidate) {
        java.util.Objects.requireNonNull(candidate, "candidate");
        if (!currencies.defaultCurrencyId().equals(candidate.defaultCurrencyId())) {
            throw new IllegalArgumentException("refusing currency swap: default currency changed");
        }
        for (Currency c : currencies.all()) {
            if (!candidate.contains(c.id())) {
                throw new IllegalArgumentException("refusing currency swap: removed " + c.id());
            }
            Currency next = candidate.get(c.id());
            if (next.scale() != c.scale() || next.isDefault() != c.isDefault()) {
                throw new IllegalArgumentException("refusing currency swap: structural change " + c.id());
            }
        }
        for (Currency c : candidate.all()) {
            if (!currencies.contains(c.id())) {
                throw new IllegalArgumentException("refusing currency swap: added " + c.id());
            }
        }
        this.currencies = candidate;
    }

    public EconomyResult<AccountSnapshot> createAccount(UUID uuid, String ownerName) {
        Optional<Account> existing = accounts.load(uuid);
        if (existing.isPresent()) {
            primeCache(existing.get());
            return EconomyResult.success(existing.get().snapshot());
        }
        Map<String, Amount> initial = new HashMap<>();
        for (Currency c : currencies.all()) {
            initial.put(c.id(), c.id().equals(currencies.defaultCurrencyId()) ? startBalance : c.zero());
        }
        Account account = accounts.create(uuid, ownerName, initial);
        primeCache(account);
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

    /**
     * Storage-backed balance read. Refreshes the read cache on success so later synchronous
     * queries can be served without I/O; drops the owner's entry when the account is missing
     * or the read itself fails, so a hit can never mask a persistence problem.
     */
    public EconomyResult<Amount> getBalance(UUID uuid, String currencyId) {
        if (!currencies.contains(currencyId)) {
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(uuid);
        Optional<Account> acc;
        try {
            acc = accounts.load(uuid);
        } catch (PersistenceException e) {
            balanceCache.invalidate(uuid);
            throw e;
        }
        if (acc.isEmpty()) {
            balanceCache.invalidate(uuid);
            return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
        }
        Amount bal = acc.get().balanceOf(currencyId);
        Amount result = bal != null ? bal : currencies.get(currencyId).zero();
        balanceCache.putIfStamp(uuid, currencyId, result, stamp);
        return EconomyResult.success(result);
    }

    /**
     * Zero-I/O cached balance for synchronous callers that must never block on storage.
     * Empty on miss or unknown currency: callers fall back to a safe default instead of
     * waiting on the calling thread.
     */
    public Optional<Amount> cachedBalance(UUID uuid, String currencyId) {
        if (uuid == null || !currencies.contains(currencyId)) {
            return Optional.empty();
        }
        return balanceCache.get(uuid, currencyId);
    }

    /**
     * Background priming read for miss-triggered loaders (such as balance placeholders).
     * A hit returns the cached value without touching storage. A miss loads the account
     * from persistence and publishes the balance only when no invalidation landed during
     * the read <em>and</em> the entry is still absent, so a delayed reply can neither
     * resurrect a dropped entry nor overwrite a newer write that committed meanwhile.
     * Failures mirror {@link #getBalance}: a missing account or a persistence error drops
     * the owner's entry instead of masking storage state behind a hit.
     */
    public EconomyResult<Amount> primeBalanceIfAbsent(UUID uuid, String currencyId) {
        if (!currencies.contains(currencyId)) {
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        Optional<Amount> hit = balanceCache.get(uuid, currencyId);
        if (hit.isPresent()) {
            return EconomyResult.success(hit.get());
        }
        AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(uuid);
        Optional<Account> acc;
        try {
            acc = accounts.load(uuid);
        } catch (PersistenceException e) {
            balanceCache.invalidate(uuid);
            throw e;
        }
        if (acc.isEmpty()) {
            balanceCache.invalidate(uuid);
            return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
        }
        Amount bal = acc.get().balanceOf(currencyId);
        Amount result = bal != null ? bal : currencies.get(currencyId).zero();
        balanceCache.putIfStampIfAbsent(uuid, currencyId, result, stamp);
        return EconomyResult.success(result);
    }

    /** Drop one owner's cached balances (offline, write conflict, reload). */
    public void invalidateBalance(UUID uuid) {
        balanceCache.invalidate(uuid);
    }

    /**
     * Drop all cached balances (reload / restore / disable).
     *
     * <p>Accepted product contract: synchronous balance queries ({@code cachedBalance},
     * served to Vault without touching storage) miss after this call and fall back to the
     * safe default zero until the next persisted read or successful write re-primes the
     * entry. There is intentionally no synchronous refill — refilling on the calling
     * (usually server main) thread would reintroduce the blocking I/O the cache exists to
     * avoid. Re-priming happens only through ordinary persisted reads/writes.</p>
     */
    public void invalidateAllBalances() {
        balanceCache.invalidateAll();
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
        Optional<EconomyResult<Amount>> capRefusal = refuseOnRequestAmountCap(currencyId, amount);
        if (capRefusal.isPresent()) {
            return capRefusal.get();
        }
        ReentrantLock lock = locks.lockFor(uuid);
        lock.lock();
        try {
            AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(uuid);
            Optional<Account> acc = accounts.load(uuid);
            if (acc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
            }
            Optional<EconomyResult<Amount>> scaleRefusal =
                    refuseOnScaleMismatch(currencyId, acc.get().balanceOf(currencyId), amount);
            if (scaleRefusal.isPresent()) {
                return scaleRefusal.get();
            }
            Optional<EconomyResult<Amount>> storedCapRefusal =
                    refuseOnStoredAmountCap(currencyId, acc.get().balanceOf(currencyId));
            if (storedCapRefusal.isPresent()) {
                return storedCapRefusal.get();
            }
            Amount before = orZero(acc.get().balanceOf(currencyId), amount);
            Amount after = before.add(amount);
            Optional<EconomyResult<Amount>> resultCapRefusal =
                    refuseOnResultAmountCap(currencyId, after);
            if (resultCapRefusal.isPresent()) {
                return resultCapRefusal.get();
            }
            TransactionEvent event = new TransactionEvent(uuid, amount, TransactionType.DEPOSIT, before);
            events.publishPreCommit(event);
            if (event.isCancelled()) {
                return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED, "deposit cancelled");
            }
            Account updated = acc.get().deposit(currencyId, amount);
            try {
                accounts.save(acc.get(), updated);
            } catch (PersistenceException e) {
                balanceCache.invalidate(uuid);
                throw e;
            }
            // Same value previewed and cap-checked above; re-reading is unnecessary.
            balanceCache.putIfStamp(uuid, currencyId, after, stamp);
            Transaction tx = new Transaction(newId(), uuid, null, norm(currencyId), amount,
                    TransactionType.DEPOSIT, before, after, now(), "deposit");
            EconomyResult<Amount> result = commitAudit(tx, after);
            publishPostCommit(tx);
            return result;
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
        Optional<EconomyResult<Amount>> capRefusal = refuseOnRequestAmountCap(currencyId, amount);
        if (capRefusal.isPresent()) {
            return capRefusal.get();
        }
        ReentrantLock lock = locks.lockFor(accountId);
        lock.lock();
        try {
            AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(accountId);
            Optional<Account> acc = accounts.load(accountId);
            if (acc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + accountId);
            }
            Optional<EconomyResult<Amount>> scaleRefusal =
                    refuseOnScaleMismatch(currencyId, acc.get().balanceOf(currencyId), amount);
            if (scaleRefusal.isPresent()) {
                return scaleRefusal.get();
            }
            Optional<EconomyResult<Amount>> storedCapRefusal =
                    refuseOnStoredAmountCap(currencyId, acc.get().balanceOf(currencyId));
            if (storedCapRefusal.isPresent()) {
                return storedCapRefusal.get();
            }
            Amount before = orZero(acc.get().balanceOf(currencyId), amount);
            Amount after = before.add(amount);
            Optional<EconomyResult<Amount>> resultCapRefusal =
                    refuseOnResultAmountCap(currencyId, after);
            if (resultCapRefusal.isPresent()) {
                return resultCapRefusal.get();
            }
            TransactionEvent event = new TransactionEvent(accountId, amount, TransactionType.DEPOSIT, before);
            events.publishPreCommit(event);
            if (event.isCancelled()) {
                return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED, "deposit cancelled");
            }
            Account updated = acc.get().deposit(currencyId, amount);
            // Same value previewed and cap-checked above; re-reading is unnecessary.
            Transaction tx = new Transaction(newId(), accountId, null, norm(currencyId), amount,
                    TransactionType.DEPOSIT, before, after, now(), "banknote-deposit");
            try {
                RedemptionResult r = redemptionStore.redeemPrepared(nonce, updated, tx, debtPolicy);
                if (r.isCommitted()) {
                    balanceCache.putIfStamp(accountId, currencyId, r.balanceAfter(), stamp);
                    publishPostCommit(authoritativeRedeem(tx, r));
                    return EconomyResult.success(r.balanceAfter());
                }
                if (r.isReplay()) {
                    return EconomyResult.failure(EconomyError.REPLAY_DETECTED, "replay.detected");
                }
                if (r.isDebtLimitExceeded()) {
                    if (!debtPolicy.isAllowNegative()) {
                        return EconomyResult.failure(EconomyError.DEBT_DISABLED, "negative balance not allowed");
                    }
                    return EconomyResult.failure(EconomyError.DEBT_LIMIT_EXCEEDED, "debt limit exceeded");
                }
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "account missing at commit");
            } catch (PersistenceException e) {
                if (e instanceof IndeterminatePersistenceException indeterminate) {
                    // Uncertain outcome: neither proven committed nor proven rolled back.
                    // It must propagate so boundaries warn against retrying instead of
                    // reporting an ordinary failure.
                    balanceCache.invalidate(accountId);
                    throw indeterminate;
                }
                if (e.isCommitted()) {
                    // commit succeeded but post-commit cleanup failed; data is durable, must not retry
                    try {
                        Optional<Account> committedAcc = accounts.load(accountId);
                        if (committedAcc.isPresent()) {
                            Amount bal = committedAcc.get().balanceOf(currencyId);
                            if (bal != null) {
                                balanceCache.putIfStamp(accountId, currencyId, bal, stamp);
                                publishPostCommit(tx);
                                return EconomyResult.success(bal);
                            }
                        }
                    } catch (PersistenceException ignored) {
                        // provider may have been abandoned after post-commit failure; still committed
                    }
                    balanceCache.putIfStamp(accountId, currencyId, after, stamp);
                    publishPostCommit(tx);
                    return EconomyResult.success(after);
                }
                balanceCache.invalidate(accountId);
                LOG.log(Level.WARNING,
                        "Redeem failed before commit; answering AUDIT_FAILURE without changing anything",
                        e);
                return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------- withdraw compensation (uncertain handover) ----------

    /**
     * Void a minted banknote nonce and refund its value in ONE storage
     * transaction, for handovers whose placement is uncertain (the region
     * callback threw, so the note may or may not have landed). A redeemable
     * note and a refund can never exist side by side: either both happen
     * together, or the outcome stays undecided and nothing is refunded blindly.
     *
     * <p>Unlike a player-initiated deposit this is system-initiated, so no
     * pre-commit event fires: no listener can veto compensation and strand a
     * charged player without a note. Locking, balance-cache handling,
     * post-commit notification and the committed/indeterminate/uncommitted
     * failure split mirror {@link #redeemBanknote}.</p>
     *
     * @param nonce the minted note's nonce to void
     * @param accountId the account that was deducted and is refunded here
     * @param currencyId the currency of the original deduction
     * @param amount the strictly positive amount that left the account
     * @param redemptionStore the durable atomic store, never {@code null}
     * @param reason the audit reason recorded for the refund
     * @return the refund result; replay means the nonce is already gone (the
     *         note was redeemed or already voided) so no second refund happens
     * @throws IndeterminatePersistenceException when the void+refund may or may
     *         not have committed: never retry blindly, keep it pending instead
     */
    public EconomyResult<Amount> compensateUndeliveredNote(UUID nonce, UUID accountId,
                                                           String currencyId, Amount amount,
                                                           AtomicRedemptionStore redemptionStore,
                                                           String reason) {
        if (nonce == null) {
            return EconomyResult.failure(EconomyError.INVALID_AMOUNT, "nonce null");
        }
        if (redemptionStore == null) {
            return EconomyResult.failure(EconomyError.AUDIT_FAILURE, "redemption store missing");
        }
        if (reason == null || reason.isBlank()) {
            return EconomyResult.failure(EconomyError.INVALID_AMOUNT, "reason missing");
        }
        EconomyResult<Void> v = validateAmountPositive(amount);
        if (v.isFailure()) {
            return EconomyResult.failure(v.error(), v.message());
        }
        if (!currencies.contains(currencyId)) {
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        Optional<EconomyResult<Amount>> capRefusal = refuseOnRequestAmountCap(currencyId, amount);
        if (capRefusal.isPresent()) {
            return capRefusal.get();
        }
        ReentrantLock lock = locks.lockFor(accountId);
        lock.lock();
        try {
            AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(accountId);
            Optional<Account> acc = accounts.load(accountId);
            if (acc.isEmpty()) {
                // Leave the nonce unconsumed: the physical note stays redeemable
                // once the account exists, and no refund is fabricated.
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND,
                        "no account for " + accountId);
            }
            Optional<EconomyResult<Amount>> scaleRefusal =
                    refuseOnScaleMismatch(currencyId, acc.get().balanceOf(currencyId), amount);
            if (scaleRefusal.isPresent()) {
                return scaleRefusal.get();
            }
            Optional<EconomyResult<Amount>> storedCapRefusal =
                    refuseOnStoredAmountCap(currencyId, acc.get().balanceOf(currencyId));
            if (storedCapRefusal.isPresent()) {
                return storedCapRefusal.get();
            }
            Amount before = orZero(acc.get().balanceOf(currencyId), amount);
            Amount after = before.add(amount);
            Optional<EconomyResult<Amount>> resultCapRefusal =
                    refuseOnResultAmountCap(currencyId, after);
            if (resultCapRefusal.isPresent()) {
                return resultCapRefusal.get();
            }
            Account updated = acc.get().deposit(currencyId, amount);
            // Same value previewed and cap-checked above; re-reading is unnecessary.
            Transaction tx = new Transaction(newId(), accountId, null, norm(currencyId), amount,
                    TransactionType.DEPOSIT, before, after, now(), reason);
            try {
                RedemptionResult r = redemptionStore.redeemPrepared(nonce, updated, tx, debtPolicy);
                if (r.isCommitted()) {
                    balanceCache.putIfStamp(accountId, currencyId, r.balanceAfter(), stamp);
                    publishPostCommit(authoritativeRedeem(tx, r));
                    return EconomyResult.success(r.balanceAfter());
                }
                if (r.isReplay()) {
                    return EconomyResult.failure(EconomyError.REPLAY_DETECTED, "replay.detected");
                }
                if (r.isDebtLimitExceeded()) {
                    if (!debtPolicy.isAllowNegative()) {
                        return EconomyResult.failure(EconomyError.DEBT_DISABLED,
                                "negative balance not allowed");
                    }
                    return EconomyResult.failure(EconomyError.DEBT_LIMIT_EXCEEDED,
                            "debt limit exceeded");
                }
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "account missing at commit");
            } catch (PersistenceException e) {
                if (e instanceof IndeterminatePersistenceException indeterminate) {
                    // Uncertain outcome: neither proven committed nor proven rolled
                    // back. Propagate so the flow keeps it pending instead of
                    // refunding blindly or retrying.
                    balanceCache.invalidate(accountId);
                    throw indeterminate;
                }
                if (e.isCommitted()) {
                    // Void+refund durable, cleanup failed afterwards: report the
                    // refund as done without fabricating anything.
                    try {
                        Optional<Account> committedAcc = accounts.load(accountId);
                        if (committedAcc.isPresent()) {
                            Amount bal = committedAcc.get().balanceOf(currencyId);
                            if (bal != null) {
                                balanceCache.putIfStamp(accountId, currencyId, bal, stamp);
                                publishPostCommit(tx);
                                return EconomyResult.success(bal);
                            }
                        }
                    } catch (PersistenceException ignored) {
                        // Provider may have been abandoned after post-commit failure.
                    }
                    balanceCache.putIfStamp(accountId, currencyId, after, stamp);
                    publishPostCommit(tx);
                    return EconomyResult.success(after);
                }
                balanceCache.invalidate(accountId);
                LOG.log(Level.WARNING,
                        "Compensation failed before commit; answering AUDIT_FAILURE without changing anything",
                        e);
                return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
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
        Optional<EconomyResult<Amount>> capRefusal = refuseOnRequestAmountCap(currencyId, amount);
        if (capRefusal.isPresent()) {
            return capRefusal.get();
        }
        ReentrantLock lock = locks.lockFor(uuid);
        lock.lock();
        try {
            AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(uuid);
            Optional<Account> acc = accounts.load(uuid);
            if (acc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
            }
            Optional<EconomyResult<Amount>> scaleRefusal =
                    refuseOnScaleMismatch(currencyId, acc.get().balanceOf(currencyId), amount);
            if (scaleRefusal.isPresent()) {
                return scaleRefusal.get();
            }
            // A withdraw can land below minus-the-cap when debt is enabled with a
            // limit above the cap: preview the outcome and refuse before the
            // pre-commit event fires, so the answer is a service-level cap
            // diagnosis instead of a storage exception from the write guard.
            Optional<EconomyResult<Amount>> storedCapRefusal =
                    refuseOnStoredAmountCap(currencyId, acc.get().balanceOf(currencyId));
            if (storedCapRefusal.isPresent()) {
                return storedCapRefusal.get();
            }
            Amount before = orZero(acc.get().balanceOf(currencyId), amount);
            Amount after = before.subtract(amount);
            Optional<EconomyResult<Amount>> resultCapRefusal =
                    refuseOnResultAmountCap(currencyId, after);
            if (resultCapRefusal.isPresent()) {
                return resultCapRefusal.get();
            }
            TransactionEvent event = new TransactionEvent(uuid, amount, TransactionType.WITHDRAW, before);
            events.publishPreCommit(event);
            if (event.isCancelled()) {
                return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED, "withdraw cancelled");
            }
            if (!debtPolicy.allows(after)) {
                return rejectWithdraw();
            }
            Account updated = acc.get().withdraw(currencyId, amount);
            try {
                accounts.save(acc.get(), updated);
            } catch (PersistenceException e) {
                balanceCache.invalidate(uuid);
                throw e;
            }
            balanceCache.putIfStamp(uuid, currencyId, after, stamp);
            Transaction tx = new Transaction(newId(), uuid, null, norm(currencyId), amount,
                    TransactionType.WITHDRAW, before, after, now(), "withdraw");
            EconomyResult<Amount> result = commitAudit(tx, after);
            publishPostCommit(tx);
            return result;
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
        // The stored value becomes exactly the request, so the request check below
        // already bounds the outcome; no separate resulting-balance check is needed.
        Optional<EconomyResult<Amount>> capRefusal = refuseOnRequestAmountCap(currencyId, amount);
        if (capRefusal.isPresent()) {
            return capRefusal.get();
        }
        ReentrantLock lock = locks.lockFor(uuid);
        lock.lock();
        try {
            AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(uuid);
            Optional<Account> acc = accounts.load(uuid);
            if (acc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
            }
            Optional<EconomyResult<Amount>> scaleRefusal =
                    refuseOnScaleMismatch(currencyId, acc.get().balanceOf(currencyId), amount);
            if (scaleRefusal.isPresent()) {
                return scaleRefusal.get();
            }
            Optional<EconomyResult<Amount>> storedCapRefusal =
                    refuseOnStoredAmountCap(currencyId, acc.get().balanceOf(currencyId));
            if (storedCapRefusal.isPresent()) {
                return storedCapRefusal.get();
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
            try {
                accounts.save(acc.get(), updated);
            } catch (PersistenceException e) {
                balanceCache.invalidate(uuid);
                throw e;
            }
            balanceCache.putIfStamp(uuid, currencyId, amount, stamp);
            Transaction tx = new Transaction(newId(), uuid, null, norm(currencyId), amount,
                    TransactionType.SET, before, amount, now(), "set");
            EconomyResult<Amount> result = commitAudit(tx, amount);
            publishPostCommit(tx);
            return result;
        } finally {
            lock.unlock();
        }
    }

    // ---------- administrative mutations with operator audit ----------

    /**
     * Audited deposit for administrative commands. Runs the single-commit path in
     * {@link #adminMutation}. The ordinary non-audited {@link #deposit(UUID, String, Amount)}
     * keeps its existing success-with-{@code auditFailure} behaviour and is never used here.
     */
    public EconomyResult<Amount> deposit(UUID uuid, String currencyId, Amount amount, AdminAudit audit) {
        return adminMutation(uuid, currencyId, amount, TransactionType.DEPOSIT, audit);
    }

    /**
     * Audited withdrawal; same single-commit contract as
     * {@link #deposit(UUID, String, Amount, AdminAudit)}.
     */
    public EconomyResult<Amount> withdraw(UUID uuid, String currencyId, Amount amount, AdminAudit audit) {
        return adminMutation(uuid, currencyId, amount, TransactionType.WITHDRAW, audit);
    }

    /**
     * Audited balance assignment; same single-commit contract as
     * {@link #deposit(UUID, String, Amount, AdminAudit)}.
     */
    public EconomyResult<Amount> setBalance(UUID uuid, String currencyId, Amount amount, AdminAudit audit) {
        return adminMutation(uuid, currencyId, amount, TransactionType.SET, audit);
    }

    /**
     * Shared single-commit path for give, take and set. Under the account lock it checks the
     * request and the previewed outcome, fires the pre-commit event, then hands the prepared
     * record to {@code adminStore}. The store applies the balance effect and appends the audit
     * record in ONE storage transaction, so there is no separate save plus audit commit and no
     * compensation. A failure before the commit leaves balance, record and notification
     * untouched. Notification fires only after a committed result.
     */
    private EconomyResult<Amount> adminMutation(UUID uuid, String currencyId, Amount amount,
                                                TransactionType type, AdminAudit audit) {
        java.util.Objects.requireNonNull(audit, "audit");
        if (type == TransactionType.SET) {
            if (amount == null) {
                return EconomyResult.failure(EconomyError.INVALID_AMOUNT, "amount null");
            }
        } else {
            EconomyResult<Void> v = validateAmountPositive(amount);
            if (v.isFailure()) {
                return EconomyResult.failure(v.error(), v.message());
            }
        }
        if (!currencies.contains(currencyId)) {
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        Optional<EconomyResult<Amount>> capRefusal = refuseOnRequestAmountCap(currencyId, amount);
        if (capRefusal.isPresent()) {
            return capRefusal.get();
        }
        if (adminStore == null) {
            return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
        }
        ReentrantLock lock = locks.lockFor(uuid);
        lock.lock();
        try {
            AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(uuid);
            Optional<Account> acc;
            try {
                acc = accounts.load(uuid);
            } catch (PersistenceException e) {
                return adminFailedBeforeCommit(uuid, e);
            }
            if (acc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
            }
            Optional<EconomyResult<Amount>> scaleRefusal =
                    refuseOnScaleMismatch(currencyId, acc.get().balanceOf(currencyId), amount);
            if (scaleRefusal.isPresent()) {
                return scaleRefusal.get();
            }
            Optional<EconomyResult<Amount>> storedCapRefusal =
                    refuseOnStoredAmountCap(currencyId, acc.get().balanceOf(currencyId));
            if (storedCapRefusal.isPresent()) {
                return storedCapRefusal.get();
            }
            Amount before = orZero(acc.get().balanceOf(currencyId), amount);
            Amount after = previewAdminBalance(type, before, amount);
            Optional<EconomyResult<Amount>> resultCapRefusal =
                    refuseOnResultAmountCap(currencyId, after);
            if (resultCapRefusal.isPresent()) {
                return resultCapRefusal.get();
            }
            TransactionEvent event = new TransactionEvent(uuid, amount, type, before);
            events.publishPreCommit(event);
            if (event.isCancelled()) {
                return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED,
                        type.name().toLowerCase(Locale.ROOT) + " cancelled");
            }
            if (!debtPolicy.allows(after)) {
                return refuseAdminDebt(type);
            }
            Transaction prepared = new Transaction(newId(), uuid, null, norm(currencyId), amount, type,
                    before, after, now(), auditReason(audit), audit.operatorId(), audit.operatorName());
            AdminMutationResult result;
            try {
                result = adminStore.commitAdminMutation(prepared, debtPolicy);
            } catch (AtomicAdminMutationStore.CapExceededException e) {
                return EconomyResult.failure(EconomyError.AMOUNT_EXCEEDS_CAP, e.getMessage());
            } catch (AtomicAdminMutationStore.DebtLimitExceededException e) {
                return refuseAdminDebt(type);
            } catch (CommittedAdminMutationException e) {
                // The balance and the audit record are durable with the authoritative
                // live-derived balances; only the post-commit report failed. Notify
                // once with the real receipt and never retry or compensate.
                LOG.log(Level.WARNING,
                        "Administrative mutation committed but reported a storage error afterwards", e);
                Transaction authoritative = new Transaction(e.transactionId(),
                        prepared.accountId(), prepared.counterparty(), prepared.currencyId(),
                        prepared.amount(), prepared.type(), e.balanceBefore(), e.balanceAfter(),
                        prepared.timestamp(), prepared.reason(),
                        prepared.operatorId(), prepared.operatorName());
                balanceCache.putIfStamp(uuid, currencyId, e.balanceAfter(), stamp);
                publishPostCommit(authoritative);
                return EconomyResult.success(e.balanceAfter());
            } catch (IndeterminatePersistenceException e) {
                // Uncertain outcome: it must propagate so boundaries warn against retrying.
                balanceCache.invalidate(uuid);
                throw e;
            } catch (PersistenceException e) {
                if (e.isCommitted()) {
                    // Balance and record are durable but no authoritative receipt travelled
                    // with the failure. Re-read the committed record by its stable id and
                    // notify with it; when it cannot be safely read, propagate as
                    // committed-unknown instead of forging a preview notification.
                    Transaction authoritative = loadCommittedAdmin(prepared);
                    if (authoritative != null) {
                        LOG.log(Level.WARNING,
                                "Administrative mutation committed but reported a storage error afterwards", e);
                        balanceCache.putIfStamp(uuid, currencyId,
                                authoritative.balanceAfter(), stamp);
                        publishPostCommit(authoritative);
                        return EconomyResult.success(authoritative.balanceAfter());
                    }
                    LOG.log(Level.WARNING,
                            "Administrative mutation committed but its record could not be re-read;"
                                    + " propagating as committed-unknown without notification", e);
                    balanceCache.invalidate(uuid);
                    throw e;
                }
                return adminFailedBeforeCommit(uuid, e);
            }
            if (result.isCommitted()) {
                balanceCache.putIfStamp(uuid, currencyId, result.balanceAfter(), stamp);
                publishPostCommit(authoritativeAdmin(prepared, result));
                return EconomyResult.success(result.balanceAfter());
            }
            if (result.isDebtLimitExceeded()) {
                return refuseAdminDebt(type);
            }
            balanceCache.invalidate(uuid);
            return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + uuid);
        } finally {
            lock.unlock();
        }
    }

    private EconomyResult<Amount> adminFailedBeforeCommit(UUID uuid, PersistenceException e) {
        balanceCache.invalidate(uuid);
        LOG.log(Level.WARNING,
                "Administrative mutation failed before commit; answering AUDIT_FAILURE without changing anything",
                e);
        return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
    }

    private EconomyResult<Amount> refuseAdminDebt(TransactionType type) {
        if (type == TransactionType.WITHDRAW) {
            return rejectWithdraw();
        }
        if (!debtPolicy.isAllowNegative()) {
            return EconomyResult.failure(EconomyError.DEBT_DISABLED, "negative balance not allowed");
        }
        return EconomyResult.failure(EconomyError.DEBT_LIMIT_EXCEEDED, "debt limit exceeded");
    }

    private static Amount previewAdminBalance(TransactionType type, Amount before, Amount amount) {
        return switch (type) {
            case DEPOSIT -> before.add(amount);
            case WITHDRAW -> before.subtract(amount);
            case SET -> amount;
            default -> throw new IllegalArgumentException("unsupported administrative mutation: " + type);
        };
    }

    /**
     * Re-read the committed admin record by its stable id after a committed
     * cleanup failure that carried no receipt. Returns null when the record
     * cannot be safely read, so the caller propagates instead of forging.
     */
    private Transaction loadCommittedAdmin(Transaction prepared) {
        if (!(accounts instanceof com.smile.aceeconomy.ports.persistence.TransactionRepository store)) {
            return null;
        }
        try {
            for (Transaction row : store.loadByAccount(prepared.accountId())) {
                if (row.id().equals(prepared.id())) {
                    return row;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static String auditReason(AdminAudit audit) {
        return audit.reason() == null ? AdminAudit.DEFAULT_REASON : audit.reason();
    }

    private Transaction authoritativeAdmin(Transaction prepared, AdminMutationResult result) {
        return new Transaction(result.transactionId(), prepared.accountId(), prepared.counterparty(),
                prepared.currencyId(), prepared.amount(), prepared.type(),
                result.balanceBefore(), result.balanceAfter(), prepared.timestamp(), prepared.reason(),
                prepared.operatorId(), prepared.operatorName());
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
        Optional<EconomyResult<TransferResult>> requestCapRefusal =
                refuseOnRequestAmountCap(currencyId, amount);
        if (requestCapRefusal.isPresent()) {
            return requestCapRefusal.get();
        }
        locks.lockBoth(from, to);
        try {
            AccountBalanceCache.CacheStamp fromStamp = balanceCache.stampOf(from);
            AccountBalanceCache.CacheStamp toStamp = balanceCache.stampOf(to);
            Optional<Account> fromAcc = accounts.load(from);
            Optional<Account> toAcc = accounts.load(to);
            if (fromAcc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + from);
            }
            if (toAcc.isEmpty()) {
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "no account for " + to);
            }
            Amount fromStored = fromAcc.get().balanceOf(currencyId);
            Amount toStored = toAcc.get().balanceOf(currencyId);
            Optional<EconomyResult<TransferResult>> senderScaleRefusal =
                    refuseOnScaleMismatch(currencyId, fromStored, amount);
            if (senderScaleRefusal.isPresent()) {
                return senderScaleRefusal.get();
            }
            Optional<EconomyResult<TransferResult>> receiverScaleRefusal =
                    refuseOnScaleMismatch(currencyId, toStored, amount);
            if (receiverScaleRefusal.isPresent()) {
                return receiverScaleRefusal.get();
            }
            Optional<EconomyResult<TransferResult>> senderCapRefusal =
                    refuseOnStoredAmountCap(currencyId, fromStored);
            if (senderCapRefusal.isPresent()) {
                return senderCapRefusal.get();
            }
            Optional<EconomyResult<TransferResult>> receiverCapRefusal =
                    refuseOnStoredAmountCap(currencyId, toStored);
            if (receiverCapRefusal.isPresent()) {
                return receiverCapRefusal.get();
            }
            Amount fromBefore = orZero(fromStored, amount);
            Amount toBefore = orZero(toStored, amount);
            // Preview both legs before any event fires or either leg moves. The
            // receiver only grows; the sender can land below minus-the-cap when
            // debt is enabled with a limit above the cap. Refusing here keeps the
            // answer a service-level cap diagnosis instead of a storage exception
            // from the write guard.
            Optional<EconomyResult<TransferResult>> receiverResultCapRefusal =
                    refuseOnResultAmountCap(currencyId, toBefore.add(amount));
            if (receiverResultCapRefusal.isPresent()) {
                return receiverResultCapRefusal.get();
            }
            Optional<EconomyResult<TransferResult>> senderResultCapRefusal =
                    refuseOnResultAmountCap(currencyId, fromBefore.subtract(amount));
            if (senderResultCapRefusal.isPresent()) {
                return senderResultCapRefusal.get();
            }

            // pre-commit events: cancellation blocks the mutation entirely.
            // A throwing listener cancels the event with a diagnostic (handled inside
            // the publisher), so a broken observer can never silently approve a transfer.
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

            // Prefer atomic store when available: the store commits both balances and both
            // audit records in one storage transaction. The records are already durable at
            // this point, so the service must NOT call audit.record again (that would
            // duplicate them for shared backends). Post-commit observers are notified with
            // the real committed ids instead.
            if (transferStore != null) {
                try {
                    TransferResult result = transferStore.transfer(from, to, norm(currencyId), amount, debtPolicy);
                    publishTransferPostCommit(from, to, norm(currencyId), amount,
                            fromBefore, toBefore, result);
                    balanceCache.putIfStamp(from, currencyId, result.fromBalance(), fromStamp);
                    balanceCache.putIfStamp(to, currencyId, result.toBalance(), toStamp);
                    return EconomyResult.success(result);
                } catch (AtomicTransferStore.DebtLimitExceededException e) {
                    return EconomyResult.failure(rejectWithdrawError(), rejectWithdrawMessage());
                } catch (CommittedTransferException e) {
                    // The transfer committed but cleanup failed afterwards; the result carries
                    // the real ids, so success is reported without fabricating a receipt.
                    TransferResult committed = e.committedResult();
                    publishTransferPostCommit(from, to, norm(currencyId), amount,
                            fromBefore, toBefore, committed);
                    balanceCache.putIfStamp(from, currencyId, committed.fromBalance(), fromStamp);
                    balanceCache.putIfStamp(to, currencyId, committed.toBalance(), toStamp);
                    return EconomyResult.success(committed);
                } catch (PersistenceException e) {
                    balanceCache.invalidate(from);
                    balanceCache.invalidate(to);
                    if (e instanceof IndeterminatePersistenceException indeterminate) {
                        // Uncertain outcome: propagate so callers treat this as
                        // committed-unknown and never retry it as an uncommitted write.
                        throw indeterminate;
                    }
                    if (e.isCommitted()) {
                        // Indeterminate commit without the real ids: the data has moved but no
                        // receipt can be honestly built, so propagate instead of fabricating
                        // random ids. Callers must treat this as committed-unknown, never retry
                        // it as an uncommitted write.
                        throw e;
                    }
                    LOG.log(Level.WARNING,
                            "Transfer failed before commit;"
                                    + " answering AUDIT_FAILURE without changing anything",
                            e);
                    return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
                }
            }

            Amount fromAfter = fromBefore.subtract(amount);
            if (!debtPolicy.allows(fromAfter)) {
                return EconomyResult.failure(rejectWithdrawError(), rejectWithdrawMessage());
            }
            // receiver balance only increases; debt policy only constrains the sender
            Amount toAfter = toBefore.add(amount);

            Account updatedFrom = fromAcc.get().withdraw(currencyId, amount);
            Account updatedTo = toAcc.get().deposit(currencyId, amount);
            try {
                accounts.save(fromAcc.get(), updatedFrom);
                accounts.save(toAcc.get(), updatedTo);
            } catch (PersistenceException e) {
                balanceCache.invalidate(from);
                balanceCache.invalidate(to);
                throw e;
            }
            balanceCache.putIfStamp(from, currencyId, fromAfter, fromStamp);
            balanceCache.putIfStamp(to, currencyId, toAfter, toStamp);

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
            // Balances are committed at this point; observers are notified even when the
            // audit record itself failed (surfaced via auditFailure), and observer failures
            // can never change this result.
            publishPostCommit(outTx);
            publishPostCommit(inTx);
            return EconomyResult.success(result, auditError);
        } finally {
            locks.unlockBoth(from, to);
        }
    }

    // ---------- helpers ----------

    private void primeCache(Account account) {
        // Guarded publish: an offline/reload invalidation that lands while the account is
        // being created must not be resurrected by this priming write.
        AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(account.owner());
        account.balances().forEach((currencyId, balance) ->
                balanceCache.putIfStamp(account.owner(), currencyId, balance, stamp));
    }

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

    /**
     * Stored-scale compatibility gate. When a persisted balance (or, defensively, the
     * request amount) disagrees with the configured currency scale, the transaction is
     * refused with a currency-naming diagnosis before any event fires or balance moves,
     * so the stored data stays untouched. Callers invoke this right after loading the
     * account and before the pre-commit event.
     */
    private <T> Optional<EconomyResult<T>> refuseOnScaleMismatch(
            String currencyId, Amount stored, Amount requested) {
        return CurrencyScaleCheck.check(currencies, currencyId, stored, requested)
                .map(mismatch -> EconomyResult.<T>failure(
                        EconomyError.CURRENCY_SCALE_MISMATCH, mismatch.describe()));
    }

    /**
     * Public amount-cap gate, mirroring the stored-scale gate. The request side is
     * checked before any load (it needs no storage); the stored side is checked
     * right after loading alongside the scale gate, so a legacy over-cap row
     * refuses with a stored-data diagnosis instead of moving or being paved over.
     * Resulting balances (a credit onto a near-cap balance) are previewed with pure
     * arithmetic and checked before the pre-commit event fires.
     */
    private <T> Optional<EconomyResult<T>> refuseOnRequestAmountCap(
            String currencyId, Amount requested) {
        if (requested != null && AmountCap.exceeds(requested)) {
            return Optional.of(EconomyResult.<T>failure(
                    EconomyError.AMOUNT_EXCEEDS_CAP,
                    AmountCap.describeRequest(norm(currencyId), requested)));
        }
        return Optional.empty();
    }

    private <T> Optional<EconomyResult<T>> refuseOnStoredAmountCap(
            String currencyId, Amount stored) {
        if (stored != null && AmountCap.exceeds(stored)) {
            return Optional.of(EconomyResult.<T>failure(
                    EconomyError.AMOUNT_EXCEEDS_CAP,
                    AmountCap.describeStored(norm(currencyId), stored)));
        }
        return Optional.empty();
    }

    private <T> Optional<EconomyResult<T>> refuseOnResultAmountCap(
            String currencyId, Amount after) {
        if (after != null && AmountCap.exceeds(after)) {
            return Optional.of(EconomyResult.<T>failure(
                    EconomyError.AMOUNT_EXCEEDS_CAP,
                    AmountCap.describeResult(norm(currencyId), after)));
        }
        return Optional.empty();
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

    /**
     * Best-effort post-commit fan-out. The publisher already swallows observer failures;
     * this guard additionally ensures a misbehaving publisher implementation can never
     * change a committed result.
     */
    private void publishPostCommit(Transaction transaction) {
        try {
            events.publishPostCommit(transaction);
        } catch (Throwable ignored) {
            // Committed state is authoritative; notification failures stay diagnostics only.
        }
    }

    /**
     * Rebuild the committed transfer records for post-commit observers from the store's
     * authoritative result. Uses the real committed ids — never fabricated — and the
     * pre-commit balances observed under the transfer locks.
     */
    private void publishTransferPostCommit(UUID from, UUID to, String currencyId, Amount amount,
                                           Amount fromBefore, Amount toBefore, TransferResult result) {
        Instant stamp = now();
        Transaction outTx = new Transaction(result.outTransactionId(), from, to, currencyId, amount,
                TransactionType.TRANSFER_OUT, fromBefore, result.fromBalance(), stamp, "transfer-out");
        Transaction inTx = new Transaction(result.inTransactionId(), to, from, currencyId, amount,
                TransactionType.TRANSFER_IN, toBefore, result.toBalance(), stamp, "transfer-in");
        publishPostCommit(outTx);
        publishPostCommit(inTx);
    }

    /**
     * Rebuild the committed redeem record with the store's authoritative balances. The id
     * is the one the service handed to the store, which durable backends persist as-is.
     */
    private Transaction authoritativeRedeem(Transaction prepared, RedemptionResult result) {
        return new Transaction(prepared.id(), prepared.accountId(), prepared.counterparty(),
                prepared.currencyId(), prepared.amount(), prepared.type(),
                result.balanceBefore(), result.balanceAfter(), prepared.timestamp(), prepared.reason());
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
