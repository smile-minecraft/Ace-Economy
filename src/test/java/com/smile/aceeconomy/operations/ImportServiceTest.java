package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.IdempotencyGuard;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import com.smile.aceeconomy.ports.operations.ImportOptions;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportServiceTest {

    private static final Currency COIN = Currency.define("coin", "Coin", "C", 2, true);

    private CurrencyRegistry registry() {
        return CurrencyRegistry.of(List.of(COIN));
    }

    private ImportRecord rec(UUID account, String recordId, double amount) {
        return new ImportRecord(ImportSource.ESSENTIALS, recordId, account, "Owner-" + recordId, "coin",
                Amount.of(amount, 2));
    }

    private UUID key(String sourceRecordId) {
        return UUID.nameUUIDFromBytes(("ESSENTIALS:" + sourceRecordId).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void dryRunPerformsNoWrites() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.zero(2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        IdempotencyGuard guard = new InMemoryIdempotencyGuard();
        ImportService svc = new ImportService(registry(), accounts, tx, new FixedClock(), guard);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)), new ImportOptions(true, false));

        assertTrue(report.dryRun());
        assertEquals(1, report.appliedCount());
        assertTrue(report.fullySuccessful());
        // No mutation of state.
        assertEquals(Amount.zero(2), accounts.load(acc).orElseThrow().balanceOf("coin"));
        assertEquals(0, tx.all().size());
        assertFalse(guard.isConsumed(key("r1")));
    }

    @Test
    void applySetsBalanceAndAppendsTx() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.zero(2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        IdempotencyGuard guard = new InMemoryIdempotencyGuard();
        ImportService svc = new ImportService(registry(), accounts, tx, new FixedClock(), guard);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)), new ImportOptions(false, false));

        assertEquals(1, report.appliedCount());
        assertTrue(report.fullySuccessful());
        assertEquals(Amount.of(500, 2), accounts.load(acc).orElseThrow().balanceOf("coin"));
        assertEquals(1, tx.all().size());
        assertEquals(TransactionType.SET, tx.all().get(0).type());
        assertEquals("import:ESSENTIALS", tx.all().get(0).reason());
        assertTrue(guard.isConsumed(key("r1")));
    }

    @Test
    void duplicateRerunIsSkipped() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.zero(2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        IdempotencyGuard guard = new InMemoryIdempotencyGuard();
        ImportService svc = new ImportService(registry(), accounts, tx, new FixedClock(), guard);

        ImportReport first = svc.importRecords(List.of(rec(acc, "r1", 500)), new ImportOptions(false, false));
        assertEquals(1, first.appliedCount());
        ImportReport second = svc.importRecords(List.of(rec(acc, "r1", 500)), new ImportOptions(false, false));
        assertEquals(1, second.skippedCount());
        assertEquals(0, second.appliedCount());
        // Balance unchanged, only one tx.
        assertEquals(Amount.of(500, 2), accounts.load(acc).orElseThrow().balanceOf("coin"));
        assertEquals(1, tx.all().size());
    }

    @Test
    void malformedRecordIsolated() {
        UUID good = new UUID(0, 1);
        UUID bad = new UUID(0, 2);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(good, "owner", Map.of("coin", Amount.zero(2)));
        accounts.create(bad, "owner", Map.of("coin", Amount.zero(2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        IdempotencyGuard guard = new InMemoryIdempotencyGuard();
        ImportService svc = new ImportService(registry(), accounts, tx, new FixedClock(), guard);

        ImportRecord unknownCurrency = new ImportRecord(ImportSource.ESSENTIALS, "bad", bad, "Owner-bad", "gem", Amount.of(10, 2));
        ImportRecord negative = new ImportRecord(ImportSource.ESSENTIALS, "neg", good, "Owner-neg", "coin", Amount.of(-5, 2));
        ImportReport report = svc.importRecords(
                List.of(rec(good, "r1", 500), unknownCurrency, negative), new ImportOptions(false, false));

        assertEquals(1, report.appliedCount());
        assertEquals(2, report.failedCount());
        assertFalse(report.fullySuccessful());
        // Valid one applied.
        assertEquals(Amount.of(500, 2), accounts.load(good).orElseThrow().balanceOf("coin"));
        // Failed records did not consume idempotency (retryable).
        assertFalse(guard.isConsumed(key("bad")));
        assertFalse(guard.isConsumed(key("neg")));
    }

    @Test
    void createsMissingAccountWhenEnabled() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        IdempotencyGuard guard = new InMemoryIdempotencyGuard();
        ImportService svc = new ImportService(registry(), accounts, tx, new FixedClock(), guard);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)), new ImportOptions(false, true));
        assertEquals(1, report.appliedCount());
        assertTrue(accounts.exists(acc));
        assertEquals(Amount.of(500, 2), accounts.load(acc).orElseThrow().balanceOf("coin"));
    }

    @Test
    void missingAccountWithoutCreateFails() {
        UUID acc = new UUID(0, 1);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        IdempotencyGuard guard = new InMemoryIdempotencyGuard();
        ImportService svc = new ImportService(registry(), accounts, tx, new FixedClock(), guard);

        ImportReport report = svc.importRecords(List.of(rec(acc, "r1", 500)), new ImportOptions(false, false));
        assertEquals(1, report.failedCount());
        assertFalse(accounts.exists(acc));
    }
}
