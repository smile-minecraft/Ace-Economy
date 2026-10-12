package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.AccountSnapshot;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.AmountCap;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
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
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.AtomicAdminMutationStore;
import com.smile.aceeconomy.ports.persistence.AtomicTransferStore;
import com.smile.aceeconomy.ports.persistence.CommittedAdminMutationException;
import com.smile.aceeconomy.ports.persistence.CommittedIssuanceDebitException;
import com.smile.aceeconomy.ports.persistence.CommittedIssuanceRefundException;
import com.smile.aceeconomy.ports.persistence.CommittedTransferException;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;
import com.smile.aceeconomy.ports.persistence.IssuanceDebitResult;
import com.smile.aceeconomy.ports.persistence.IssuanceRefundResult;
import com.smile.aceeconomy.ports.persistence.NonceStore;
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
     * Safety-control gate. Null means the legacy open gate used only by tests
     * that never connect a control; production Bootstrap always binds a real
     * control and ledger so no initiating path can miss the gate. Every
     * initiating path below takes a {@code MutationPermit} from this control
     * before any per-account lock and refuses on {@link #gateRefusal} before
     * any debit; reads and previews never consult it.
     */
    private volatile com.smile.aceeconomy.operations.security.SecurityControlService securityControl;
    /** Trusted-refund ledger seam; same lifecycle rule as the control above. */
    private volatile com.smile.aceeconomy.operations.internal.TrustedRefundLedger refundLedger;
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

    /**
     * Bind the safety-control gate. Every initiating path consults the bound
     * control under a {@code MutationPermit} taken before any per-account
     * lock; reads and previews never consult it. A null control keeps the
     * legacy open gate for tests that predate the freeze.
     */
    public void bindSecurityControl(
            com.smile.aceeconomy.operations.security.SecurityControlService control) {
        this.securityControl = control;
    }

    /**
     * Bind the trusted-refund ledger. The refund path claims a ledger entry
     * for every presented proof; without a bound ledger the refund fails
     * closed with no money movement. Production always binds the same ledger
     * instance as the banknote flow.
     */
    public void bindRefundLedger(
            com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger) {
        this.refundLedger = ledger;
    }

    /**
     * Early gate diagnosis for initiating mutations. Empty when no control is
     * bound (legacy open gate) or when the control allows the mutation.
     */
    private Optional<com.smile.aceeconomy.domain.EconomyError> gateRefusal(UUID... accountIds) {
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        if (control == null) {
            return Optional.empty();
        }
        return control.checkInitiating(accountIds);
    }

    /**
     * Keep one minimal blocked-attempt record per refusal. Best-effort and
     * never throwing: the refusal stands even when recording fails.
     */
    private void recordRefusal(String operation, UUID target,
                               com.smile.aceeconomy.domain.EconomyError error) {
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        if (control == null) {
            return;
        }
        String category = switch (error) {
            case ACCOUNT_FROZEN ->
                    com.smile.aceeconomy.domain.BlockedControlAttempt.CATEGORY_FROZEN;
            case GLOBAL_READ_ONLY ->
                    com.smile.aceeconomy.domain.BlockedControlAttempt.CATEGORY_READONLY;
            case CONTROL_UNAVAILABLE ->
                    com.smile.aceeconomy.domain.BlockedControlAttempt.CATEGORY_UNAVAILABLE;
            default -> null;
        };
        if (category == null) {
            return;
        }
        try {
            control.recordBlockedAttempt(
                    com.smile.aceeconomy.domain.BlockedControlAttempt.of(operation, target,
                            category, control.controlVersion(), null, null, now()));
        } catch (RuntimeException ignored) {
            // The refusal stands; the evidence record is best-effort here.
        }
    }

    private String refusalMessage(com.smile.aceeconomy.domain.EconomyError error) {
        return switch (error) {
            case ACCOUNT_FROZEN ->
                    "account is frozen; transaction refused, nothing changed";
            case GLOBAL_READ_ONLY ->
                    "server is read-only; transaction refused, nothing changed";
            case CONTROL_UNAVAILABLE -> {
                com.smile.aceeconomy.operations.security.SecurityControlService control =
                        securityControl;
                String diagnosis = control == null ? "" : control.loadDiagnosis();
                yield diagnosis.isBlank()
                        ? "safety control unavailable; transaction refused, nothing changed"
                        : "safety control unavailable; transaction refused, nothing changed: "
                                + diagnosis;
            }
            default -> "transaction refused";
        };
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
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            if (control != null) {
                Optional<EconomyError> refusal = control.checkInitiating(uuid);
                if (refusal.isPresent()) {
                    recordRefusal("deposit", uuid, refusal.get());
                    return EconomyResult.failure(refusal.get(), refusalMessage(refusal.get()));
                }
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
        } finally {
            if (permit != null) {
                permit.close();
            }
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
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            if (control != null) {
                Optional<EconomyError> refusal = control.checkInitiating(accountId);
                if (refusal.isPresent()) {
                    recordRefusal("redeem", accountId, refusal.get());
                    return EconomyResult.failure(refusal.get(), refusalMessage(refusal.get()));
                }
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
        } finally {
            if (permit != null) {
                permit.close();
            }
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
     * <p>This direct overload stays behind the initiating gate: a frozen
     * account or a read-only server refuses it before anything moves. The
     * banknote pipeline's uncertain handover instead uses the trusted
     * overload, which consumes a single-use ledger authorization and is
     * exempt from the gate so a committed charge is never stranded.</p>
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
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            if (control != null) {
                Optional<EconomyError> refusal = control.checkInitiating(accountId);
                if (refusal.isPresent()) {
                    recordRefusal("compensate", accountId, refusal.get());
                    return EconomyResult.failure(refusal.get(), refusalMessage(refusal.get()));
                }
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
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    /**
     * Void a minted banknote nonce and refund its value in ONE storage
     * transaction for a pipeline-authorized uncertain handover, exempt from
     * the freeze and read-only gate. The bound
     * {@link com.smile.aceeconomy.operations.internal.TrustedRefundLedger}
     * must hold an exact unused entry (nonce, account, currency, amount)
     * recorded by the banknote pipeline after its deduction committed and its
     * handover threw at an unknown placement. Unknown, undeducted, tampered,
     * replayed, spent, or already-settled proofs are refused with no money
     * movement, and so is every proof when no ledger is bound: an unbound
     * compensation fails closed instead of trusting the presented proof.
     *
     * <p>Unlike a player-initiated deposit this is system-initiated, so no
     * pre-commit event fires: no listener can veto compensation and strand a
     * charged player without a note. A {@code MutationPermit} is still taken
     * before any per-account lock so a concurrent freeze can never overtake
     * the commit; the initiating gate itself is never consulted, exactly like
     * {@link #refundFailedMint}. The legacy direct overload stays
     * gate-checked for ordinary initiators.</p>
     *
     * <p>Claim lifecycle: the entry is claimed up front and settles exactly
     * once. A committed void+refund (even one whose audit record carries a
     * failure) settles it as refunded and never releases it. A replay, an
     * indeterminate outcome, or an already-committed outcome settles it as
     * uncertain — retained, never re-authorized for consumption, so a voided
     * note can never be redeemed and no second refund can land. Only a
     * definitely-uncommitted outcome (missing account, debt refusal, or
     * uncommitted storage failure, all leaving the nonce unconsumed) releases
     * the same claim so a later retry with the exact same fields can
     * proceed.</p>
     *
     * @param compensation the pipeline's four-field proof; authorizes nothing
     *                     by itself
     * @param redemptionStore the durable atomic store, never {@code null}
     * @param reason the audit reason recorded for the refund
     * @return the refund result; replay means the nonce is already gone (the
     *         note was redeemed or already voided) so no second refund happens
     * @throws IndeterminatePersistenceException when the void+refund may or may
     *         not have committed: never retry blindly, keep it pending instead
     */
    public EconomyResult<Amount> compensateUndeliveredNote(
            com.smile.aceeconomy.operations.internal.UncertainDeliveryCompensation compensation,
            AtomicRedemptionStore redemptionStore, String reason) {
        if (compensation == null) {
            return EconomyResult.failure(EconomyError.INVALID_AMOUNT, "proof null");
        }
        UUID nonce = compensation.nonce();
        UUID accountId = compensation.accountId();
        String currencyId = compensation.currencyId();
        Amount amount = compensation.amount();
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
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            if (accounts instanceof com.smile.aceeconomy.ports.persistence.NonceStore nonces) {
                boolean consumed;
                try {
                    consumed = nonces.isConsumed(nonce);
                } catch (PersistenceException e) {
                    balanceCache.invalidate(accountId);
                    throw e;
                }
                if (consumed) {
                    return EconomyResult.failure(EconomyError.REPLAY_DETECTED,
                            "note already redeemed; refund refused");
                }
            }
            com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger = refundLedger;
            if (ledger == null) {
                return EconomyResult.failure(EconomyError.AUDIT_FAILURE,
                        "refund ledger is not bound; refund refused with no money movement");
            }
            if (!ledger.tryConsume(nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN)) {
                return EconomyResult.failure(EconomyError.INVALID_AMOUNT,
                        "no matching refund authorization; refund refused");
            }
            ReentrantLock lock = locks.lockFor(accountId);
            lock.lock();
            try {
            AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(accountId);
            Optional<Account> acc = accounts.load(accountId);
            if (acc.isEmpty()) {
                // Leave the nonce unconsumed: the physical note stays redeemable
                // once the account exists, and no refund is fabricated.
                releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND,
                        "no account for " + accountId);
            }
            Optional<EconomyResult<Amount>> scaleRefusal =
                    refuseOnScaleMismatch(currencyId, acc.get().balanceOf(currencyId), amount);
            if (scaleRefusal.isPresent()) {
                releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
                return scaleRefusal.get();
            }
            Optional<EconomyResult<Amount>> storedCapRefusal =
                    refuseOnStoredAmountCap(currencyId, acc.get().balanceOf(currencyId));
            if (storedCapRefusal.isPresent()) {
                releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
                return storedCapRefusal.get();
            }
            Amount before = orZero(acc.get().balanceOf(currencyId), amount);
            Amount after = before.add(amount);
            Optional<EconomyResult<Amount>> resultCapRefusal =
                    refuseOnResultAmountCap(currencyId, after);
            if (resultCapRefusal.isPresent()) {
                releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
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
                    settleRefunded(ledger, nonce);
                    publishPostCommit(authoritativeRedeem(tx, r));
                    return EconomyResult.success(r.balanceAfter());
                }
                if (r.isReplay()) {
                    // The nonce is already gone: redeemed or voided before.
                    // Nothing moved here, so settle terminally and refuse any
                    // second refund instead of releasing for a retry.
                    settleUncertain(ledger, nonce);
                    return EconomyResult.failure(EconomyError.REPLAY_DETECTED, "replay.detected");
                }
                if (r.isAccountMissing()) {
                    // The store left the nonce unconsumed: definitely
                    // uncommitted, so the same claim may be retried later.
                    releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
                    return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND,
                            "account missing at commit");
                }
                if (r.isDebtLimitExceeded()) {
                    // Refused inside the commit without consuming the nonce:
                    // definitely uncommitted, so the same claim may be retried.
                    releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
                    if (!debtPolicy.isAllowNegative()) {
                        return EconomyResult.failure(EconomyError.DEBT_DISABLED,
                                "negative balance not allowed");
                    }
                    return EconomyResult.failure(EconomyError.DEBT_LIMIT_EXCEEDED,
                            "debt limit exceeded");
                }
                releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
                return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "account missing at commit");
            } catch (PersistenceException e) {
                if (e instanceof IndeterminatePersistenceException indeterminate) {
                    // Uncertain outcome: neither proven committed nor proven rolled
                    // back. Settle the claim terminally so it is retained, never
                    // re-authorized, and propagate so the flow keeps it pending
                    // instead of refunding blindly or retrying.
                    settleUncertain(ledger, nonce);
                    balanceCache.invalidate(accountId);
                    throw indeterminate;
                }
                if (e.isCommitted()) {
                    // Void+refund durable, cleanup failed afterwards: settle as
                    // refunded and report the refund as done without
                    // fabricating anything.
                    settleRefunded(ledger, nonce);
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
                releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.DELIVERY_UNCERTAIN);
                LOG.log(Level.WARNING,
                        "Compensation failed before commit; answering AUDIT_FAILURE without changing anything",
                        e);
                return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
            }
            } finally {
                lock.unlock();
            }
        } finally {
            if (permit != null) {
                permit.close();
            }
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
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            if (control != null) {
                Optional<EconomyError> refusal = control.checkInitiating(uuid);
                if (refusal.isPresent()) {
                    recordRefusal("withdraw", uuid, refusal.get());
                    return EconomyResult.failure(refusal.get(), refusalMessage(refusal.get()));
                }
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
        } finally {
            if (permit != null) {
                permit.close();
            }
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
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            if (control != null) {
                Optional<EconomyError> refusal = control.checkInitiating(uuid);
                if (refusal.isPresent()) {
                    recordRefusal("set", uuid, refusal.get());
                    return EconomyResult.failure(refusal.get(), refusalMessage(refusal.get()));
                }
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
        } finally {
            if (permit != null) {
                permit.close();
            }
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
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            if (control != null) {
                Optional<EconomyError> refusal = control.checkInitiating(uuid);
                if (refusal.isPresent()) {
                    recordRefusal("admin-" + type.name().toLowerCase(java.util.Locale.ROOT),
                            uuid, refusal.get());
                    return EconomyResult.failure(refusal.get(), refusalMessage(refusal.get()));
                }
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
        } finally {
            if (permit != null) {
                permit.close();
            }
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
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            if (control != null) {
                Optional<EconomyError> refusal = control.checkInitiating(from, to);
                if (refusal.isPresent()) {
                    UUID target = control.isFrozen(from) ? from
                            : control.isFrozen(to) ? to : null;
                    recordRefusal("transfer", target, refusal.get());
                    return EconomyResult.failure(refusal.get(), refusalMessage(refusal.get()));
                }
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
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    // ---------- trusted failed-mint refund ----------

    /** Audit reason recorded for a trusted failed-mint refund. */
    static final String FAILED_MINT_REFUND_REASON = "failed-mint-refund";

    /**
     * Credit a proven failed ticket mint through the same atomic commit as an
     * ordinary deposit, exempt from the freeze and read-only gate. The ledger
     * must hold an exact unused entry (nonce, account, currency, amount)
     * recorded by the ticket pipeline after its deduction committed and its
     * mint observably failed without exposing a redeemable note. Unknown,
     * undeducted, already-redeemed, tampered, replayed, spent, or uncertain
     * proofs are refused with no money movement, and so is every proof when
     * no ledger is bound: an unbound refund fails closed instead of trusting
     * the presented proof.
     *
     * <p>Claim lifecycle: the entry is claimed up front and settles exactly
     * once. A committed refund (even one whose audit record carries a failure)
     * settles it as refunded and never releases it. An uncertain or
     * already-committed outcome settles it as uncertain — retained, never
     * re-authorized for consumption. Only a definitely-uncommitted storage
     * failure releases the same claim so a later retry can proceed.
     */
    public EconomyResult<Amount> refundFailedMint(
            com.smile.aceeconomy.operations.internal.FailedMintRefund proof) {
        if (proof == null) {
            return EconomyResult.failure(EconomyError.INVALID_AMOUNT, "proof null");
        }
        UUID accountId = proof.accountId();
        String currencyId = proof.currencyId();
        Amount amount = proof.amount();
        UUID nonce = proof.mintNonce();
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
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            if (accounts instanceof com.smile.aceeconomy.ports.persistence.NonceStore nonces) {
                boolean consumed;
                try {
                    consumed = nonces.isConsumed(nonce);
                } catch (PersistenceException e) {
                    balanceCache.invalidate(accountId);
                    throw e;
                }
                if (consumed) {
                    return EconomyResult.failure(EconomyError.REPLAY_DETECTED,
                            "note already redeemed; refund refused");
                }
            }
            com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger = refundLedger;
            if (ledger == null) {
                return EconomyResult.failure(EconomyError.AUDIT_FAILURE,
                        "refund ledger is not bound; refund refused with no money movement");
            }
            if (!ledger.tryConsume(nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.FAILED_MINT)) {
                return EconomyResult.failure(EconomyError.INVALID_AMOUNT,
                        "no matching refund authorization; refund refused");
            }
            ReentrantLock lock = locks.lockFor(accountId);
            lock.lock();
            try {
                AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(accountId);
                Optional<Account> acc = accounts.load(accountId);
                if (acc.isEmpty()) {
                    releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.FAILED_MINT);
                    return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND,
                            "no account for " + accountId);
                }
                Optional<EconomyResult<Amount>> scaleRefusal =
                        refuseOnScaleMismatch(currencyId, acc.get().balanceOf(currencyId), amount);
                if (scaleRefusal.isPresent()) {
                    releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.FAILED_MINT);
                    return scaleRefusal.get();
                }
                Optional<EconomyResult<Amount>> storedCapRefusal =
                        refuseOnStoredAmountCap(currencyId, acc.get().balanceOf(currencyId));
                if (storedCapRefusal.isPresent()) {
                    releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.FAILED_MINT);
                    return storedCapRefusal.get();
                }
                Amount before = orZero(acc.get().balanceOf(currencyId), amount);
                Amount after = before.add(amount);
                Optional<EconomyResult<Amount>> resultCapRefusal =
                        refuseOnResultAmountCap(currencyId, after);
                if (resultCapRefusal.isPresent()) {
                    releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.FAILED_MINT);
                    return resultCapRefusal.get();
                }
                // System-initiated compensation like the undelivered-note path:
                // no pre-commit event fires, so no listener can veto the refund
                // and strand a charged player without a note.
                Account updated = acc.get().deposit(currencyId, amount);
                try {
                    accounts.save(acc.get(), updated);
                } catch (PersistenceException e) {
                    if (e instanceof IndeterminatePersistenceException indeterminate) {
                        // Uncertain outcome: settle the claim as uncertain so it
                        // is retained, never re-authorized for consumption, and
                        // a retry can never send twice. Keep it pending instead
                        // of refunding blindly.
                        settleUncertain(ledger, nonce);
                        balanceCache.invalidate(accountId);
                        throw indeterminate;
                    }
                    if (e.isCommitted()) {
                        // The credit is durable with only post-commit cleanup
                        // failed: settle as refunded and report it as done
                        // without fabricating anything.
                        settleRefunded(ledger, nonce);
                        try {
                            Optional<Account> committedAcc = accounts.load(accountId);
                            if (committedAcc.isPresent()) {
                                Amount bal = committedAcc.get().balanceOf(currencyId);
                                if (bal != null) {
                                    Transaction authoritativeTx = new Transaction(newId(),
                                            accountId, null, norm(currencyId), amount,
                                            TransactionType.DEPOSIT, before, bal, now(),
                                            FAILED_MINT_REFUND_REASON);
                                    balanceCache.putIfStamp(accountId, currencyId, bal, stamp);
                                    publishPostCommit(authoritativeTx);
                                    return EconomyResult.success(bal);
                                }
                            }
                        } catch (PersistenceException ignored) {
                            // Provider abandoned after post-commit failure; still committed.
                        }
                        balanceCache.putIfStamp(accountId, currencyId, after, stamp);
                        publishPostCommit(new Transaction(newId(), accountId, null,
                                norm(currencyId), amount, TransactionType.DEPOSIT, before,
                                after, now(), FAILED_MINT_REFUND_REASON));
                        return EconomyResult.success(after);
                    }
                    // Definitely uncommitted: nothing moved, so the same claim
                    // is released and may be tried again later.
                    balanceCache.invalidate(accountId);
                    releaseForRetry(ledger, nonce, accountId, currencyId, amount, com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose.FAILED_MINT);
                    LOG.log(Level.WARNING,
                            "Refund failed before commit; answering AUDIT_FAILURE without changing anything",
                            e);
                    return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
                }
                balanceCache.putIfStamp(accountId, currencyId, after, stamp);
                Transaction tx = new Transaction(newId(), accountId, null, norm(currencyId),
                        amount, TransactionType.DEPOSIT, before, after, now(),
                        FAILED_MINT_REFUND_REASON);
                EconomyResult<Amount> result = commitAudit(tx, after);
                // The balance moved (an audit-record failure travels on the
                // success, never as a second spend), so the claim settles as
                // refunded and is never released.
                settleRefunded(ledger, nonce);
                publishPostCommit(tx);
                return result;
            } finally {
                lock.unlock();
            }
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    /**
     * Release a claimed entry for a later retry with the exact same fields.
     * Only called when the commit definitely did not happen, so the release
     * can never fund two credits. Best-effort: the refusal stands either way.
     */
    private void releaseForRetry(
            com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger,
            UUID nonce, UUID accountId, String currencyId, Amount amount,
            com.smile.aceeconomy.operations.internal.TrustedRefundLedger.RefundPurpose purpose) {
        if (ledger != null) {
            try {
                ledger.releaseClaim(nonce, accountId, currencyId, amount, purpose);
            } catch (RuntimeException ignored) {
                // The retry hint is best-effort; the refusal stands either way.
            }
        }
    }

    /**
     * Settle a claimed entry as refunded once money has moved. Best-effort:
     * the credit is already durable, so a settle failure is only logged.
     */
    private void settleRefunded(
            com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger,
            UUID nonce) {
        if (ledger != null) {
            try {
                ledger.markRefunded(nonce);
            } catch (RuntimeException settleFailure) {
                LOG.log(Level.SEVERE,
                        "Refund settled on storage but the ledger claim could not be marked"
                                + " refunded; the nonce stays claimed and retries stay refused",
                        settleFailure);
            }
        }
    }

    /**
     * Settle a claimed entry as uncertain once the outcome can no longer be
     * proven uncommitted. Best-effort: the indeterminate failure still
     * propagates, so a settle failure is only logged.
     */
    private void settleUncertain(
            com.smile.aceeconomy.operations.internal.TrustedRefundLedger ledger,
            UUID nonce) {
        if (ledger != null) {
            try {
                ledger.markUncertain(nonce);
            } catch (RuntimeException settleFailure) {
                LOG.log(Level.SEVERE,
                        "Refund outcome is uncertain and the ledger claim could not be marked"
                                + " uncertain; retries stay refused",
                        settleFailure);
            }
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

    // ---------- durable banknote issuance (phase-1 skeleton) ----------

    /**
     * Issue one banknote deduction atomically through the durable issuance
     * store: the {@code PREPARED} record is published before any deduction,
     * then the deduction commits together with the
     * {@code PREPARED -> DEBIT_COMMITTED} flip in ONE storage transaction.
     * There is no window where the charge succeeded but the issuance still
     * reads {@code PREPARED}, and no {@code api.withdraw} plus standalone
     * mark ever runs. Ordinary new issuance stays behind the freeze /
     * read-only gate; only the trusted recovery refund below is exempt, and
     * only for durably proven deductions.
     *
     * <p>When the service account view does not know the account (tests and
     * split wiring), the store stays authoritative: pre-checks that need the
     * live balance are skipped and the commit decides. In production both
     * views are the same backend instance, so the pre-checks and the commit
     * always agree.</p>
     *
     * @param accountId the account to deduct; never {@code null}
     * @param currencyId the currency id; never {@code null}
     * @param amount the strictly positive amount to deduct; never {@code null}
     * @param nonce the single-use issuance nonce; never {@code null}
     * @param issuanceStore the durable atomic store; never {@code null}
     * @return the deduction result
     * @throws IndeterminatePersistenceException when the debit may or may not
     *         have committed: never retry blindly, verify first
     */
    public EconomyResult<Amount> issueBanknote(UUID accountId, String currencyId, Amount amount,
                                               UUID nonce,
                                               AtomicBanknoteIssuanceStore issuanceStore) {
        java.util.Objects.requireNonNull(accountId, "accountId");
        java.util.Objects.requireNonNull(currencyId, "currencyId");
        java.util.Objects.requireNonNull(amount, "amount");
        java.util.Objects.requireNonNull(nonce, "nonce");
        java.util.Objects.requireNonNull(issuanceStore, "issuanceStore");
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
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            if (control != null) {
                Optional<EconomyError> refusal = control.checkInitiating(accountId);
                if (refusal.isPresent()) {
                    recordRefusal("issue", accountId, refusal.get());
                    return EconomyResult.failure(refusal.get(), refusalMessage(refusal.get()));
                }
            }
            ReentrantLock lock = locks.lockFor(accountId);
            lock.lock();
            try {
                AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(accountId);
                Optional<Account> acc;
                try {
                    acc = accounts.load(accountId);
                } catch (PersistenceException e) {
                    balanceCache.invalidate(accountId);
                    LOG.log(Level.WARNING,
                            "Issuance failed before commit; answering AUDIT_FAILURE without changing anything",
                            e);
                    return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
                }
                Amount previewBefore = null;
                Amount previewAfter = null;
                if (acc.isPresent()) {
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
                    previewBefore = orZero(acc.get().balanceOf(currencyId), amount);
                    try {
                        previewAfter = previewBefore.subtract(amount);
                    } catch (IllegalArgumentException scaleMismatch) {
                        return EconomyResult.failure(EconomyError.CURRENCY_SCALE_MISMATCH,
                                "currency scale mismatch for " + norm(currencyId));
                    }
                    Optional<EconomyResult<Amount>> resultCapRefusal =
                            refuseOnResultAmountCap(currencyId, previewAfter);
                    if (resultCapRefusal.isPresent()) {
                        return resultCapRefusal.get();
                    }
                    if (!debtPolicy.allows(previewAfter)) {
                        return rejectWithdraw();
                    }
                }
                Amount eventBefore = previewBefore != null ? previewBefore : Amount.zero(amount.scale());
                TransactionEvent event = new TransactionEvent(accountId, amount,
                        TransactionType.WITHDRAW, eventBefore);
                events.publishPreCommit(event);
                if (event.isCancelled()) {
                    return EconomyResult.failure(EconomyError.TRANSACTION_CANCELLED,
                            "withdraw cancelled");
                }
                try {
                    issuanceStore.prepare(BanknoteIssuance.prepared(
                            nonce, accountId, norm(currencyId), amount, now()));
                } catch (IndeterminatePersistenceException e) {
                    balanceCache.invalidate(accountId);
                    throw e;
                } catch (PersistenceException e) {
                    if (e.getMessage() != null && e.getMessage().contains("payload mismatch")) {
                        return EconomyResult.failure(EconomyError.INVALID_AMOUNT,
                                "issuance payload mismatch; nothing was changed");
                    }
                    balanceCache.invalidate(accountId);
                    LOG.log(Level.WARNING,
                            "Issuance preparation failed before commit;"
                                    + " answering AUDIT_FAILURE without changing anything",
                            e);
                    return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
                }
                Amount txBefore = previewBefore != null ? previewBefore : Amount.zero(amount.scale());
                Amount txAfter;
                try {
                    txAfter = previewAfter != null ? previewAfter : txBefore.subtract(amount);
                } catch (IllegalArgumentException scaleMismatch) {
                    return EconomyResult.failure(EconomyError.CURRENCY_SCALE_MISMATCH,
                            "currency scale mismatch for " + norm(currencyId));
                }
                Transaction prepared = new Transaction(newId(), accountId, null,
                        norm(currencyId), amount, TransactionType.WITHDRAW, txBefore, txAfter,
                        now(), BanknoteIssuanceReasons.issueReason(nonce));
                IssuanceDebitResult result;
                try {
                    result = issuanceStore.commitDebit(nonce, prepared, debtPolicy);
                } catch (CommittedIssuanceDebitException e) {
                    LOG.log(Level.WARNING,
                            "Issuance debit committed but reported a storage error afterwards", e);
                    Transaction authoritative = new Transaction(e.debitTransactionId(),
                            prepared.accountId(), prepared.counterparty(), prepared.currencyId(),
                            prepared.amount(), prepared.type(), e.balanceBefore(), e.balanceAfter(),
                            prepared.timestamp(), prepared.reason(),
                            prepared.operatorId(), prepared.operatorName());
                    balanceCache.putIfStamp(accountId, currencyId, e.balanceAfter(), stamp);
                    publishPostCommit(authoritative);
                    return EconomyResult.success(e.balanceAfter());
                } catch (IndeterminatePersistenceException e) {
                    balanceCache.invalidate(accountId);
                    throw e;
                } catch (PersistenceException e) {
                    if (e.isCommitted()) {
                        balanceCache.invalidate(accountId);
                        throw e;
                    }
                    balanceCache.invalidate(accountId);
                    LOG.log(Level.WARNING,
                            "Issuance failed before commit; answering AUDIT_FAILURE without changing anything",
                            e);
                    return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
                }
                if (result.isCommitted()) {
                    Transaction authoritative = new Transaction(result.debitTransactionId(),
                            prepared.accountId(), prepared.counterparty(), prepared.currencyId(),
                            prepared.amount(), prepared.type(), result.balanceBefore(),
                            result.balanceAfter(), prepared.timestamp(), prepared.reason(),
                            prepared.operatorId(), prepared.operatorName());
                    balanceCache.putIfStamp(accountId, currencyId, result.balanceAfter(), stamp);
                    publishPostCommit(authoritative);
                    return EconomyResult.success(result.balanceAfter());
                }
                balanceCache.invalidate(accountId);
                if (result.isReplay()) {
                    return EconomyResult.failure(EconomyError.REPLAY_DETECTED, "replay.detected");
                }
                if (result.isDebtLimitExceeded()) {
                    return rejectWithdraw();
                }
                if (result.isCapExceeded()) {
                    return EconomyResult.failure(EconomyError.AMOUNT_EXCEEDS_CAP,
                            AmountCap.describeResult(norm(currencyId), amount));
                }
                if (result.isScaleMismatch()) {
                    return EconomyResult.failure(EconomyError.CURRENCY_SCALE_MISMATCH,
                            "currency scale mismatch for " + norm(currencyId));
                }
                if (result.isAccountMissing()) {
                    return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND,
                            "no account for " + accountId);
                }
                return EconomyResult.failure(EconomyError.INVALID_AMOUNT,
                        "issuance debit refused; nothing was changed");
            } finally {
                lock.unlock();
            }
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    /**
     * Refund one proven pending issuance for startup recovery: the nonce is
     * consumed, the system refund is appended and the issuance is final-marked
     * in ONE storage transaction. Authority comes from the live stored
     * issuance plus the durable debit transaction, never from a
     * caller-supplied string and never from a memory ledger.
     *
     * <p>This trusted path takes the safety-control permit for sequencing but
     * stays exempt from the freeze / read-only initiating gate: a durably
     * proven charge must never strand its player. Ordinary new issuance above
     * stays gated. No pre-commit event fires (system-initiated, so no
     * listener can veto compensation and strand a charged player).</p>
     *
     * @param nonce the issuance nonce to refund; never {@code null}
     * @param issuanceStore the durable atomic store; never {@code null}
     * @return the refund result
     * @throws IndeterminatePersistenceException when the void+refund may or
     *         may not have committed: never retry blindly, verify first
     */
    public EconomyResult<Amount> refundIssuanceForRecovery(
            UUID nonce,
            AtomicBanknoteIssuanceStore issuanceStore) {
        java.util.Objects.requireNonNull(nonce, "nonce");
        java.util.Objects.requireNonNull(issuanceStore, "issuanceStore");
        BanknoteIssuance stored;
        try {
            Optional<BanknoteIssuance> found = issuanceStore.findByNonce(nonce);
            if (found.isEmpty()) {
                return EconomyResult.failure(EconomyError.AUDIT_FAILURE,
                        "no issuance record for nonce; nothing was changed");
            }
            stored = found.get();
        } catch (IndeterminatePersistenceException e) {
            throw e;
        } catch (PersistenceException e) {
            LOG.log(Level.WARNING,
                    "Issuance recovery refund failed before commit;"
                            + " answering AUDIT_FAILURE without changing anything",
                    e);
            return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
        }
        UUID accountId = stored.accountId();
        String currencyId = stored.currencyId();
        Amount amount = stored.amount();
        if (stored.status() == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.PREPARED) {
            try {
                issuanceStore.markAborted(nonce,
                        "recovery: prepared without deduction; aborting with no refund");
            } catch (RuntimeException ignored) {
                LOG.log(Level.WARNING,
                        "Issuance abort mark failed for nonce {0}; refusal stands", nonce);
            }
            return EconomyResult.failure(EconomyError.INVALID_AMOUNT,
                    "no deduction to refund; nothing was changed");
        }
        if (!stored.isRefundablePending()) {
            if (stored.status().isUnfinished()) {
                quarantineIssuance(issuanceStore, nonce,
                        "recovery: undecidable issuance state with no durable debit"
                                + " proof; quarantined with no money movement");
            }
            return EconomyResult.failure(EconomyError.AUDIT_FAILURE,
                    "issuance is not refundable; nothing was changed");
        }
        if (!currencies.contains(currencyId)) {
            quarantineIssuance(issuanceStore, nonce,
                    "recovery: unknown currency; quarantined with no money movement");
            return EconomyResult.failure(EconomyError.CURRENCY_NOT_FOUND, currencyId);
        }
        Optional<EconomyResult<Amount>> capRefusal = refuseOnRequestAmountCap(currencyId, amount);
        if (capRefusal.isPresent()) {
            quarantineIssuance(issuanceStore, nonce,
                    "recovery: amount over cap; quarantined with no money movement");
            return capRefusal.get();
        }
        com.smile.aceeconomy.operations.security.SecurityControlService control = securityControl;
        com.smile.aceeconomy.operations.security.SecurityControlService.MutationPermit permit =
                control == null ? null : control.acquireMutationPermit();
        try {
            ReentrantLock lock = locks.lockFor(accountId);
            lock.lock();
            try {
                AccountBalanceCache.CacheStamp stamp = balanceCache.stampOf(accountId);
                Optional<Account> acc;
                try {
                    acc = accounts.load(accountId);
                } catch (PersistenceException e) {
                    balanceCache.invalidate(accountId);
                    LOG.log(Level.WARNING,
                            "Issuance recovery refund failed before commit;"
                                    + " answering AUDIT_FAILURE without changing anything",
                            e);
                    return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
                }
                if (acc.isPresent()) {
                    Optional<EconomyResult<Amount>> scaleRefusal =
                            refuseOnScaleMismatch(currencyId, acc.get().balanceOf(currencyId), amount);
                    if (scaleRefusal.isPresent()) {
                        quarantineIssuance(issuanceStore, nonce,
                                "recovery: scale mismatch; quarantined with no money movement");
                        return scaleRefusal.get();
                    }
                    Optional<EconomyResult<Amount>> storedCapRefusal =
                            refuseOnStoredAmountCap(currencyId, acc.get().balanceOf(currencyId));
                    if (storedCapRefusal.isPresent()) {
                        quarantineIssuance(issuanceStore, nonce,
                                "recovery: stored balance over cap; quarantined with no money movement");
                        return storedCapRefusal.get();
                    }
                    Amount before = orZero(acc.get().balanceOf(currencyId), amount);
                    Amount after;
                    try {
                        after = before.add(amount);
                    } catch (IllegalArgumentException scaleMismatch) {
                        quarantineIssuance(issuanceStore, nonce,
                                "recovery: scale mismatch; quarantined with no money movement");
                        return EconomyResult.failure(EconomyError.CURRENCY_SCALE_MISMATCH,
                                "currency scale mismatch for " + norm(currencyId));
                    }
                    Optional<EconomyResult<Amount>> resultCapRefusal =
                            refuseOnResultAmountCap(currencyId, after);
                    if (resultCapRefusal.isPresent()) {
                        quarantineIssuance(issuanceStore, nonce,
                                "recovery: resulting balance over cap; quarantined with no money movement");
                        return resultCapRefusal.get();
                    }
                }
                Transaction prepared = new Transaction(newId(), accountId, null,
                        norm(currencyId), amount, TransactionType.DEPOSIT, null, null,
                        now(), BanknoteIssuanceReasons.recoveryRefundReason(nonce), null,
                        BanknoteIssuanceReasons.SYSTEM_RECOVERY_OPERATOR);
                IssuanceRefundResult result;
                try {
                    result = issuanceStore.commitRecoveryRefund(nonce, prepared, debtPolicy);
                } catch (CommittedIssuanceRefundException e) {
                    LOG.log(Level.WARNING,
                            "Issuance recovery refund committed but reported a storage error afterwards",
                            e);
                    Transaction authoritative = new Transaction(e.refundTransactionId(),
                            prepared.accountId(), prepared.counterparty(), prepared.currencyId(),
                            prepared.amount(), prepared.type(), e.balanceBefore(), e.balanceAfter(),
                            prepared.timestamp(), prepared.reason(),
                            prepared.operatorId(), prepared.operatorName());
                    balanceCache.putIfStamp(accountId, currencyId, e.balanceAfter(), stamp);
                    publishPostCommit(authoritative);
                    return EconomyResult.success(e.balanceAfter());
                } catch (IndeterminatePersistenceException e) {
                    balanceCache.invalidate(accountId);
                    throw e;
                } catch (PersistenceException e) {
                    if (e.isCommitted()) {
                        balanceCache.invalidate(accountId);
                        throw e;
                    }
                    balanceCache.invalidate(accountId);
                    LOG.log(Level.WARNING,
                            "Issuance recovery refund failed before commit;"
                                    + " answering AUDIT_FAILURE without changing anything",
                            e);
                    return EconomyResult.failure(EconomyError.AUDIT_FAILURE, STORAGE_UNAVAILABLE);
                }
                if (result.isCommitted()) {
                    Transaction authoritative = new Transaction(result.refundTransactionId(),
                            prepared.accountId(), prepared.counterparty(), prepared.currencyId(),
                            prepared.amount(), prepared.type(), result.balanceBefore(),
                            result.balanceAfter(), prepared.timestamp(), prepared.reason(),
                            prepared.operatorId(), prepared.operatorName());
                    balanceCache.putIfStamp(accountId, currencyId, result.balanceAfter(), stamp);
                    publishPostCommit(authoritative);
                    return EconomyResult.success(result.balanceAfter());
                }
                balanceCache.invalidate(accountId);
                if (result.isReplay()) {
                    Optional<Account> reloaded;
                    try {
                        reloaded = accounts.load(accountId);
                    } catch (PersistenceException e) {
                        return EconomyResult.failure(EconomyError.AUDIT_FAILURE,
                                STORAGE_UNAVAILABLE);
                    }
                    Amount balance = reloaded.flatMap(a -> Optional.ofNullable(a.balanceOf(currencyId)))
                            .orElse(null);
                    if (balance != null) {
                        balanceCache.putIfStamp(accountId, currencyId, balance, stamp);
                        return EconomyResult.success(balance);
                    }
                    return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND,
                            "no account for " + accountId);
                }
                if (result.isAlreadyRedeemed()) {
                    return EconomyResult.failure(EconomyError.REPLAY_DETECTED,
                            "note already redeemed; refund refused");
                }
                if (result.isDebtLimitExceeded()) {
                    if (!debtPolicy.isAllowNegative()) {
                        return EconomyResult.failure(EconomyError.DEBT_DISABLED,
                                "negative balance not allowed");
                    }
                    return EconomyResult.failure(EconomyError.DEBT_LIMIT_EXCEEDED,
                            "debt limit exceeded");
                }
                if (result.isCapExceeded()) {
                    quarantineIssuance(issuanceStore, nonce,
                            "recovery: amount over cap; quarantined with no money movement");
                    return EconomyResult.failure(EconomyError.AMOUNT_EXCEEDS_CAP,
                            AmountCap.describeResult(norm(currencyId), amount));
                }
                if (result.isScaleMismatch()) {
                    quarantineIssuance(issuanceStore, nonce,
                            "recovery: scale mismatch; quarantined with no money movement");
                    return EconomyResult.failure(EconomyError.CURRENCY_SCALE_MISMATCH,
                            "currency scale mismatch for " + norm(currencyId));
                }
                if (result.isAccountMissing()) {
                    return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND,
                            "no account for " + accountId);
                }
                quarantineIssuance(issuanceStore, nonce,
                        "recovery: undecidable issuance state; quarantined with no money movement");
                return EconomyResult.failure(EconomyError.AUDIT_FAILURE,
                        "issuance recovery refused; nothing was changed");
            } finally {
                lock.unlock();
            }
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    /**
     * Best-effort quarantine for an undecidable issuance record. Never throws:
     * the refusal stands even when the mark itself fails.
     */
    private void quarantineIssuance(AtomicBanknoteIssuanceStore issuanceStore, UUID nonce,
                                    String diagnosis) {
        try {
            issuanceStore.markQuarantined(nonce, diagnosis);
        } catch (RuntimeException ignored) {
            LOG.log(Level.WARNING,
                    "Issuance quarantine mark failed for nonce {0}; refusal stands", nonce);
        }
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
