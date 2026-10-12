package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.item.ItemIdentity;
import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.domain.BanknoteIssuanceReasons;
import com.smile.aceeconomy.domain.BanknoteIssuanceStatus;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.item.BanknoteValidator;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.persistence.AtomicBanknoteIssuanceStore;
import com.smile.aceeconomy.ports.persistence.IssuanceDebitResult;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Genuine redemption through the production deposit path must observe the
 * managed issuance as redeemed.
 *
 * <p>A delivered managed note blocks an empty-candidate rollback. Asking the
 * player to redeem first only unblocks the rollback when the redemption
 * itself flips the issuance to redeemed; otherwise the record stays delivered
 * and the same rollback stays refused.</p>
 */
class ManagedRedeemObservationTest {

    private static final DebtPolicy DEBT = DebtPolicy.disabled();

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Gold", "$", 2, true)));
    }

    private static Transaction preparedDebit(UUID txId, UUID account, Amount amount,
            Amount before, Amount after, UUID nonce) {
        return new Transaction(txId, account, null, "dollar", amount,
                TransactionType.WITHDRAW, before, after, Instant.now(),
                BanknoteIssuanceReasons.issueReason(nonce));
    }

    private static ItemIdentity v2Identity() {
        return new ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY,
                BanknoteClaim.V2_SCHEMA.major(), BanknoteClaim.V2_SCHEMA.minor());
    }

    private static ProductionAdapters.BankUseCase useCaseWith(EconomyService economy,
            CurrencyRegistry registry, FakeBanknoteFactory banknotes,
            AtomicBanknoteIssuanceStore atomic, Object redemptions) {
        ProductionAdapters.BankUseCase useCase = new ProductionAdapters.BankUseCase(
                Mockito.mock(EconomyApi.class), economy, registry, banknotes,
                new BanknoteValidator(new InMemoryIdempotencyGuard()),
                (com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore) redemptions);
        useCase.bindAtomicIssuanceStore(atomic);
        return useCase;
    }

    @Test
    void jsonGenuineRedeemMarksDeliveredAsRedeemedAndUnblocksRollback(@TempDir Path dir)
            throws Exception {
        Path dataFile = dir.resolve("redeem-observation.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));

            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            Amount amount = Amount.of(100L, 2);
            backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
            Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
            Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                    before, before.subtract(amount), nonce);
            IssuanceDebitResult committed = backend.commitDebit(nonce, prepared, DEBT);
            assertTrue(committed.isCommitted(), "setup commit must commit, got " + committed);
            backend.markDeliveryStarted(nonce);
            backend.markDelivered(nonce);
            assertEquals(BanknoteIssuanceStatus.DELIVERED,
                    backend.findByNonce(nonce).orElseThrow().status());
            assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "a delivered live record must block the empty candidate before redemption");

            CurrencyRegistry registry = currencies();
            EconomyService economy = new EconomyService(registry, DEBT, Amount.zero(2),
                    backend, tx -> {}, Instant::now, new InMemoryTransactionEventPublisher());
            FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
            ProductionAdapters.BankUseCase useCase =
                    useCaseWith(economy, registry, banknotes, backend, backend);

            BanknoteClaim claim = new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                    100L, UUID.randomUUID(), nonce, "dollar");
            ItemStack note = Mockito.mock(ItemStack.class);
            banknotes.register(note, claim);
            DepositResult result = useCase.deposit(account, note);
            assertTrue(result.success(), "genuine redemption must credit");
            assertEquals(BanknoteIssuanceStatus.REDEEMED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "genuine redemption through the production deposit path must mark"
                            + " the managed record redeemed");
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "redeeming the delivered note must credit the live balance");

            backend.restore(new ByteArrayInputStream(preUpgradeBytes));
            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "after a genuine redemption the empty candidate must no longer be"
                            + " blocked by this record");
        } finally {
            backend.close();
        }
    }

    @Test
    void sqliteGenuineRedeemMarksDeliveredAsRedeemedAndUnblocksRollback(@TempDir Path dir)
            throws Exception {
        Path db = dir.resolve("redeem-observation.db");
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
            Amount amount = Amount.of(100L, 2);
            backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
            Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
            Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                    before, before.subtract(amount), nonce);
            IssuanceDebitResult committed = backend.commitDebit(nonce, prepared, DEBT);
            assertTrue(committed.isCommitted(), "setup commit must commit, got " + committed);
            backend.markDeliveryStarted(nonce);
            backend.markDelivered(nonce);
            assertEquals(BanknoteIssuanceStatus.DELIVERED,
                    backend.findByNonce(nonce).orElseThrow().status());
            assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "a delivered live record must block the empty candidate before redemption");

            CurrencyRegistry registry = currencies();
            EconomyService economy = new EconomyService(registry, DEBT, Amount.zero(2),
                    backend, tx -> {}, Instant::now, new InMemoryTransactionEventPublisher());
            FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
            ProductionAdapters.BankUseCase useCase =
                    useCaseWith(economy, registry, banknotes, backend, backend);

            BanknoteClaim claim = new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                    100L, UUID.randomUUID(), nonce, "dollar");
            ItemStack note = Mockito.mock(ItemStack.class);
            banknotes.register(note, claim);
            DepositResult result = useCase.deposit(account, note);
            assertTrue(result.success(), "genuine redemption must credit");
            assertEquals(BanknoteIssuanceStatus.REDEEMED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "genuine redemption through the production deposit path must mark"
                            + " the managed record redeemed");
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "redeeming the delivered note must credit the live balance");

            backend.restore(new ByteArrayInputStream(preUpgradeBytes));
            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "after a genuine redemption the empty candidate must no longer be"
                            + " blocked by this record");
        } finally {
            backend.close();
        }
    }

    /** Faulty issuance view: reads through to the live backend but refuses the redeem mark. */
    private static final class FailingMarkStore implements AtomicBanknoteIssuanceStore {
        private final AtomicBanknoteIssuanceStore live;

        FailingMarkStore(AtomicBanknoteIssuanceStore live) {
            this.live = live;
        }

        @Override
        public com.smile.aceeconomy.domain.BanknoteIssuance prepare(
                com.smile.aceeconomy.domain.BanknoteIssuance issuance)
                throws PersistenceException {
            return live.prepare(issuance);
        }

        @Override
        public com.smile.aceeconomy.domain.BanknoteIssuance markDeliveryStarted(UUID nonce)
                throws PersistenceException {
            return live.markDeliveryStarted(nonce);
        }

        @Override
        public com.smile.aceeconomy.domain.BanknoteIssuance markDelivered(UUID nonce)
                throws PersistenceException {
            return live.markDelivered(nonce);
        }

        @Override
        public com.smile.aceeconomy.domain.BanknoteIssuance markRedeemed(UUID nonce)
                throws PersistenceException {
            throw new PersistenceException("injected redeem observation outage");
        }

        @Override
        public com.smile.aceeconomy.domain.BanknoteIssuance markAborted(UUID nonce, String reason)
                throws PersistenceException {
            return live.markAborted(nonce, reason);
        }

        @Override
        public com.smile.aceeconomy.domain.BanknoteIssuance markQuarantined(UUID nonce, String diagnosis)
                throws PersistenceException {
            return live.markQuarantined(nonce, diagnosis);
        }

        @Override
        public java.util.Optional<com.smile.aceeconomy.domain.BanknoteIssuance> findByNonce(UUID nonce)
                throws PersistenceException {
            return live.findByNonce(nonce);
        }

        @Override
        public java.util.List<com.smile.aceeconomy.domain.BanknoteIssuance> listUnfinished()
                throws PersistenceException {
            return live.listUnfinished();
        }

        @Override
        public com.smile.aceeconomy.ports.persistence.IssuanceDebitResult commitDebit(UUID nonce,
                Transaction prepared, com.smile.aceeconomy.domain.DebtPolicy debtPolicy)
                throws PersistenceException {
            return live.commitDebit(nonce, prepared, debtPolicy);
        }

        @Override
        public com.smile.aceeconomy.ports.persistence.IssuanceRefundResult commitRecoveryRefund(
                UUID nonce, Transaction prepared,
                com.smile.aceeconomy.domain.DebtPolicy debtPolicy) throws PersistenceException {
            return live.commitRecoveryRefund(nonce, prepared, debtPolicy);
        }
    }

    @Test
    void jsonMarkFailureKeepsCreditButBlocksRollback(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("redeem-mark-failure.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));

            ByteArrayOutputStream preUpgrade = new ByteArrayOutputStream();
            backend.backup(preUpgrade);
            byte[] preUpgradeBytes = preUpgrade.toByteArray();

            UUID nonce = UUID.randomUUID();
            Amount amount = Amount.of(100L, 2);
            backend.prepare(BanknoteIssuance.prepared(nonce, account, "dollar", amount, Instant.now()));
            Amount before = backend.load(account).orElseThrow().balanceOf("dollar");
            Transaction prepared = preparedDebit(UUID.randomUUID(), account, amount,
                    before, before.subtract(amount), nonce);
            IssuanceDebitResult committed = backend.commitDebit(nonce, prepared, DEBT);
            assertTrue(committed.isCommitted(), "setup commit must commit, got " + committed);
            backend.markDeliveryStarted(nonce);
            backend.markDelivered(nonce);

            CurrencyRegistry registry = currencies();
            EconomyService economy = new EconomyService(registry, DEBT, Amount.zero(2),
                    backend, tx -> {}, Instant::now, new InMemoryTransactionEventPublisher());
            FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
            ProductionAdapters.BankUseCase useCase = useCaseWith(economy, registry, banknotes,
                    new FailingMarkStore(backend), backend);

            BanknoteClaim claim = new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                    100L, UUID.randomUUID(), nonce, "dollar");
            ItemStack note = Mockito.mock(ItemStack.class);
            banknotes.register(note, claim);
            DepositResult result = useCase.deposit(account, note);
            assertTrue(result.success(),
                    "a mark failure must never overturn the committed credit");
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "the committed credit must stay even when the observation fails");
            assertEquals(BanknoteIssuanceStatus.DELIVERED,
                    backend.findByNonce(nonce).orElseThrow().status(),
                    "a failed observation leaves the record delivered (conservative)");

            BanknoteClaim replayClaim = new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                    100L, UUID.randomUUID(), nonce, "dollar");
            ItemStack replayNote = Mockito.mock(ItemStack.class);
            banknotes.register(replayNote, replayClaim);
            DepositResult replay = useCase.deposit(account, replayNote);
            assertTrue(!replay.success(), "the consumed nonce must never credit twice");
            assertEquals("replay.detected", replay.reason(),
                    "a second presentation must surface as a replay");
            assertEquals(0, Amount.of(10000L, 2).compareTo(
                            backend.load(account).orElseThrow().balanceOf("dollar")),
                    "a replay must not move the balance");

            PersistenceException refused = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(preUpgradeBytes)),
                    "a record staying delivered must keep refusing the empty candidate");
            assertTrue(refused.getMessage() != null
                    && refused.getMessage().contains("Restore refused"),
                    "diagnosis must name the restore refusal, got: " + refused.getMessage());
        } finally {
            backend.close();
        }
    }

    @Test
    void jsonRedeemWithoutManagedRecordStillCredits(@TempDir Path dir) throws Exception {
        Path dataFile = dir.resolve("redeem-legacy.json");
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dataFile);
        backend.initialize();
        try {
            UUID account = UUID.randomUUID();
            backend.create(account, "alice", Map.of("dollar", Amount.of(10000L, 2)));

            CurrencyRegistry registry = currencies();
            EconomyService economy = new EconomyService(registry, DEBT, Amount.zero(2),
                    backend, tx -> {}, Instant::now, new InMemoryTransactionEventPublisher());
            FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
            ProductionAdapters.BankUseCase useCase =
                    useCaseWith(economy, registry, banknotes, backend, backend);

            UUID nonce = UUID.randomUUID();
            BanknoteClaim claim = new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                    100L, UUID.randomUUID(), nonce, "dollar");
            ItemStack note = Mockito.mock(ItemStack.class);
            banknotes.register(note, claim);
            DepositResult result = useCase.deposit(account, note);
            assertTrue(result.success(), "a note without a managed record must still redeem");
            assertTrue(backend.findByNonce(nonce).isEmpty(),
                    "a note without a managed record must leave no issuance behind");
        } finally {
            backend.close();
        }
    }
}
