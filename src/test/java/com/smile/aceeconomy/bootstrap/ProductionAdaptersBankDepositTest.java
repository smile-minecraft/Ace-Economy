package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.item.ItemIdentity;
import com.smile.acelib.item.ItemSchemaVersion;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.infrastructure.item.BanknoteValidator;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the production bank GUI deposit path ({@code ProductionAdapters.BankUseCase}).
 * They lock the full reject-code matrix and the ordering guarantee: the redemption store is only
 * reached by structurally valid v2 notes of a known currency, and only a committed redemption
 * yields a success outcome that lets the caller remove the item.
 */
class ProductionAdaptersBankDepositTest {

    /** Recording fake standing in for the durable redemption store. */
    private static final class FakeRedemptionStore implements AtomicRedemptionStore {
        final Set<UUID> consumed = ConcurrentHashMap.newKeySet();
        RedemptionResult programmed;
        PersistenceException failure;
        UUID lastNonce;
        UUID lastAccount;
        String lastCurrency;
        Amount lastAmount;
        int calls;
        int preparedCalls;

        @Override
        public RedemptionResult redeem(UUID nonce, UUID accountId, String currencyId, Amount amount) {
            calls++;
            lastNonce = nonce;
            lastAccount = accountId;
            lastCurrency = currencyId;
            lastAmount = amount;
            if (failure != null) {
                throw failure;
            }
            if (programmed != null) {
                return programmed;
            }
            if (!consumed.add(nonce)) {
                return RedemptionResult.replay();
            }
            return RedemptionResult.committed(Amount.zero(amount.scale()), amount, UUID.randomUUID());
        }

