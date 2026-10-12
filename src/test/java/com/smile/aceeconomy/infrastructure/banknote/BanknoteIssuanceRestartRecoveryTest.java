package com.smile.aceeconomy.infrastructure.banknote;

import com.smile.aceeconomy.application.BanknoteIssuanceRecovery;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.infrastructure.persistence.InMemoryBanknoteIssuanceStore;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restart and replay safety for durable issuance (phase-1 red).
 *
 * <p>Each backend test rebuilds a REAL backend instance over the same files to
 * simulate a process restart, then asserts the unfinished issuance survives and
 * recovery converges. The JSON and SQL backends persist issuance progress only
 * transiently in phase 1 (never in the file, snapshot, backup, restore or
 * preflight), so the restart assertions fail until phase 2 lands the additive
 * {@code issuances} section / table with the single-transaction atomic
 * commits. Planting uses {@code prepare} plus the test-only
 * {@code plantForTest} seam with a nonce-bound binding, never a standalone
 * money mark. The idempotence and first-writer-wins tests run on the
 * in-memory reference and fail on the phase-1 recovery stub instead.
 * In-memory is semantics-only and never proves restart durability.</p>
 */
class BanknoteIssuanceRestartRecoveryTest {

    private static BanknoteIssuance prepared(UUID nonce, UUID account) {
        return BanknoteIssuance.prepared(nonce, account, "dollar",
                Amount.of(100L, 2), Instant.now());
    }

    private static BanknoteIssuance debitCommitted(UUID nonce, UUID account) {
        UUID debitId = UUID.randomUUID();
        return new BanknoteIssuance(nonce, account, "dollar",
                Amount.of(100L, 2), BanknoteIssuanceStatus.DEBIT_COMMITTED,
                debitId.toString(), BanknoteIssuanceReasons.issueReason(nonce),
                Instant.now(), Instant.now());
    }

    @Test
    void jsonRestartKeepsUnfinishedIssuance(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("economy.json");
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        JsonPersistenceBackend first = new JsonPersistenceBackend(dataFile);
        first.initialize();
        first.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
        AtomicBanknoteIssuanceStore store = first;
        store.prepare(prepared(nonce, account));
        first.close();

        JsonPersistenceBackend second = new JsonPersistenceBackend(dataFile);
        second.initialize();
        try {
            assertTrue(second.findByNonce(nonce).isPresent(),
                    "JSON backend must persist issuance progress in an additive section:"
                            + " restart recovery needs the unfinished record on the same files");
            assertTrue(second instanceof AtomicBanknoteIssuanceStore,
                    "JSON backend must expose the atomic issuance boundary on the same instance");
        } finally {
            second.close();
        }
    }

    @Test
    void sqliteRestartKeepsUnfinishedIssuance(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("economy.db");
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        SqlBackend first = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        first.initialize();
        first.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
        AtomicBanknoteIssuanceStore store = first;
        store.prepare(prepared(nonce, account));
        first.close();

        SqlBackend second = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        second.initialize();
        try {
            assertTrue(second.findByNonce(nonce).isPresent(),
                    "SQL backend must persist issuance progress in an additive table:"
                            + " restart recovery needs the unfinished record on the same database");
            assertTrue(second instanceof AtomicBanknoteIssuanceStore,
                    "SQL backend must expose the atomic issuance boundary on the same instance");
        } finally {
            second.close();
        }
    }

    @Test
    void repeatRecoveryIsIdempotent() {
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        InMemoryBanknoteIssuanceStore store = new InMemoryBanknoteIssuanceStore();
        store.prepare(prepared(nonce, account));
        store.plantForTest(debitCommitted(nonce, account));
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        BanknoteIssuanceRecovery.RecoveryReport first = recovery.recoverAll();
        assertEquals(1, first.scanned(), "first run must scan the unfinished record");
        assertEquals(1, first.refunded(), "first run must refund the proven deduction");

        BanknoteIssuanceRecovery.RecoveryReport second = recovery.recoverAll();
        assertEquals(0, second.refunded(), "second run must never refund again");
        assertEquals(first.refunded(), 1, "first run must have refunded exactly once");
        assertEquals(BanknoteIssuanceStatus.FINAL_VOID_REFUNDED,
                store.findByNonce(nonce).orElseThrow().status());
    }

    @Test
    void redeemVsRecoveryFirstWriterWinsConservesValue() {
        UUID nonce = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        InMemoryBanknoteIssuanceStore store = new InMemoryBanknoteIssuanceStore();
        store.prepare(prepared(nonce, account));
        store.plantForTest(debitCommitted(nonce, account));
        store.markDeliveryStarted(nonce);
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);

        BanknoteIssuanceRecovery.RecoveryReport report = recovery.recoverAll();
        assertEquals(1, report.refunded(),
                "recovery wins the unfinished nonce: refund plus void in one commit");

        BanknoteIssuance after = store.findByNonce(nonce).orElseThrow();
        assertEquals(BanknoteIssuanceStatus.FINAL_VOID_REFUNDED, after.status(),
                "a void-refunded nonce must stay consumed: a late redeem observes"
                        + " replay with no second credit, so value is conserved");
    }
}
