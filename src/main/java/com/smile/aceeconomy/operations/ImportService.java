package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.Clock;
import com.smile.aceeconomy.ports.IdempotencyGuard;
import com.smile.aceeconomy.ports.operations.ImportOptions;
import com.smile.aceeconomy.ports.operations.ImportRecord;
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

/**
 * Essentials/CMI normalized import boundary.
 *
 * <p>Accepts already-normalized {@link ImportRecord} values (the parsing of vendor export files and
 * vendor-file discovery live outside this isolated slice). For each record it validates the
 * currency and amount, then either reports what would happen ({@code dryRun}) or applies it by
 * setting the account balance to the imported amount.</p>
 *
 * <p>Guarantees:</p>
 * <ul>
 *   <li><b>Dry-run</b> performs zero writes and consumes no idempotency state.</li>
 *   <li><b>Rerun idempotency</b> — each record is keyed by {@code source:sourceRecordId}; a rerun
 *       of an already-applied record is reported as skipped, not re-applied.</li>
 *   <li><b>Concurrent applies are serialized.</b> The account, transaction, and
 *       idempotency stores expose no shared transaction, so a real cross-store
 *       atomic commit is impossible here. Instead every non-dry-run import runs
 *       under one JVM-wide mutex and each record claims its idempotency key
 *       <em>before</em> writing: two racing applies of the same record yield
 *       exactly one {@code APPLIED} and one {@code SKIPPED_DUPLICATE}, and a
 *       lost claim ({@code consume} returning {@code false}) is a duplicate
 *       skip that appends no audit record.</li>
 *   <li><b>Failure isolation</b> — a malformed or failing record yields a per-record failure and
 *       processing continues; the overall report is only "fully successful" when no record failed.
 *       When the audit append fails after the balance was written, the previous
 *       balance is restored best-effort. Two residuals are inherent to the
 *       available ports and documented, not hidden: the idempotency key stays
 *       consumed (there is no un-consume operation, so a later retry reports a
 *       duplicate skip), and a newly created account cannot be removed (there
 *       is no account-delete operation).</li>
 *   <li><b>Never partially claims success</b> — {@link ImportReport#fullySuccessful()} is false if
 *       any record failed.</li>
 * </ul>
 */
public final class ImportService {

    /**
     * Serializes every non-dry-run import in this JVM. Claim-first idempotency
     * already decides same-record races atomically at the guard, but the mutex
     * additionally keeps concurrent imports of different records on one
     * account from interleaving balance writes, and keeps the behavior correct
     * even for guard implementations whose claim is not itself atomic.
     */
    private static final Object IMPORT_MUTEX = new Object();

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

    public ImportService(CurrencyRegistry currencies, AccountRepository accounts,
                         TransactionRepository transactions, Clock clock, IdempotencyGuard idempotency) {
        this(currencies, accounts, transactions, clock, idempotency, null);
    }

    public ImportService(CurrencyRegistry currencies, AccountRepository accounts,
                         TransactionRepository transactions, Clock clock, IdempotencyGuard idempotency,
                         Consumer<UUID> cacheInvalidation) {
        this.currencies = Objects.requireNonNull(currencies, "currencies");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.cacheInvalidation = cacheInvalidation;
    }

    public ImportReport importRecords(List<ImportRecord> records, ImportOptions options) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(options, "options");

        if (options.dryRun()) {
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

        UUID key = idempotencyKey(r);

        if (options.dryRun()) {
            // Zero writes; just report what would happen.
            if (idempotency.isConsumed(key)) {
                return ImportRecordResult.skipped(r, "already applied (dry-run)");
            }
            return ImportRecordResult.applied(r, null, "would apply (dry-run)");
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