        @Override
        public RedemptionResult redeemPrepared(UUID nonce, com.smile.aceeconomy.domain.Account account,
                                               com.smile.aceeconomy.domain.Transaction transaction) {
            preparedCalls++;
            lastNonce = nonce;
            lastAccount = transaction.accountId();
            lastCurrency = transaction.currencyId();
            lastAmount = transaction.amount();
            if (failure != null) {
                throw failure;
            }
            if (programmed != null) {
                return programmed;
            }
            if (!consumed.add(nonce)) {
                return RedemptionResult.replay();
            }
            return RedemptionResult.committed(transaction.balanceBefore(), transaction.balanceAfter(), transaction.id());
        }
    }

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
    private final FakeRedemptionStore redemptions = new FakeRedemptionStore();
    private final ProductionAdapters.BankUseCase useCase = new ProductionAdapters.BankUseCase(
            Mockito.mock(com.smile.aceeconomy.api.v2.EconomyApi.class),
            currencies(), banknotes,
            new BanknoteValidator(new InMemoryIdempotencyGuard()), redemptions);

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 2, true),
                Currency.define("token", "活動代幣", "ⓒ", 0, false)));
    }

    private static ItemIdentity v2Identity() {
        return new ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY,
                BanknoteClaim.V2_SCHEMA.major(), BanknoteClaim.V2_SCHEMA.minor());
    }

    private ItemStack plant(BanknoteClaim claim) {
        ItemStack stack = Mockito.mock(ItemStack.class);
        banknotes.register(stack, claim);
        return stack;
    }

    // ---------------- happy path ----------------

    @Test
    void validNoteCommitsOnceAndReachesTheStoreWithTypedValues() {
        UUID player = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                100L, UUID.randomUUID(), nonce, "dollar"));

        com.smile.aceeconomy.ports.DepositResult result = useCase.deposit(player, note);

        assertTrue(result.success(), "a valid v2 note must credit");
        assertEquals(100L, result.value());
        assertEquals("dollar", result.currencyId());
        assertEquals(1, redemptions.calls);
        assertEquals(nonce, redemptions.lastNonce);
        assertEquals(player, redemptions.lastAccount);
        assertEquals("dollar", redemptions.lastCurrency);
        assertEquals(0, Amount.of(100L, 2).compareTo(redemptions.lastAmount));
        assertTrue(redemptions.consumed.contains(nonce), "committed redemption consumes the nonce");
    }

    @Test
    void secondDepositOfSameNoteIsAReplay() {
        UUID player = UUID.randomUUID();
        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                50L, UUID.randomUUID(), UUID.randomUUID(), "dollar"));

        assertTrue(useCase.deposit(player, note).success());
        com.smile.aceeconomy.ports.DepositResult second = useCase.deposit(player, note);

        assertFalse(second.success(), "the same note must never credit twice");
        assertEquals("replay.detected", second.reason());
    }

    // ---------------- structural rejections keep the item and never reach the store ----------------

    @Test
    void undecodableItemRejectedBeforeAnyBusinessLogic() {
        ItemStack plain = Mockito.mock(ItemStack.class);
        com.smile.aceeconomy.ports.DepositResult r = useCase.deposit(UUID.randomUUID(), plain);
        assertFalse(r.success());
        assertEquals("banknote.invalid", r.reason());
        assertEquals(0, redemptions.calls);
    }

    @Test
    void foreignNamespaceRejected() {
        ItemStack note = plant(new BanknoteClaim(
                new ItemIdentity("aceeconomy.v1", BanknoteClaim.V2_KEY, 2, 0),
                BanknoteClaim.V2_SCHEMA, 100L, UUID.randomUUID(), UUID.randomUUID(), "dollar"));
        assertEquals("identity.namespace", useCase.deposit(UUID.randomUUID(), note).reason());
        assertEquals(0, redemptions.calls);
    }

    @Test
    void staleSchemaRecordRejected() {
        ItemStack note = plant(new BanknoteClaim(v2Identity(), new ItemSchemaVersion(1, 0),
                100L, UUID.randomUUID(), UUID.randomUUID(), "dollar"));
        assertEquals("schema.version", useCase.deposit(UUID.randomUUID(), note).reason());
        assertEquals(0, redemptions.calls);
    }

    @Test
    void nonPositiveValueRejected() {
        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                0L, UUID.randomUUID(), UUID.randomUUID(), "dollar"));
        assertEquals("value.nonpositive", useCase.deposit(UUID.randomUUID(), note).reason());
        assertEquals(0, redemptions.calls);
    }

    @Test
    void missingIssuerOrNonceRejected() {
        ItemStack noIssuer = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                100L, null, UUID.randomUUID(), "dollar"));
        ItemStack noNonce = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                100L, UUID.randomUUID(), null, "dollar"));
        assertEquals("issuer.missing", useCase.deposit(UUID.randomUUID(), noIssuer).reason());
        assertEquals("nonce.missing", useCase.deposit(UUID.randomUUID(), noNonce).reason());
        assertEquals(0, redemptions.calls);
    }

    @Test
    void unknownCurrencyRejectedBeforeRedemption() {
        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                100L, UUID.randomUUID(), UUID.randomUUID(), "euro"));
        com.smile.aceeconomy.ports.DepositResult r = useCase.deposit(UUID.randomUUID(), note);
        assertFalse(r.success());
        assertEquals("currency.unknown", r.reason());
        assertEquals(0, redemptions.calls);
    }

    // ---------------- redemption outcomes ----------------

    @Test
    void replayFromStoreSurfacesAsStableReplayReason() {
        UUID nonce = UUID.randomUUID();
        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                100L, UUID.randomUUID(), nonce, "dollar"));
        redemptions.consumed.add(nonce); // another process already redeemed this note

        com.smile.aceeconomy.ports.DepositResult r = useCase.deposit(UUID.randomUUID(), note);
        assertFalse(r.success());
        assertEquals("replay.detected", r.reason());
    }

    @Test
    void accountMissingLeavesNonceUnconsumed() {
        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                100L, UUID.randomUUID(), UUID.randomUUID(), "dollar"));
        redemptions.programmed = RedemptionResult.accountMissing();

        com.smile.aceeconomy.ports.DepositResult r = useCase.deposit(UUID.randomUUID(), note);
        assertFalse(r.success());
        assertEquals("credit.account-missing", r.reason());
        assertTrue(redemptions.consumed.isEmpty(),
                "an unknown account must not burn the note's nonce");
    }

    @Test
    void storageFailureMapsToTypedCreditFailedInsteadOfThrowing() {
        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                100L, UUID.randomUUID(), UUID.randomUUID(), "dollar"));
        redemptions.failure = new PersistenceException("injected storage outage");

        com.smile.aceeconomy.ports.DepositResult r = useCase.deposit(UUID.randomUUID(), note);
        assertFalse(r.success());
        assertEquals("credit.failed", r.reason());
        assertTrue(redemptions.consumed.isEmpty(), "an undecided failure must not consume the nonce");
    }

    // ---------------- economy-backed (prepared) path ----------------

    private ProductionAdapters.BankUseCase economyUseCase(FakeRedemptionStore store,
                                                          InMemoryTransactionEventPublisher publisher,
                                                          InMemoryAccountRepository repo) {
        CurrencyRegistry cr = currencies();
        // ensure default currency exists for EconomyService construction
        EconomyService economy = new EconomyService(cr, DebtPolicy.disabled(), Amount.zero(2),
                repo, tx -> {}, () -> Instant.now(), publisher);
        return new ProductionAdapters.BankUseCase(
                Mockito.mock(com.smile.aceeconomy.api.v2.EconomyApi.class),
                economy, cr, banknotes,
                new BanknoteValidator(new InMemoryIdempotencyGuard()), store);
    }

    @Test
    void economyPreCommitCancellationMapsToTransactionCancelledAndDoesNotConsumeNonce() {
        FakeRedemptionStore store = new FakeRedemptionStore();
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        publisher.register(event -> event.cancel());
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID player = UUID.randomUUID();
        repo.create(player, "alice", Map.of("dollar", Amount.of(100L, 2)));
        ProductionAdapters.BankUseCase eu = economyUseCase(store, publisher, repo);

        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                50L, UUID.randomUUID(), UUID.randomUUID(), "dollar"));

        com.smile.aceeconomy.ports.DepositResult r = eu.deposit(player, note);
        assertFalse(r.success());
        assertEquals("transaction.cancelled", r.reason());
        assertEquals(0, store.preparedCalls, "cancelled pre-commit must not reach the atomic store");
        assertTrue(store.consumed.isEmpty());
        // balance unchanged
        assertEquals(0, Amount.of(100L, 2).compareTo(repo.load(player).orElseThrow().balanceOf("dollar")));
    }

    @Test
    void economyReplayViaPreparedStoreMapsToReplayDetected() {
        FakeRedemptionStore store = new FakeRedemptionStore();
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID player = UUID.randomUUID();
        repo.create(player, "alice", Map.of("dollar", Amount.of(0L, 2)));
        ProductionAdapters.BankUseCase eu = economyUseCase(store, publisher, repo);

        UUID nonce = UUID.randomUUID();
        ItemStack first = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                30L, UUID.randomUUID(), nonce, "dollar"));
        assertTrue(eu.deposit(player, first).success());
        assertEquals(1, store.preparedCalls);
        // second use of same nonce via same item identity
        ItemStack second = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                30L, UUID.randomUUID(), nonce, "dollar"));
        com.smile.aceeconomy.ports.DepositResult r = eu.deposit(player, second);
        assertFalse(r.success());
        assertEquals("replay.detected", r.reason());
        assertEquals(2, store.preparedCalls);
    }

    @Test
    void economyStorageFailureViaPreparedMapsToCreditFailed() {
        FakeRedemptionStore store = new FakeRedemptionStore();
        store.failure = new PersistenceException("outage");
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID player = UUID.randomUUID();
        repo.create(player, "alice", Map.of("dollar", Amount.of(0L, 2)));
        ProductionAdapters.BankUseCase eu = economyUseCase(store, publisher, repo);

        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                10L, UUID.randomUUID(), UUID.randomUUID(), "dollar"));

        com.smile.aceeconomy.ports.DepositResult r = eu.deposit(player, note);
        assertFalse(r.success());
        assertEquals("credit.failed", r.reason());
        assertEquals(1, store.preparedCalls);
        assertTrue(store.consumed.isEmpty());
    }

    @Test
    void economyAccountMissingViaPreparedMapsToAccountMissing() {
        FakeRedemptionStore store = new FakeRedemptionStore();
        store.programmed = RedemptionResult.accountMissing();
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        UUID player = UUID.randomUUID();
        repo.create(player, "alice", Map.of("dollar", Amount.of(0L, 2)));
        ProductionAdapters.BankUseCase eu = economyUseCase(store, publisher, repo);

        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                10L, UUID.randomUUID(), UUID.randomUUID(), "dollar"));

        com.smile.aceeconomy.ports.DepositResult r = eu.deposit(player, note);
        assertFalse(r.success());
        assertEquals("credit.account-missing", r.reason());
        assertEquals(1, store.preparedCalls);
        assertTrue(store.consumed.isEmpty());
    }

    @Test
    void economyUnknownAccountFailsBeforeStore() {
        FakeRedemptionStore store = new FakeRedemptionStore();
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        ProductionAdapters.BankUseCase eu = economyUseCase(store, publisher, repo);

        UUID missing = UUID.randomUUID();
        ItemStack note = plant(new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                10L, UUID.randomUUID(), UUID.randomUUID(), "dollar"));
        com.smile.aceeconomy.ports.DepositResult r = eu.deposit(missing, note);
        assertFalse(r.success());
        assertEquals("credit.account-missing", r.reason());
        assertEquals(0, store.preparedCalls, "unknown account must fail before reaching the store");
    }
}
