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
 *   <li><b>Failure isolation</b> — a malformed or failing record yields a per-record failure and
 *       processing continues; the overall report is only "fully successful" when no record failed.</li>
 *   <li><b>Never partially claims success</b> — {@link ImportReport#fullySuccessful()} is false if
 *       any record failed.</li>
 * </ul>
 */
public final class ImportService {

    private final CurrencyRegistry currencies;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final Clock clock;
    private final IdempotencyGuard idempotency;

    public ImportService(CurrencyRegistry currencies, AccountRepository accounts,
                         TransactionRepository transactions, Clock clock, IdempotencyGuard idempotency) {
        this.currencies = Objects.requireNonNull(currencies, "currencies");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    }

    public ImportReport importRecords(List<ImportRecord> records, ImportOptions options) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(options, "options");

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

        try {
            Currency cur = currencies.get(r.currencyId());
            if (accounts.exists(r.accountUuid())) {
                Account existing = accounts.load(r.accountUuid()).orElseThrow();
                Amount before = existing.balanceOf(cur.id());
                if (before == null) {
                    before = cur.zero();
                }
                Account updated = existing.setBalance(cur.id(), r.amount());
                accounts.save(updated);
                Amount after = updated.balanceOf(cur.id());
                UUID txId = appendImportRecord(r, cur, before, after);
                idempotency.consume(key);
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
            idempotency.consume(key);
            return ImportRecordResult.applied(r, txId, null);
        } catch (RuntimeException e) {
            // Isolation: do not consume the idempotency key so the record can be retried.
            return ImportRecordResult.failed(r, "apply failed: " + e.getMessage());
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
