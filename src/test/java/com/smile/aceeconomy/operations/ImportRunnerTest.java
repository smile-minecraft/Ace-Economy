package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.ports.IdempotencyGuard;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import com.smile.aceeconomy.ports.operations.ImportSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Red: the apply/dry-run orchestration — path gate first, parse, then either a
 * zero-write preview or a backup-gated apply whose report never claims full
 * success when anything failed.
 */
class ImportRunnerTest {

    private static final Currency COIN = Currency.define("coin", "Coin", "C", 2, true);
    private static final UUID ALEX = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID BLOB = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    private CurrencyRegistry registry() {
        return CurrencyRegistry.of(List.of(COIN));
    }

    private ImportService service(InMemoryAccountRepository accounts,
                                  InMemoryTransactionRepository tx, IdempotencyGuard guard) {
        return new ImportService(registry(), accounts, tx, new FixedClock(), guard);
    }

    private BackupRestoreService backupReturning(BackupResult result) {
        BackupRestoreService backup = mock(BackupRestoreService.class);
        when(backup.createBackup(anyString())).thenReturn(result);
        return backup;
    }

    private void writeImport(Path dataFolder, String name, String content) throws Exception {
        Path dir = dataFolder.resolve("import");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private void writeCsv(Path dataFolder, String body) throws Exception {
        writeImport(dataFolder, "balances.csv",
                "uuid,name,balance\n" + body);
    }

    @Test
    void dryRunWritesNothingAndTakesNoBackup(@TempDir Path dataFolder) throws Exception {
        writeCsv(dataFolder, ALEX + ",Alex,500\n");
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(ALEX, "Alex", Map.of("coin", Amount.zero(2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BackupRestoreService backup = mock(BackupRestoreService.class);
        ImportRunner runner = new ImportRunner(service(accounts, tx, guard), backup, dataFolder, registry());

        ImportOutcome outcome = runner.preview(ImportSource.CMI, "balances.csv", "coin");

        assertTrue(outcome.dryRun());
        assertNull(outcome.backupId(), "a preview must not take a backup");
        assertEquals(1, outcome.report().appliedCount());
        assertTrue(outcome.fullySuccessful());
        assertEquals(Amount.zero(2), accounts.load(ALEX).orElseThrow().balanceOf("coin"));
        assertEquals(0, tx.all().size());
        verify(backup, never()).createBackup(anyString());
    }

    @Test
    void applyTakesBackupThenWrites(@TempDir Path dataFolder) throws Exception {
        writeCsv(dataFolder, ALEX + ",Alex,500\n");
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BackupRestoreService backup = backupReturning(BackupResult.success("pre-import-id"));
        ImportRunner runner = new ImportRunner(service(accounts, tx, guard), backup, dataFolder, registry());

        ImportOutcome outcome = runner.apply(ImportSource.CMI, "balances.csv", "coin");

        assertFalse(outcome.dryRun());
        assertEquals("pre-import-id", outcome.backupId());
        assertEquals(1, outcome.report().appliedCount());
        assertTrue(outcome.fullySuccessful());
        assertEquals(Amount.of(500, 2), accounts.load(ALEX).orElseThrow().balanceOf("coin"));
        verify(backup).createBackup("pre-import");
    }

    @Test
    void rerunIsSkippedNotReapplied(@TempDir Path dataFolder) throws Exception {
        writeCsv(dataFolder, ALEX + ",Alex,500\n");
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BackupRestoreService backup = backupReturning(BackupResult.success("pre-import-id"));
        ImportRunner runner = new ImportRunner(service(accounts, tx, guard), backup, dataFolder, registry());

        runner.apply(ImportSource.CMI, "balances.csv", "coin");
        ImportOutcome second = runner.apply(ImportSource.CMI, "balances.csv", "coin");

        assertEquals(1, second.report().skippedCount());
        assertEquals(0, second.report().appliedCount());
        assertEquals(1, tx.all().size(), "no duplicate audit record");
    }

    @Test
    void backupFailureAbortsBeforeAnyWrite(@TempDir Path dataFolder) throws Exception {
        writeCsv(dataFolder, ALEX + ",Alex,500\n");
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BackupRestoreService backup = backupReturning(
                BackupResult.failure(BackupRestoreError.IO_FAILED, "disk full"));
        ImportRunner runner = new ImportRunner(service(accounts, tx, guard), backup, dataFolder, registry());

        ImportException failure = assertThrows(ImportException.class,
                () -> runner.apply(ImportSource.CMI, "balances.csv", "coin"));

        assertEquals(ImportFailureReason.BACKUP_FAILED, failure.reason());
        assertEquals(0, tx.all().size());
        assertFalse(accounts.exists(ALEX));
    }

    @Test
    void pathEscapeIsRejectedBeforeBackup(@TempDir Path dataFolder) throws Exception {
        Files.createDirectories(dataFolder.resolve("import"));
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BackupRestoreService backup = mock(BackupRestoreService.class);
        ImportRunner runner = new ImportRunner(service(accounts, tx, guard), backup, dataFolder, registry());

        ImportException failure = assertThrows(ImportException.class,
                () -> runner.apply(ImportSource.CMI, "../config.yml", "coin"));

        assertEquals(ImportFailureReason.PATH_REJECTED, failure.reason());
        verify(backup, never()).createBackup(anyString());
    }

    @Test
    void unknownCurrencyFailsFastWithoutBackup(@TempDir Path dataFolder) throws Exception {
        writeCsv(dataFolder, ALEX + ",Alex,500\n");
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BackupRestoreService backup = mock(BackupRestoreService.class);
        ImportRunner runner = new ImportRunner(service(accounts, tx, guard), backup, dataFolder, registry());

        ImportException failure = assertThrows(ImportException.class,
                () -> runner.preview(ImportSource.CMI, "balances.csv", "gem"));

        assertEquals(ImportFailureReason.CURRENCY_UNKNOWN, failure.reason());
        verify(backup, never()).createBackup(anyString());
    }

    @Test
    void parseFailuresNeverClaimFullSuccess(@TempDir Path dataFolder) throws Exception {
        writeCsv(dataFolder, ALEX + ",Alex,500\nnot-a-uuid,Steve,10\n" + BLOB + ",Blob,not-a-number\n");
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BackupRestoreService backup = backupReturning(BackupResult.success("pre-import-id"));
        ImportRunner runner = new ImportRunner(service(accounts, tx, guard), backup, dataFolder, registry());

        ImportOutcome outcome = runner.preview(ImportSource.CMI, "balances.csv", "coin");

        assertEquals(1, outcome.report().appliedCount());
        assertEquals(2, outcome.failedCount());
        assertFalse(outcome.fullySuccessful(), "a partial import must never report full success");
        assertEquals(2, outcome.parseFailures().size());
    }

    @Test
    void essentialsDirectoryApplies(@TempDir Path dataFolder) throws Exception {
        Path dir = dataFolder.resolve("import").resolve("userdata");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(ALEX + ".yml"),
                "last-account-name: Alex\nmoney: 250\n", StandardCharsets.UTF_8);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BackupRestoreService backup = backupReturning(BackupResult.success("pre-import-id"));
        ImportRunner runner = new ImportRunner(service(accounts, tx, guard), backup, dataFolder, registry());

        ImportOutcome outcome = runner.apply(ImportSource.ESSENTIALS, "userdata", "coin");

        assertEquals(1, outcome.report().appliedCount());
        assertTrue(outcome.fullySuccessful());
        assertEquals(Amount.of(250, 2), accounts.load(ALEX).orElseThrow().balanceOf("coin"));
    }
}
