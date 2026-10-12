package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empty-candidate guard over refunded-voided live progress.
 *
 * <p>A refunded-voided record means the balance was already refunded in one
 * storage transaction together with the nonce consumption, but the physical
 * note may still sit in a player inventory. Restoring a snapshot without
 * issuance progress must therefore refuse: the restore would return the
 * balance path to the pre-upgrade shape while un-consuming the nonce, so the
 * kept note would become redeemable again next to the already-credited
 * refund.</p>
 */
class RefundedVoidEmptyCandidateGuardTest {

    private static final DebtPolicy DEBT = DebtPolicy.disabled();

    private static CurrencyRegistry registry() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Gold", "$", 2, true)));
    }

    private static EconomyService serviceWithBackend(JsonPersistenceBackend backend) {
        return new EconomyService(registry(), DEBT, Amount.zero(2), backend,
                tx -> {}, Instant::now, new InMemoryTransactionEventPublisher());
    }

    private static EconomyService serviceWithBackend(SqlBackend backend) {
        return new EconomyService(registry(), DEBT, Amount.zero(2), backend,
                tx -> {}, Instant::now, new InMemoryTransactionEventPublisher());
    }

    private static Transaction preparedDebit(UUID txId, UUID account, Amount amount,
            Amount before, Amount after, UUID nonce) {
        return new Transaction(txId, account, null, "dollar", amount,
                TransactionType.WITHDRAW, before, after, Instant.now(),
                BanknoteIssuanceReasons.issueReason(nonce));
    }

    private static void commitDebitJson(JsonPersistenceBackend backend, UUID account,
            UUID nonce, Amount amount) throws Exception {
        backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
        Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
        Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                before, before.subtract(amount), nonce);
        IssuanceDebitResult committed =
                ((AtomicBanknoteIssuanceStore) backend).commitDebit(nonce, prepared, DEBT);
        assertTrue(committed.isCommitted(), "setup commit must commit, got " + committed);
    }

    private static void commitDebitSql(SqlBackend backend, UUID account,
            UUID nonce, Amount amount) throws Exception {
        backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
        Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
        Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                before, before.subtract(amount), nonce);
        IssuanceDebitResult committed =
                ((AtomicBanknoteIssuanceStore) backend).commitDebit(nonce, prepared, DEBT);
        assertTrue(committed.isCommitted(), "setup commit must commit, got " + committed);
    }

    @Test
    void jsonEmptyCandidateOverRefundedVoidLiveIsRefusedAndNoteStaysVoid(@TempDir Path dir)
            throws Exception {
        Path dataFile = dir.resolve("refunded-void-guard.json");
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
            Amount amount = Amount.of(100L, 2);
            commitDebitJson(backend, account, nonce, amount);
            backend.markDeliveryStarted(nonce);
            EconomyResult<Amount> refunded =
                    serviceWithBackend(backend).refundIssuanceForRecovery(nonce, backend);
            assertTrue(refunded.isSuccess(),
                    "setup refund must void and refund together, got " + refunded);
            assertEquals(BanknoteIssuanceStatus.FINAL_VOID_REFUNDED,
                    backend.findByNonce(nonce).orElseThrow().status());
            assertTrue(backend.isConsumed(nonce), "a refunded void must consume the nonce");
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "setup must leave the refunded balance");

            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "a refunded-voided live record may still have its physical note kept,"
                            + " so the empty candidate must be refused");
            assertTrue(refused.getMessage() != null
                            && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());

            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "a refused restore must leave the live balance untouched");
            assertEquals(BanknoteIssuanceStatus.FINAL_VOID_REFUNDED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "a refused restore must leave the refunded-voided live record untouched");
            assertTrue(backend.isConsumed(nonce),
                    "a refused restore must keep the voided nonce consumed");

            RedemptionResult residual = backend.redeem(nonce, account, "dollar", Amount.of(100L, 2));
            assertFalse(residual.isCommitted(),
                    "the kept physical note must stay void after the refused restore, got " + residual);
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "value must be conserved: the balance must never reach 101.00");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqliteEmptyCandidateOverRefundedVoidLiveIsRefusedAndNoteStaysVoid(@TempDir Path dir)
            throws Exception {
        Path db = dir.resolve("refunded-void-guard.db");
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
            Amount amount = Amount.of(100L, 2);
            commitDebitSql(backend, account, nonce, amount);
            backend.markDeliveryStarted(nonce);
            EconomyResult<Amount> refunded =
                    serviceWithBackend(backend).refundIssuanceForRecovery(nonce, backend);
            assertTrue(refunded.isSuccess(),
                    "setup refund must void and refund together, got " + refunded);
            assertEquals(BanknoteIssuanceStatus.FINAL_VOID_REFUNDED,
                    backend.findByNonce(nonce).orElseThrow().status());
            assertTrue(backend.isConsumed(nonce), "a refunded void must consume the nonce");
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "setup must leave the refunded balance");

            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "a refunded-voided live record may still have its physical note kept,"
                            + " so the empty candidate must be refused");
            assertTrue(refused.getMessage() != null
                            && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());

            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "a refused restore must leave the live balance untouched");
            assertEquals(BanknoteIssuanceStatus.FINAL_VOID_REFUNDED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "a refused restore must leave the refunded-voided live record untouched");
            assertTrue(backend.isConsumed(nonce),
                    "a refused restore must keep the voided nonce consumed");

            RedemptionResult residual = backend.redeem(nonce, account, "dollar", Amount.of(100L, 2));
            assertFalse(residual.isCommitted(),
                    "the kept physical note must stay void after the refused restore, got " + residual);
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "value must be conserved: the balance must never reach 101.00");
        } finally {
            backend.close();
        }
    }
}
