package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.operations.StorageReversalExecutor;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.operations.ImportReport;
import com.smile.aceeconomy.operations.ImportService;
import com.smile.aceeconomy.operations.RollbackResult;
import com.smile.aceeconomy.operations.RollbackService;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.operations.ImportOptions;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Amount-cap enforcement at the storage boundary, on both real backends.
 *
 * <ul>
 *   <li>Write entry: an over-cap balance or transaction amount is refused with a
 *       diagnosis naming the cap — never truncated into {@code VARCHAR(64)}.</li>
 *   <li>Read entry stays lenient (stored-scale precedent): a legacy over-cap row
 *       still loads, and the service gate refuses the next transaction on that
 *       currency with an explicit stored-data diagnosis, leaving it untouched.</li>
 *   <li>Import and rollback paths funnel through the same write entry, so an
 *       over-cap import record fails per-record and an over-cap reversal applies
 *       nothing.</li>
 * </ul>
 */
class AmountCapStorageTest {

    private static final String CUR = "dollar";
    private static final String CAP = "1000000000000000";
    private static final String OVER_CAP = "1000000000000001.00";
    private static final String HUGE = "2000000000000000.00";
    private static final String NEAR_CAP = "999999999999900.00";

    private static final FixedClock CLOCK = new FixedClock();

    @TempDir
    Path dir;

