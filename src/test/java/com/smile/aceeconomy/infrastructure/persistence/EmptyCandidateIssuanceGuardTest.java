package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonModel;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.IssuanceDebitResult;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empty-candidate restore guard over live managed progress.
 *
 * <p>A candidate without issuance progress must not blindly replace live
 * progress when a delivered or possibly-delivered note may still sit in a
 * player inventory. Only live states that cannot leave a redeemable note
 * behind may roll back to such a candidate.</p>
 */
class EmptyCandidateIssuanceGuardTest {

    private static final DebtPolicy DEBT = DebtPolicy.disabled();

    private static Transaction preparedDebit(UUID txId, UUID account, Amount amount,
            Amount before, Amount after, UUID nonce) {
        return new Transaction(txId, account, null, "dollar", amount,
                TransactionType.WITHDRAW, before, after, Instant.now(),
                BanknoteIssuanceReasons.issueReason(nonce));
    }

    private static void commitDebit(AtomicBanknoteIssuanceStore store,
            JsonPersistenceBackend backend, UUID account, UUID nonce, Amount amount)
            throws Exception {
        store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
        Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
        Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                before, before.subtract(amount), nonce);
        IssuanceDebitResult committed = store.commitDebit(nonce, prepared, DEBT);
        assertTrue(committed.isCommitted(), "setup commit must commit, got " + committed);
    }

    private static void commitDebitSql(AtomicBanknoteIssuanceStore store,
            SqlBackend backend, UUID account, UUID nonce, Amount amount)
            throws Exception {
        store.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
        Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
        Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                before, before.subtract(amount), nonce);
        IssuanceDebitResult committed = store.commitDebit(nonce, prepared, DEBT);
        assertTrue(committed.isCommitted(), "setup commit must commit, got " + committed);
    }

    private static UUID deliverJson(JsonPersistenceBackend backend, UUID account, UUID nonce,
            Amount amount) throws Exception {
        commitDebit(backend, backend, account, nonce, amount);
        backend.markDeliveryStarted(nonce);
        backend.markDelivered(nonce);
        assertEquals(BanknoteIssuanceStatus.DELIVERED,
                backend.findByNonce(nonce).orElseThrow().status());
        return nonce;
    }

    private static UUID deliverSql(SqlBackend backend, UUID account, UUID nonce,
            Amount amount) throws Exception {
        commitDebitSql(backend, backend, account, nonce, amount);
        backend.markDeliveryStarted(nonce);
        backend.markDelivered(nonce);
        assertEquals(BanknoteIssuanceStatus.DELIVERED,
                backend.findByNonce(nonce).orElseThrow().status());
        return nonce;
    }

    @Test
    void jsonEmptyCandidateOverDeliveredLiveIsRefusedAndStateUnchanged(@TempDir Path dir)
            throws Exception {
        Path dataFile = dir.resolve("empty-guard.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));

            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();
            assertFalse(new String(preUpgradeBytes, StandardCharsets.UTF_8).contains("issuances"),
                    "setup must produce a candidate without an issuance section");

            UUID nonce = UUID.randomUUID();
            deliverJson(backend, account, nonce, Amount.of(100L, 2));
            assertEquals(0, Amount.of(9900L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "setup must leave the deducted balance");

            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "restoring a snapshot without issuance progress over a delivered"
                            + " live record must be refused");
            assertTrue(refused.getMessage() != null
                            && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());

            assertEquals(0, Amount.of(9900L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "a refused restore must leave the live balance untouched");
            assertEquals(BanknoteIssuanceStatus.DELIVERED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "a refused restore must leave the delivered live record untouched");

            RedemptionResult redeem = backend.redeem(nonce, account, "dollar", Amount.of(100L, 2));
            assertTrue(redeem.isCommitted(),
                    "the delivered note must stay redeemable after the refused restore,"
                            + " got " + redeem);
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "redeeming the surviving note must credit the live balance");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqliteEmptyCandidateOverDeliveredLiveIsRefusedAndStateUnchanged(@TempDir Path dir)
            throws Exception {
        Path db = dir.resolve("empty-guard.db");
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));

            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();
            assertFalse(new String(preUpgradeBytes, StandardCharsets.UTF_8).contains("issuances"),
                    "setup must produce a candidate without an issuance section");

            UUID nonce = UUID.randomUUID();
            deliverSql(backend, account, nonce, Amount.of(100L, 2));
            assertEquals(0, Amount.of(9900L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "setup must leave the deducted balance");

            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "restoring a snapshot without issuance progress over a delivered"
                            + " live record must be refused");
            assertTrue(refused.getMessage() != null
                            && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());

            assertEquals(0, Amount.of(9900L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "a refused restore must leave the live balance untouched");
            assertEquals(BanknoteIssuanceStatus.DELIVERED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "a refused restore must leave the delivered live record untouched");

            RedemptionResult redeem = backend.redeem(nonce, account, "dollar", Amount.of(100L, 2));
            assertTrue(redeem.isCommitted(),
                    "the delivered note must stay redeemable after the refused restore,"
                            + " got " + redeem);
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "redeeming the surviving note must credit the live balance");
        } finally {
            backend.close();
        }
    }

    @Test
    void jsonEmptyCandidateOverDeliveryStartedLiveIsRefused(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("delivery-started.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            commitDebit(backend, backend, account, nonce, Amount.of(100L, 2));
            backend.markDeliveryStarted(nonce);
            assertEquals(BanknoteIssuanceStatus.DELIVERY_STARTED,
                    backend.findByNonce(nonce).orElseThrow().status());

            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "a delivery-started live record may already have handed the note over,"
                            + " so the empty candidate must be refused");
            assertTrue(refused.getMessage() != null
                    && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());
            assertEquals(BanknoteIssuanceStatus.DELIVERY_STARTED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "a refused restore must leave the live record untouched");
            assertEquals(0, Amount.of(9900L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "a refused restore must leave the live balance untouched");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqliteEmptyCandidateOverDeliveryStartedLiveIsRefused(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("delivery-started.db");
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            commitDebitSql(backend, backend, account, nonce, Amount.of(100L, 2));
            backend.markDeliveryStarted(nonce);
            assertEquals(BanknoteIssuanceStatus.DELIVERY_STARTED,
                    backend.findByNonce(nonce).orElseThrow().status());

            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "a delivery-started live record may already have handed the note over,"
                            + " so the empty candidate must be refused");
            assertTrue(refused.getMessage() != null
                    && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());
            assertEquals(BanknoteIssuanceStatus.DELIVERY_STARTED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "a refused restore must leave the live record untouched");
        } finally {
            backend.close();
        }
    }

    @Test
    void jsonEmptyCandidateOverQuarantinedLiveIsRefused(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("quarantined.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                    Amount.of(100L, 2), Instant.now()));
            backend.markQuarantined(nonce, "quarantine for the empty-candidate guard check");
            assertEquals(BanknoteIssuanceStatus.QUARANTINED,
                    backend.findByNonce(nonce).orElseThrow().status());

            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "a quarantined live record cannot be judged safe,"
                            + " so the empty candidate must be refused");
            assertTrue(refused.getMessage() != null
                    && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());
            assertEquals(BanknoteIssuanceStatus.QUARANTINED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "a refused restore must leave the live record untouched");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqliteEmptyCandidateOverQuarantinedLiveIsRefused(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("quarantined.db");
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                    Amount.of(100L, 2), Instant.now()));
            backend.markQuarantined(nonce, "quarantine for the empty-candidate guard check");
            assertEquals(BanknoteIssuanceStatus.QUARANTINED,
                    backend.findByNonce(nonce).orElseThrow().status());

            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "a quarantined live record cannot be judged safe,"
                            + " so the empty candidate must be refused");
            assertTrue(refused.getMessage() != null
                    && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());
            assertEquals(BanknoteIssuanceStatus.QUARANTINED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "a refused restore must leave the live record untouched");
        } finally {
            backend.close();
        }
    }

    @Test
    void jsonEmptyCandidateOverPreparedLiveStillRollsBack(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("prepared-rollback.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                    Amount.of(100L, 2), Instant.now()));
            assertEquals(BanknoteIssuanceStatus.PREPARED,
                    backend.findByNonce(nonce).orElseThrow().status());

            backend.restore(new ByteArrayInputStream(preUpgradeBytes));

            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "a deduction-free prepared record leaves no redeemable note behind,"
                            + " so the rollback must still replace it wholesale");
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "balances must mirror the restored snapshot exactly");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqliteEmptyCandidateOverPreparedLiveStillRollsBack(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("prepared-rollback.db");
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                    Amount.of(100L, 2), Instant.now()));
            assertEquals(BanknoteIssuanceStatus.PREPARED,
                    backend.findByNonce(nonce).orElseThrow().status());

            backend.restore(new ByteArrayInputStream(preUpgradeBytes));

            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "a deduction-free prepared record leaves no redeemable note behind,"
                            + " so the rollback must still replace it wholesale");
        } finally {
            backend.close();
        }
    }

    @Test
    void jsonEmptyCandidateOverRedeemedLiveStillRollsBack(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("redeemed-rollback.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            commitDebit(backend, backend, account, nonce, Amount.of(100L, 2));
            backend.markRedeemed(nonce);
            assertEquals(BanknoteIssuanceStatus.REDEEMED,
                    backend.findByNonce(nonce).orElseThrow().status());

            backend.restore(new ByteArrayInputStream(preUpgradeBytes));

            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "an already redeemed record holds no redeemable note,"
                            + " so the rollback must still replace it wholesale");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqliteEmptyCandidateOverRedeemedLiveStillRollsBack(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("redeemed-rollback.db");
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            commitDebitSql(backend, backend, account, nonce, Amount.of(100L, 2));
            backend.markRedeemed(nonce);
            assertEquals(BanknoteIssuanceStatus.REDEEMED,
                    backend.findByNonce(nonce).orElseThrow().status());

            backend.restore(new ByteArrayInputStream(preUpgradeBytes));

            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "an already redeemed record holds no redeemable note,"
                            + " so the rollback must still replace it wholesale");
        } finally {
            backend.close();
        }
    }

    @Test
    void jsonEmptyCandidateOverDebitCommittedLiveStillRollsBack(@TempDir Path dir)
            throws Exception {
        Path dataFile = dir.resolve("debit-committed-rollback.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            commitDebit(backend, backend, account, nonce, Amount.of(100L, 2));
            assertEquals(BanknoteIssuanceStatus.DEBIT_COMMITTED,
                    backend.findByNonce(nonce).orElseThrow().status());

            backend.restore(new ByteArrayInputStream(preUpgradeBytes));

            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "a committed-but-undelivered record never reached the handover,"
                            + " so the rollback must still replace it wholesale");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqliteEmptyCandidateOverDebitCommittedLiveStillRollsBack(@TempDir Path dir)
            throws Exception {
        Path db = dir.resolve("debit-committed-rollback.db");
        SqlBackend backend = new SqlBackend(
                new SqlConnectionProvider(DriverManager.getConnection("jdbc:sqlite:" + db)),
                new SqliteDialect());
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));
            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            commitDebitSql(backend, backend, account, nonce, Amount.of(100L, 2));
            assertEquals(BanknoteIssuanceStatus.DEBIT_COMMITTED,
                    backend.findByNonce(nonce).orElseThrow().status());

            backend.restore(new ByteArrayInputStream(preUpgradeBytes));

            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "a committed-but-undelivered record never reached the handover,"
                            + " so the rollback must still replace it wholesale");
        } finally {
            backend.close();
        }
    }

    /**
     * Format-level evidence that a rolled-back store keeps the old shape.
     * This runs no old program build: it proves the produced bytes carry no
     * new section key, keep the old schema value, and load through the same
     * additive-section path old files use.
     */
    @Test
    void jsonRollbackKeepsOldShapeLoadableByLegacyPath(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("old-shape.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));

            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            String preUpgradeText = new String(preUpgrade.toByteArray(), StandardCharsets.UTF_8);
            assertFalse(preUpgradeText.contains("issuances"),
                    "pre-upgrade bytes must carry no issuance section key");
            JsonModel preUpgradeModel = JsonModel.fromJson(preUpgradeText);
            assertTrue(preUpgradeModel.issuances.isEmpty(),
                    "an absent section must read as empty through the legacy path");
            assertEquals(JsonModel.SCHEMA_VERSION, preUpgradeModel.schemaVersion,
                    "the schema value must stay at the running version");

            UUID nonce = UUID.randomUUID();
            backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar",
                    Amount.of(100L, 2), Instant.now()));
            backend.restore(new ByteArrayInputStream(preUpgrade.toByteArray()));

            ByteArrayOutputStream after = new ByteArrayOutputStream();
            backend.backup(after);
            String afterText = new String(after.toByteArray(), StandardCharsets.UTF_8);
            assertFalse(afterText.contains("issuances"),
                    "restored state must keep the old shape so older readers still load it");
            JsonModel reloaded = JsonModel.fromJson(afterText);
            assertTrue(reloaded.issuances.isEmpty(),
                    "reloaded state must carry no issuance progress");
            assertEquals(JsonModel.SCHEMA_VERSION, reloaded.schemaVersion,
                    "the schema value must stay at the running version");
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "balances must mirror the restored snapshot exactly");
        } finally {
            backend.close();
        }
    }
}
