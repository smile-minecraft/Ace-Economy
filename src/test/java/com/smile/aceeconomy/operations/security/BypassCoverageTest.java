package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.operations.BackupRestoreService;
import com.smile.aceeconomy.operations.ImportReport;
import com.smile.aceeconomy.operations.ImportService;
import com.smile.aceeconomy.operations.RollbackResult;
import com.smile.aceeconomy.operations.RollbackService;
import com.smile.aceeconomy.ports.inmemory.CapturingReversalExecutor;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import com.smile.aceeconomy.ports.operations.ImportOptions;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Import, rollback, and restore bypass the application service, so each of them must
 * consult the same safety gate before touching a frozen account or running under
 * read-only mode. A restore that touches a frozen account is refused as a whole and a
 * restore never lifts the current control.
 */
class BypassCoverageTest {

    private static final Currency COIN = Currency.define("coin", "Coin", "C", 2, true);

    private CurrencyRegistry registry() {
        return CurrencyRegistry.of(List.of(COIN));
    }

    @Test
    @DisplayName("import apply touching a frozen account is refused with nothing applied")
    void importApplyTouchingFrozenAccountRefused() {
        UUID acc = UUID.randomUUID();
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.of(100, 2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        ImportService svc = new ImportService(registry(), accounts, tx,
                new FixedClock(), new InMemoryIdempotencyGuard());
        SecurityControlService control = new SecurityControlService();
        svc.bindSecurityControl(control);
        control.freeze(acc, AdminAudit.console("incident"));

        assertTrue(control.checkInitiating(acc).isPresent(),
                "gate must name the frozen account before import runs");
        ImportRecord rec = new ImportRecord(ImportSource.ESSENTIALS, "r1", acc,
                "owner", "coin", Amount.of(999, 2));
        ImportReport report = svc.importRecords(List.of(rec), new ImportOptions(false, false));
        assertEquals(0, report.appliedCount(), "import touching a frozen account must apply nothing");
        assertTrue(!report.fullySuccessful(), "import report must not claim success");
        assertEquals(Amount.of(100, 2), accounts.load(acc).orElseThrow().balanceOf("coin"));
        assertTrue(tx.all().isEmpty(), "no audit record for a refused import");
    }

    @Test
    @DisplayName("rollback touching a frozen account never reaches its executor")
    void rollbackTouchingFrozenAccountRefused() {
        UUID acc = UUID.randomUUID();
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.of(100, 2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        Transaction target = new Transaction(UUID.randomUUID(), acc, null, "coin",
                Amount.of(10, 2), TransactionType.DEPOSIT,
                Amount.of(90, 2), Amount.of(100, 2), Instant.now(), "deposit");
        try {
            tx.append(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        CapturingReversalExecutor executor = new CapturingReversalExecutor();
        RollbackService svc = new RollbackService(tx, executor);
        SecurityControlService control = new SecurityControlService();
        svc.bindSecurityControl(control);
        control.freeze(acc, AdminAudit.console("incident"));

        assertTrue(control.checkInitiating(acc).isPresent(),
                "gate must name the frozen account before rollback runs");
        RollbackResult r = svc.rollback(target.id(), AdminAudit.console("incident"));
        assertTrue(!r.isSuccess(), "rollback touching a frozen account must be refused");
        assertEquals(0, executor.callCount(), "refused rollback must never reach its executor");
        assertEquals(Amount.of(100, 2), accounts.load(acc).orElseThrow().balanceOf("coin"));
    }

    @Test
    @DisplayName("restore under global read-only is refused")
    void restoreUnderReadonlyRefused(@TempDir Path dir) throws Exception {
        Path liveFile = dir.resolve("live.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(liveFile);
        backend.initialize();
        backend.create(UUID.randomUUID(), "owner", Map.of("coin", Amount.of(1, 2)));
        Path dataFolder = dir.resolve("data");
        java.nio.file.Files.createDirectories(dataFolder);
        BackupRestoreService restores = new BackupRestoreService(backend, dataFolder,
                () -> false, () -> Set.of("coin"), () -> {
                });
        var backup = restores.createBackup("before");
        assertTrue(backup.isSuccess(), "setup backup must succeed");

        SecurityControlService control = new SecurityControlService();
        restores.bindSecurityControl(control);
        control.setGlobalReadonly(true, AdminAudit.console("emergency"));
        Optional<EconomyError> gate = control.checkInitiating(UUID.randomUUID());
        assertEquals(Optional.of(EconomyError.GLOBAL_READ_ONLY), gate,
                "read-only must refuse the restore path");

        var result = restores.restore(backup.backupId(), AdminAudit.console("emergency"));
        assertTrue(!result.isSuccess(), "restore under read-only must be refused");
    }

    @Test
    @DisplayName("restore touching a frozen account is refused as a whole")
    void restoreTouchingFrozenAccountRefused(@TempDir Path dir) throws Exception {
        UUID acc = UUID.randomUUID();
        Path liveFile = dir.resolve("live.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(liveFile);
        backend.initialize();
        backend.create(acc, "owner", Map.of("coin", Amount.of(100, 2)));
        Path dataFolder = dir.resolve("data");
        java.nio.file.Files.createDirectories(dataFolder);
        BackupRestoreService restores = new BackupRestoreService(backend, dataFolder,
                () -> false, () -> Set.of("coin"), () -> {
                });
        var backup = restores.createBackup("before");
        assertTrue(backup.isSuccess(), "setup backup must succeed");

        SecurityControlService control = new SecurityControlService();
        restores.bindSecurityControl(control);
        control.freeze(acc, AdminAudit.console("incident"));
        assertTrue(control.checkInitiating(acc).isPresent(),
                "gate must name the frozen account before restore runs");

        var result = restores.restore(backup.backupId(), AdminAudit.console("incident"));
        assertTrue(!result.isSuccess(), "restore touching a frozen account must be refused as a whole");
        assertTrue(control.isFrozen(acc), "a refused restore must never lift the current freeze");
    }
}
