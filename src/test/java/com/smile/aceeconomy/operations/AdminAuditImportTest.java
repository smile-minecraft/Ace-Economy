package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.Transaction;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audited import: every applied record's transaction carries the console
 * operator and the given reason, still inside the backup-gated atomic apply.
 */
final class AdminAuditImportTest {

    private static final Currency COIN = Currency.define("coin", "Coin", "C", 2, true);
    private static final UUID ALEX = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private CurrencyRegistry registry() {
        return CurrencyRegistry.of(List.of(COIN));
    }

    private ImportService service(InMemoryAccountRepository accounts,
                                  InMemoryTransactionRepository tx, IdempotencyGuard guard) {
        return new ImportService(registry(), accounts, tx, new FixedClock(), guard);
    }

    @Test
    void auditedApplyStampsOperatorAndReasonOnEveryRecord(@TempDir Path dataFolder) throws Exception {
        Path dir = dataFolder.resolve("import");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("balances.csv"),
                "uuid,name,balance\n" + ALEX + ",Alex,500\n", StandardCharsets.UTF_8);
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BackupRestoreService backup = mock(BackupRestoreService.class);
        when(backup.createBackup(anyString())).thenReturn(BackupResult.success("pre-import-id"));
        ImportRunner runner = new ImportRunner(service(accounts, tx, guard), backup, dataFolder, registry());

        ImportOutcome outcome = runner.apply(ImportSource.CMI, "balances.csv", "coin",
                AdminAudit.console("harbor-import"));

        assertTrue(outcome.fullySuccessful());
        assertEquals(Amount.of(500, 2), accounts.load(ALEX).orElseThrow().balanceOf("coin"));
        assertEquals(1, tx.all().size());
        Transaction record = tx.all().get(0);
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, record.operatorName());
        assertTrue(record.reason().contains("harbor-import"),
                "the operator reason must stay discoverable, got: " + record.reason());
    }
}
