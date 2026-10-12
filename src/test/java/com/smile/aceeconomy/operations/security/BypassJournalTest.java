package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BlockedControlAttempt;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.security.FileBlockedAttemptJournal;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every refused bypass leaves exactly one minimal journal record on the real
 * journal: one per refused initiating action, carrying operation, target,
 * fixed category, control version and the trusted operator — never raw
 * secrets. A journal failure still refuses loudly without claiming audit
 * success.
 */
class BypassJournalTest {

    private static final Currency COIN = Currency.define("coin", "Coin", "C", 2, true);
    private static final Executor DIRECT = Runnable::run;

    private CurrencyRegistry registry() {
        return CurrencyRegistry.of(List.of(COIN));
    }

    private SecurityControlService journaledControl(Path journalFile) {
        SecurityControlService control = new SecurityControlService();
        control.bindAttemptJournal(new FileBlockedAttemptJournal(journalFile), DIRECT);
        return control;
    }

    @Test
    @DisplayName("refused import leaves one journal record with the trusted operator")
    void refusedImportLeavesOneRecord(@TempDir Path dir) {
        Path journalFile = dir.resolve("attempts.jsonl");
        UUID acc = UUID.randomUUID();
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.of(100, 2)));
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        ImportService svc = new ImportService(registry(), accounts, tx,
                new FixedClock(), new InMemoryIdempotencyGuard());
        SecurityControlService control = journaledControl(journalFile);
        svc.bindSecurityControl(control);
        control.freeze(acc, AdminAudit.console("incident"));

        UUID operator = UUID.randomUUID();
        ImportRecord rec = new ImportRecord(ImportSource.ESSENTIALS, "r1", acc,
                "owner", "coin", Amount.of(999, 2));
        ImportReport report = svc.importRecords(List.of(rec), new ImportOptions(false, false),
                AdminAudit.player(operator, "Op", "import-fix"));

        assertFalse(report.fullySuccessful(), "refused import must not claim success");
        List<BlockedControlAttempt> kept = new FileBlockedAttemptJournal(journalFile).list();
        assertEquals(1, kept.size(), "one refused import action must leave one journal record");
        assertEquals("import", kept.get(0).operation());
        assertEquals(acc, kept.get(0).targetAccount().orElseThrow());
        assertEquals(BlockedControlAttempt.CATEGORY_FROZEN, kept.get(0).refusalCategory());
        assertEquals(operator, kept.get(0).operatorId().orElseThrow());
        assertEquals("Op", kept.get(0).operatorName().orElseThrow());
        assertEquals(control.controlVersion(), kept.get(0).controlVersion());
    }

    @Test
    @DisplayName("refused rollback leaves one journal record and never reaches its executor")
    void refusedRollbackLeavesOneRecord(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("attempts.jsonl");
        UUID acc = UUID.randomUUID();
        InMemoryTransactionRepository tx = new InMemoryTransactionRepository();
        Transaction target = new Transaction(UUID.randomUUID(), acc, null, "coin",
                Amount.of(10, 2), TransactionType.DEPOSIT,
                Amount.of(90, 2), Amount.of(100, 2), Instant.now(), "deposit");
        tx.append(target);
        CapturingReversalExecutor executor = new CapturingReversalExecutor();
        RollbackService svc = new RollbackService(tx, executor);
        SecurityControlService control = journaledControl(journalFile);
        svc.bindSecurityControl(control);
        control.freeze(acc, AdminAudit.console("incident"));

        RollbackResult r = svc.rollback(target.id(), AdminAudit.console("incident"));

        assertFalse(r.isSuccess(), "rollback touching a frozen account must be refused");
        assertEquals(0, executor.callCount(), "refused rollback must never reach its executor");
        List<BlockedControlAttempt> kept = new FileBlockedAttemptJournal(journalFile).list();
        assertEquals(1, kept.size(), "one refused rollback action must leave one journal record");
        assertEquals("rollback", kept.get(0).operation());
        assertEquals(acc, kept.get(0).targetAccount().orElseThrow());
    }

    @Test
    @DisplayName("refused restore under read-only leaves one journal record with no target")
    void refusedRestoreUnderReadonlyLeavesOneRecord(@TempDir Path dir) throws Exception {
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

        Path journalFile = dataFolder.resolve("attempts.jsonl");
        SecurityControlService control = journaledControl(journalFile);
        restores.bindSecurityControl(control);
        control.setGlobalReadonly(true, AdminAudit.console("emergency"));

        var result = restores.restore(backup.backupId(), AdminAudit.console("emergency"));

        assertFalse(result.isSuccess(), "restore under read-only must be refused");
        List<BlockedControlAttempt> kept = new FileBlockedAttemptJournal(journalFile).list();
        assertEquals(1, kept.size(), "one refused restore action must leave one journal record");
        assertEquals("restore", kept.get(0).operation());
        assertEquals(BlockedControlAttempt.CATEGORY_READONLY, kept.get(0).refusalCategory());
        assertTrue(kept.get(0).targetAccount().isEmpty(),
                "a global refusal names no single target");
    }

    @Test
    @DisplayName("journal append failure still refuses without claiming audit success")
    void journalAppendFailureStillRefuses(@TempDir Path dir) {
        SecurityControlService control = new SecurityControlService();
        BlockedAttemptJournal failing = new BlockedAttemptJournal() {
            @Override
            public void append(BlockedControlAttempt attempt) {
                throw new IllegalStateException("injected journal append failure");
            }

            @Override
            public List<BlockedControlAttempt> list() {
                return List.of();
            }
        };
        control.bindAttemptJournal(failing, DIRECT);
        UUID acc = UUID.randomUUID();
        control.freeze(acc, AdminAudit.console("incident"));

        UUID operator = UUID.randomUUID();
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.of(100, 2)));
        ImportService svc = new ImportService(registry(), accounts,
                new InMemoryTransactionRepository(), new FixedClock(),
                new InMemoryIdempotencyGuard());
        svc.bindSecurityControl(control);
        ImportRecord rec = new ImportRecord(ImportSource.ESSENTIALS, "r1", acc,
                "owner", "coin", Amount.of(999, 2));
        ImportReport report = svc.importRecords(List.of(rec), new ImportOptions(false, false),
                AdminAudit.player(operator, "Op", "import-fix"));

        assertFalse(report.fullySuccessful(),
                "a journal failure must never flip a refusal into success");
        assertEquals(Amount.of(100, 2), accounts.load(acc).orElseThrow().balanceOf("coin"));
        assertEquals(1, control.blockedAttempts().size(),
                "the in-memory record is kept even when the append fails");
    }

    @Test
    @DisplayName("journal executor rejection still refuses with the memory record kept")
    void journalExecutorRejectionStillRefuses(@TempDir Path dir) {
        Path journalFile = dir.resolve("attempts.jsonl");
        SecurityControlService control = new SecurityControlService();
        Executor rejecting = command -> {
            throw new RejectedExecutionException("injected executor rejection");
        };
        control.bindAttemptJournal(new FileBlockedAttemptJournal(journalFile), rejecting);
        UUID acc = UUID.randomUUID();
        control.freeze(acc, AdminAudit.console("incident"));

        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        accounts.create(acc, "owner", Map.of("coin", Amount.of(100, 2)));
        ImportService svc = new ImportService(registry(), accounts,
                new InMemoryTransactionRepository(), new FixedClock(),
                new InMemoryIdempotencyGuard());
        svc.bindSecurityControl(control);
        ImportRecord rec = new ImportRecord(ImportSource.ESSENTIALS, "r1", acc,
                "owner", "coin", Amount.of(999, 2));
        ImportReport report = svc.importRecords(List.of(rec), new ImportOptions(false, false));

        assertFalse(report.fullySuccessful(),
                "an executor rejection must never flip a refusal into success");
        assertEquals(Amount.of(100, 2), accounts.load(acc).orElseThrow().balanceOf("coin"));
        assertEquals(1, control.blockedAttempts().size(),
                "the in-memory record is kept even when the executor rejects");
    }
}
