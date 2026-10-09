package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.CurrencyScaleCheck;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.Clock;
import com.smile.aceeconomy.ports.IdempotencyGuard;
import com.smile.aceeconomy.ports.TransactionEventPublisher;
import com.smile.aceeconomy.ports.operations.ImportOptions;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.persistence.AtomicImportStore;
import com.smile.aceeconomy.ports.persistence.CommittedImportException;
import com.smile.aceeconomy.ports.persistence.ImportCommitResult;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * Essentials/CMI normalized import boundary.
 *
 * <p>Accepts already-normalized {@link ImportRecord} values (the parsing of vendor export files and
 * vendor-file discovery live outside this isolated slice). For each record it validates the
 * currency and amount, then either reports what would happen ({@code dryRun}) or applies it by
 * setting the account balance to the imported amount.</p>
 *
 * <p>Guarantees on the atomic path (an {@link AtomicImportStore} is wired, as production
 * does):</p>
 * <ul>
 *   <li><b>Dry-run</b> performs zero writes and consumes no idempotency state.</li>
 *   <li><b>One storage transaction per record</b> — the idempotency key, the balance
 *       effect (creating the account first when allowed) and the audit record commit
 *       together or not at all. There is no compensating rollback on top, so a
 *       compensation failure can never be swallowed into a half-applied state: an
 *       undecided storage failure is reported per record, never as success.</li>
 *   <li><b>Rerun idempotency</b> — each record is keyed by {@code source:sourceRecordId};
 *       a rerun of an already-applied record is reported as skipped, not re-applied. An
 *       uncommitted failure leaves the key unconsumed, so a retry still reports
 *       {@code FAILED} and then applies instead of a duplicate skip.</li>
 *   <li><b>No lost updates</b> — the store derives the effect from the live row inside
 *       the same transaction, so a general transaction that commits first is observed,
 *       never overwritten by a stale import snapshot. Same-key races are decided
 *       first-writer-wins at the storage level, not by the JVM mutex.</li>
 *   <li><b>Post-commit fan-out only after success</b> — observers and the balance-cache
 *       invalidation run only for committed records. A failed bypass write still drops
 *       the cached entry so a hit can never mask a persistence problem.</li>
 *   <li><b>Failure isolation</b> — a malformed or failing record yields a per-record failure and
 *       processing continues; the overall report is only "fully successful" when no record failed.</li>
 *   <li><b>Never partially claims success</b> — {@link ImportReport#fullySuccessful()} is false if
 *       any record failed.</li>
 * </ul>
 *
 * <p>Without an {@link AtomicImportStore} (legacy constructor, used only by tests with
 * standalone fake repositories that cannot share one storage transaction) the service falls
 * back to sequential claim-then-write steps under one JVM-wide mutex with best-effort
 * balance restoration. That fallback keeps the historical residual: the idempotency key
 * stays consumed after a post-claim failure and a newly created account cannot be removed.
 * Production never uses it.</p>
 */
public final class ImportService {

    /**
     * Serializes non-dry-run imports that have no atomic store (legacy fallback only).
     * The atomic path needs no JVM mutex: same-key races are decided first-writer-wins
     * inside the storage transaction, which is also what keeps concurrent imports of
     * different records on one account from interleaving.
     */
    private static final Object IMPORT_MUTEX = new Object();

    private static final Logger LOG = Logger.getLogger(ImportService.class.getName());

    /**
     * Fixed operator-facing message for an undecided import outcome. The write may or may
     * not have committed, so the message never claims either: it tells the operator to
     * check the account history instead of retrying blindly. The original throwable, with
     * its cause chain, is recorded on the diagnostic log.
     */
    private static final String UNCERTAIN_OUTCOME =
            "apply uncertain: the write may or may not have committed;"
                    + " verify the account history before retrying";

    private final CurrencyRegistry currencies;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final Clock clock;
    private final IdempotencyGuard idempotency;
    /**
     * Best-effort balance-cache invalidation for every account this import touches.
     * Production wires the application read cache here because this service persists
     * directly and bypasses {@code EconomyService}; without it a cached Vault read keeps
     * serving the pre-import balance. Null means no cache is attached (legacy callers).
     */
    private final Consumer<UUID> cacheInvalidation;
    /**
     * Atomic single-record commit boundary. Null selects the legacy sequential fallback
     * (tests with standalone fake repositories only); production always wires the same
     * backend instance that also serves the account, transaction and nonce ports.
     */
    private final AtomicImportStore importStore;
    /**
     * Post-commit fan-out for committed import records (leaderboard invalidation,
     * notifications). Only fired after a commit succeeds, never for cancelled or
     * uncommitted records. Null means no observers are attached (legacy callers).
     */
    private final TransactionEventPublisher events;

    public ImportService(CurrencyRegistry currencies, AccountRepository accounts,
                         TransactionRepository transactions, Clock clock, IdempotencyGuard idempotency) {
        this(currencies, accounts, transactions, clock, idempotency, null);
    }

    public ImportService(CurrencyRegistry currencies, AccountRepository accounts,
                         TransactionRepository transactions, Clock clock, IdempotencyGuard idempotency,
                         Consumer<UUID> cacheInvalidation) {
        this(currencies, accounts, transactions, clock, idempotency, cacheInvalidation, null, null);
    }

    public ImportService(CurrencyRegistry currencies, AccountRepository accounts,
                         TransactionRepository transactions, Clock clock, IdempotencyGuard idempotency,
                         Consumer<UUID> cacheInvalidation, AtomicImportStore importStore,
                         TransactionEventPublisher events) {
        this.currencies = Objects.requireNonNull(currencies, "currencies");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.cacheInvalidation = cacheInvalidation;
        this.importStore = importStore;
        this.events = events;
    }

    public ImportReport importRecords(List<ImportRecord> records, ImportOptions options) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(options, "options");

        if (options.dryRun() || importStore != null) {
            return runAll(records, options);
        }
        synchronized (IMPORT_MUTEX) {
            return runAll(records, options);
        }
    }

    private ImportReport runAll(List<ImportRecord> records, ImportOptions options) {
        List<ImportRecordResult> results = new ArrayList<>();
        int applied = 0;
        int skipped = 0;
        int failed = 0;

        for (ImportRecord r : records) {
            ImportRecordResult result = process(r, options);
            results.add(result);
            switch (result.status()) {
                case APPLIED -> applied++;
                case SKIPPED_DUPLICATE -> skipped++;
                case FAILED -> failed++;
            }
        }

        boolean fullySuccessful = failed == 0;
        return new ImportReport(options.dryRun(), applied, skipped, failed, fullySuccessful,
                List.copyOf(results));
    }

    private ImportRecordResult process(ImportRecord r, ImportOptions options) {
        // Structural validation (record itself is already non-null with non-blank ids by construction).
        if (!currencies.contains(r.currencyId())) {
            return ImportRecordResult.failed(r, "unknown currency: " + r.currencyId());
        }
        if (r.amount() == null || r.amount().isNegative()) {
            return ImportRecordResult.failed(r, "amount must be non-negative");
        }
        if (importStore != null) {
            return processAtomic(r, options);
        }
        return processLegacy(r, options);
    }

    /**
     * One storage transaction per record: the key, the balance effect and the audit record
     * commit together or not at all. Every check that performs no write stays before the
     * commit, so a validation failure consumes nothing and a retry still reports
     * {@code FAILED} instead of a duplicate skip.
     */
    private ImportRecordResult processAtomic(ImportRecord r, ImportOptions options) {
        UUID key = idempotencyKey(r);

        if (options.dryRun()) {
            // Zero writes; just report what would happen.
            if (idempotency.isConsumed(key)) {
                return ImportRecordResult.skipped(r, "already applied (dry-run)");
            }
            return ImportRecordResult.applied(r, null, "would apply (dry-run)");
        }

        Currency cur = currencies.get(r.currencyId());
        Account live;
        try {
            live = accounts.load(r.accountUuid()).orElse(null);
        } catch (RuntimeException e) {
            notifyInvalidated(r.accountUuid());
            return ImportRecordResult.failed(r, "apply failed: " + e.getMessage());
        }
        if (live == null && !options.createMissingAccounts()) {
            return ImportRecordResult.failed(r, "account does not exist and createMissingAccounts=false");
        }
        // Stored-scale compatibility gate, before any write: a persisted balance whose
        // scale disagrees with the configured currency refuses the whole record with a
        // currency-naming diagnosis, leaving stored data and the idempotency key untouched.
        Amount stored = live == null ? null : live.balanceOf(cur.id());
        var mismatch = CurrencyScaleCheck.check(currencies, cur.id(), stored, r.amount());
        if (mismatch.isPresent()) {
            return ImportRecordResult.failed(r, mismatch.get().describe());
        }

        Account prepared = live == null ? newAccountFromRecord(r, cur)
                : live.setBalance(cur.id(), r.amount());
        Amount provisionalBefore = stored != null ? stored : cur.zero();
        Transaction preparedTx = new Transaction(UUID.randomUUID(), r.accountUuid(), null,
                cur.id(), r.amount(), TransactionType.SET, provisionalBefore, r.amount(),
                clock.instant(), "import:" + r.source().name());

        ImportCommitResult result;
        try {
            result = importStore.importPrepared(key, prepared, preparedTx,
                    options.createMissingAccounts());
        } catch (CommittedImportException committed) {
            // The data is durable but a post-commit cleanup step failed: report the real
            // receipt instead of failing a committed write or retrying it into a duplicate.
            Transaction authoritative = authoritativeImport(preparedTx,
                    committed.balanceBefore(), committed.balanceAfter(), committed.transactionId());
            publishPostCommit(authoritative);
            notifyInvalidated(r.accountUuid());
            return ImportRecordResult.applied(r, committed.transactionId(), null);
        } catch (IndeterminatePersistenceException indeterminate) {
            // Uncertain outcome: neither proven committed nor proven rolled back. Report a
            // per-record failure that warns against blind retry instead of an ordinary
            // failure, and keep processing the remaining records.
            notifyInvalidated(r.accountUuid());
            LOG.log(Level.WARNING,
                    "Import outcome is uncertain (commit response lost);"
                            + " reporting a per-record failure instead of success",
                    indeterminate);
            String detail = indeterminate.getMessage() == null ? "" : indeterminate.getMessage();
            return ImportRecordResult.failed(r, UNCERTAIN_OUTCOME + " — " + detail);
        } catch (PersistenceException e) {
            if (e.isCommitted()) {
                // Committed without a receipt: the data has moved but no honest id can be
                // built, so report failure with verification guidance instead of fabricating
                // a receipt or letting a retry duplicate the effect.
                notifyInvalidated(r.accountUuid());
                LOG.log(Level.WARNING,
                        "Import committed without a receipt; reporting failure with guidance",
                        e);
                return ImportRecordResult.failed(r, UNCERTAIN_OUTCOME
                        + " — " + e.getMessage());
            }
            notifyInvalidated(r.accountUuid());
            LOG.log(Level.WARNING,
                    "Import failed before commit; answering FAILED without changing anything", e);
            return ImportRecordResult.failed(r, "apply failed: " + e.getMessage());
        } catch (RuntimeException e) {
            notifyInvalidated(r.accountUuid());
            return ImportRecordResult.failed(r, "apply failed: " + e.getMessage());
        }

        if (result.isCommitted()) {
            // The store derived before/after from the live row: rebuild the audit view from
            // the authoritative values instead of trusting the provisional pre-read, so a
            // general transaction that committed first is never silently overwritten.
            Transaction authoritative = authoritativeImport(preparedTx,
                    result.balanceBefore(), result.balanceAfter(), result.transactionId());
            publishPostCommit(authoritative);
            notifyInvalidated(r.accountUuid());
            return ImportRecordResult.applied(r, result.transactionId(), null);
        }
        if (result.isReplay()) {
            return ImportRecordResult.skipped(r, "already applied");
        }
        if (result.isAccountMissing()) {
            return ImportRecordResult.failed(r, "account does not exist and createMissingAccounts=false");
        }
        return ImportRecordResult.failed(r, result.diagnosis());
    }

    private ImportRecordResult processLegacy(ImportRecord r, ImportOptions options) {
        UUID key = idempotencyKey(r);

        if (options.dryRun()) {
            // Zero writes; just report what would happen.
            if (idempotency.isConsumed(key)) {
                return ImportRecordResult.skipped(r, "already applied (dry-run)");
            }
            return ImportRecordResult.applied(r, null, "would apply (dry-run)");
        }

        // Every check that performs no write stays before the claim, so a
        // validation failure consumes nothing and a retry still reports
        // FAILED instead of a duplicate skip.
        boolean accountExists;
        try {
            accountExists = accounts.exists(r.accountUuid());
        } catch (RuntimeException e) {
            return ImportRecordResult.failed(r, "apply failed: " + e.getMessage());
        }
        if (!accountExists && !options.createMissingAccounts()) {
            return ImportRecordResult.failed(r, "account does not exist and createMissingAccounts=false");
        }

        if (idempotency.isConsumed(key)) {
            return ImportRecordResult.skipped(r, "already applied");
        }
        if (!idempotency.consume(key)) {
            // Lost a claim race with a concurrent apply of the same record:
            // the winner owns the write, so report a duplicate skip and append
            // nothing. Deliberately before any write, so no duplicate balance
            // or audit record can exist.
            return ImportRecordResult.skipped(r, "already applied");
        }

        try {
            Currency cur = currencies.get(r.currencyId());
            if (accounts.exists(r.accountUuid())) {
                Account existing = accounts.load(r.accountUuid()).orElseThrow();
                Amount before = existing.balanceOf(cur.id());
                if (before == null) {
                    before = cur.zero();
                }
                Account updated = existing.setBalance(cur.id(), r.amount());
                accounts.save(existing, updated);
                UUID txId;
                try {
                    Amount after = updated.balanceOf(cur.id());
                    txId = appendImportRecord(r, cur, before, after);
                } catch (RuntimeException appendFailure) {
                    // Best-effort rollback of the balance write; the claim
                    // above cannot be undone, which the class javadoc discloses.
                    try {
                        accounts.save(updated, existing);
                    } catch (RuntimeException ignored) {
                        // The balance is already dubious; never mask the
                        // original append failure with a rollback failure.
                    }
                    throw appendFailure;
                }
                notifyInvalidated(r.accountUuid());
                return ImportRecordResult.applied(r, txId, null);
            }
            if (!options.createMissingAccounts()) {
                return ImportRecordResult.failed(r, "account does not exist and createMissingAccounts=false");
            }
            Map<String, Amount> initial = new HashMap<>();
            for (Currency c : currencies.all()) {
                initial.put(c.id(), c.zero());
            }
            initial.put(cur.id(), r.amount());
            accounts.create(r.accountUuid(),
                    r.ownerName() != null ? r.ownerName() : r.accountUuid().toString(), initial);
            UUID txId = appendImportRecord(r, cur, cur.zero(), r.amount());
            notifyInvalidated(r.accountUuid());
            return ImportRecordResult.applied(r, txId, null);
        } catch (RuntimeException e) {
            // Isolation: a validation failure before the claim above never
            // consumed anything and stays retryable; a failure after the claim
            // reports per-record failure without claiming success.
            // A failed write may still have touched persistence, so drop any cached hit
            // rather than letting a later sync read mask the problem.
            notifyInvalidated(r.accountUuid());
            return ImportRecordResult.failed(r, "apply failed: " + e.getMessage());
        }
    }

    private Account newAccountFromRecord(ImportRecord r, Currency cur) {
        Map<String, Amount> initial = new HashMap<>();
        for (Currency c : currencies.all()) {
            initial.put(c.id(), c.zero());
        }
        initial.put(cur.id(), r.amount());
        return Account.create(r.accountUuid(),
                r.ownerName() != null ? r.ownerName() : r.accountUuid().toString(), initial);
    }

    /**
     * Rebuild the committed import record with the store's authoritative balances. The id
     * is the one the service handed to the store, which durable backends persist as-is.
     */
    private Transaction authoritativeImport(Transaction prepared, Amount before, Amount after,
                                            UUID txId) {
        return new Transaction(txId, prepared.accountId(), prepared.counterparty(),
                Currency.normalizeId(prepared.currencyId()), prepared.amount(),
                TransactionType.SET, before, after, prepared.timestamp(), prepared.reason());
    }

    /**
     * Best-effort post-commit fan-out. Only ever called after a commit succeeded; observer
     * failures stay diagnostics and can never change the committed result.
     */
    private void publishPostCommit(Transaction transaction) {
        if (events == null) {
            return;
        }
        try {
            events.publishPostCommit(transaction);
        } catch (Throwable ignored) {
            // Committed state is authoritative; notification failures stay diagnostics only.
        }
    }

    private void notifyInvalidated(UUID accountUuid) {
        if (cacheInvalidation == null || accountUuid == null) {
            return;
        }
        try {
            cacheInvalidation.accept(accountUuid);
        } catch (RuntimeException ignored) {
            // Best-effort: cache housekeeping must never break the import outcome.
        }
    }

    private UUID appendImportRecord(ImportRecord r, Currency cur, Amount before, Amount after) {
        Transaction t = new Transaction(
                UUID.randomUUID(), r.accountUuid(), null, cur.id(), r.amount(),
                TransactionType.SET, before, after, clock.instant(), "import:" + r.source().name());
        try {
            transactions.append(t);
        } catch (PersistenceException e) {
            throw new IllegalStateException("failed to append import record: " + e.getMessage(), e);
        }
        return t.id();
    }

    private static UUID idempotencyKey(ImportRecord r) {
        String composite = r.source().name() + ":" + r.sourceRecordId();
        return UUID.nameUUIDFromBytes(composite.getBytes(StandardCharsets.UTF_8));
    }
}