    private CurrencyRegistry registry() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
    }

    private static Amount amt2(String plain) {
        return Amount.of(new BigDecimal(plain), 2);
    }

    // ---- backend wiring ----

    private JsonPersistenceBackend jsonBackend(String file) {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve(file));
        backend.initialize();
        return backend;
    }

    private SqlBackend sqliteBackend(Path db) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        return backend;
    }

    private EconomyService serviceOver(AccountRepository accounts,
                                       TransactionRepository transactions) {
        return new EconomyService(registry(), DebtPolicy.disabled(), Amount.of(1000, 2),
                accounts, new PersistentAuditSink(transactions), CLOCK,
                new InMemoryTransactionEventPublisher());
    }

    // ---- write entry: JSON ----

    @Test
    @DisplayName("JSON: saving an over-cap balance is refused with a cap diagnosis")
    void jsonSaveRefusesOverCapBalance() {
        JsonPersistenceBackend backend = jsonBackend("cap-write.json");
        UUID owner = UUID.randomUUID();

        PersistenceException e = assertThrows(PersistenceException.class, () ->
                backend.create(owner, "alice", Map.of(CUR, amt2(OVER_CAP))));
        assertTrue(e.getMessage().contains(CAP), "diagnosis must name the cap, got: " + e.getMessage());
        assertTrue(backend.load(owner).isEmpty(), "refused write must leave no account behind");
    }

    @Test
    @DisplayName("JSON: audit rows stay writable as evidence even above the cap")
    void jsonAuditAboveCapStaysWritableAsEvidence() {
        JsonPersistenceBackend backend = jsonBackend("cap-tx.json");
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, amt2("10.00")));
        // Audit rows are append-only evidence (including of pre-cap history), never
        // cap-refused: only owed balances are capped. The economic invariant holds
        // because no balance above the cap can be written (see the save tests).
        Transaction tx = Fixtures.tx(UUID.randomUUID(), owner, null, CUR, amt2(HUGE),
                TransactionType.DEPOSIT, amt2("10.00"), amt2(HUGE));
        backend.append(tx);

        assertEquals(1, backend.loadAll().size());
        assertEquals(0, new BigDecimal(HUGE).compareTo(backend.loadAll().get(0).amount().value()));
        assertEquals(0, amt2("10.00").compareTo(
                backend.load(owner).orElseThrow().balanceOf(CUR)),
                "evidence rows move no money: the balance is untouched");
    }

    // ---- write entry: SQL ----

    @Test
    @DisplayName("SQL: saving an over-cap balance is refused with a cap diagnosis")
    void sqlSaveRefusesOverCapBalance() throws Exception {
        SqlBackend backend = sqliteBackend(dir.resolve("cap-write.db"));
        UUID owner = UUID.randomUUID();

        PersistenceException e = assertThrows(PersistenceException.class, () ->
                backend.create(owner, "alice", Map.of(CUR, amt2(OVER_CAP))));
        assertTrue(e.getMessage().contains(CAP), "diagnosis must name the cap, got: " + e.getMessage());
        assertTrue(backend.load(owner).isEmpty(), "refused write must leave no account behind");
        backend.close();
    }

    @Test
    @DisplayName("SQL: audit rows stay writable as evidence even above the cap")
    void sqlAuditAboveCapStaysWritableAsEvidence() throws Exception {
        Path db = dir.resolve("cap-tx.db");
        SqlBackend backend = sqliteBackend(db);
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, amt2("10.00")));
        // Same evidence rule as the JSON backend: only owed balances are capped.
        Transaction tx = Fixtures.tx(UUID.randomUUID(), owner, null, CUR, amt2(HUGE),
                TransactionType.DEPOSIT, amt2("10.00"), amt2(HUGE));

        try {
            backend.append(tx);
            assertEquals(0, new BigDecimal(HUGE).compareTo(
                    backend.loadAll().get(0).amount().value()));
            assertEquals(0, amt2("10.00").compareTo(
                    backend.load(owner).orElseThrow().balanceOf(CUR)),
                    "evidence rows move no money: the balance is untouched");
        } finally {
            backend.close();
        }
    }

    @Test
    @DisplayName("JSON: direct transfer overflowing the receiver is refused atomically")
    void jsonTransferReceiverOverflowRefused() {
        JsonPersistenceBackend backend = jsonBackend("cap-transfer.json");
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        backend.create(from, "alice", Map.of(CUR, amt2("500.00")));
        backend.create(to, "bob", Map.of(CUR, amt2(NEAR_CAP)));

        PersistenceException e = assertThrows(PersistenceException.class, () -> backend.transfer(
                from, to, CUR, amt2("200.00"), DebtPolicy.disabled()));
        assertTrue(e.getMessage().contains(CAP), "diagnosis must name the cap, got: " + e.getMessage());
        assertEquals(0, amt2("500.00").compareTo(backend.load(from).orElseThrow().balanceOf(CUR)));
        assertEquals(0, amt2(NEAR_CAP).compareTo(backend.load(to).orElseThrow().balanceOf(CUR)));
        assertTrue(backend.loadAll().isEmpty(), "aborted transfer must leave no audit rows");
    }

    @Test
    @DisplayName("SQL: direct transfer overflowing the receiver is refused atomically")
    void sqlTransferReceiverOverflowRefused() throws Exception {
        SqlBackend backend = sqliteBackend(dir.resolve("cap-transfer.db"));
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        backend.create(from, "alice", Map.of(CUR, amt2("500.00")));
        backend.create(to, "bob", Map.of(CUR, amt2(NEAR_CAP)));

        try {
            PersistenceException e = assertThrows(PersistenceException.class, () -> backend.transfer(
                    from, to, CUR, amt2("200.00"), DebtPolicy.disabled()));
            assertTrue(e.getMessage().contains(CAP),
                    "diagnosis must name the cap, got: " + e.getMessage());
            assertEquals(0, amt2("500.00").compareTo(backend.load(from).orElseThrow().balanceOf(CUR)));
            assertEquals(0, amt2(NEAR_CAP).compareTo(backend.load(to).orElseThrow().balanceOf(CUR)));
            assertTrue(backend.loadAll().isEmpty(), "aborted transfer must leave no audit rows");
        } finally {
            backend.close();
        }
    }

    // ---- read entry: legacy over-cap rows diagnose at the service gate ----

    @Test
    @DisplayName("JSON: legacy over-cap row loads, next transaction refuses with stored diagnosis")
    void jsonLegacyOverCapRowDiagnosed() throws Exception {
        Path file = dir.resolve("legacy.json");
        JsonPersistenceBackend writer = new JsonPersistenceBackend(file);
        writer.initialize();
        UUID owner = UUID.randomUUID();
        writer.create(owner, "alice", Map.of(CUR, amt2("100.00")));
        writer.close();

        // Hand-edit past the write guard, modelling a row that predates the cap.
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("100.00"), "seed shape changed, cannot patch");
        Files.writeString(file, json.replace("100.00", HUGE), StandardCharsets.UTF_8);

        JsonPersistenceBackend backend = new JsonPersistenceBackend(file);
        backend.initialize();
        Account loaded = backend.load(owner).orElseThrow(() -> new AssertionError("account missing"));
        assertEquals(0, new BigDecimal(HUGE).compareTo(loaded.balanceOf(CUR).value()),
                "read entry stays lenient so recovery tooling can still see the row");

        EconomyResult<Amount> r = serviceOver(backend, backend).deposit(owner, CUR, amt2("1.00"));
        assertTrue(r.isFailure());
        assertEquals(EconomyError.AMOUNT_EXCEEDS_CAP, r.error());
        assertTrue(r.message().contains("stored"), "must blame the stored data, got: " + r.message());
        assertEquals(0, new BigDecimal(HUGE).compareTo(
                backend.load(owner).orElseThrow().balanceOf(CUR).value()),
                "diagnosed row must be left untouched");
    }

    @Test
    @DisplayName("SQL: legacy over-cap row loads, next transaction refuses with stored diagnosis")
    void sqlLegacyOverCapRowDiagnosed() throws Exception {
        Path db = dir.resolve("legacy.db");
        SqlBackend backend = sqliteBackend(db);
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, amt2("100.00")));
        backend.close();

        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = raw.prepareStatement(
                     "UPDATE ace_v2_balances SET amount = ? WHERE owner = ?")) {
            ps.setString(1, HUGE);
            ps.setString(2, owner.toString());
            assertEquals(1, ps.executeUpdate());
        }

        SqlBackend reopened = sqliteBackend(db);
        try {
            Account loaded = reopened.load(owner).orElseThrow(() -> new AssertionError("account missing"));
            assertEquals(0, new BigDecimal(HUGE).compareTo(loaded.balanceOf(CUR).value()),
                    "read entry stays lenient so recovery tooling can still see the row");

            EconomyResult<Amount> r = serviceOver(reopened, reopened).deposit(owner, CUR, amt2("1.00"));
            assertTrue(r.isFailure());
            assertEquals(EconomyError.AMOUNT_EXCEEDS_CAP, r.error());
            assertTrue(r.message().contains("stored"), "got: " + r.message());
            assertEquals(0, new BigDecimal(HUGE).compareTo(
                    reopened.load(owner).orElseThrow().balanceOf(CUR).value()),
                    "diagnosed row must be left untouched");
        } finally {
            reopened.close();
        }
    }

    // ---- import path ----

    @Test
    @DisplayName("import: over-cap record fails per-record with cap diagnosis; key stays retryable")
    void importOverCapRecordFails() {
        JsonPersistenceBackend backend = jsonBackend("cap-import.json");
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, amt2("10.00")));
        ImportService svc = new ImportService(registry(), backend, backend, CLOCK,
                new InMemoryIdempotencyGuard(), null, backend, null);
        ImportRecord record = new ImportRecord(ImportSource.ESSENTIALS, "r-over", owner,
                "alice", CUR, amt2(OVER_CAP));

        ImportReport first = svc.importRecords(List.of(record), new ImportOptions(false, true));

        assertEquals(0, first.appliedCount());
        assertEquals(1, first.failedCount());
        assertTrue(first.results().get(0).message().contains(CAP),
                "per-record failure must carry the cap diagnosis, got: "
                        + first.results().get(0).message());
        assertEquals(0, amt2("10.00").compareTo(
                backend.load(owner).orElseThrow().balanceOf(CUR)),
                "failed import must not move the balance");

        ImportReport second = svc.importRecords(List.of(record), new ImportOptions(false, true));
        assertEquals(1, second.failedCount(), "key must stay unconsumed so a retry still fails, not skips");
    }

    // ---- rollback path ----

    @Test
    @DisplayName("rollback: reversal that would exceed the cap applies nothing and stays retryable")
    void rollbackOverCapAppliesNothing() {
        JsonPersistenceBackend backend = jsonBackend("cap-rollback.json");
        UUID owner = UUID.randomUUID();
        // Live balance reflects the original withdraw: (cap + 100) - 200 = cap - 100.
        backend.create(owner, "alice", Map.of(CUR, amt2(NEAR_CAP)));
        UUID originalId = UUID.randomUUID();
        backend.append(Fixtures.tx(originalId, owner, null, CUR, amt2("200.00"),
                TransactionType.WITHDRAW, amt2("1000000000000100.00"), amt2(NEAR_CAP)));

        RollbackService svc = new RollbackService(backend,
                new StorageReversalExecutor(backend, backend, CLOCK));
        RollbackResult result = svc.rollback(originalId);

        assertFalse(result.isSuccess(), "over-cap reversal must not report success");
        assertEquals(0, amt2(NEAR_CAP).compareTo(
                backend.load(owner).orElseThrow().balanceOf(CUR)),
                "refused reversal must leave the balance untouched");
        assertEquals(1, backend.loadAll().size(), "no reversal record may be appended");
        assertFalse(backend.isReverted(originalId), "marker must stay unset so the op stays retryable");
    }
}
