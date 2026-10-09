package com.smile.aceeconomy.infrastructure.banknote;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.BanknoteFactory;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the shared banknote pipeline ({@link BanknoteFlow}) that the
 * command, Java GUI and Bedrock form entries must all use: region snapshot and
 * checks first, money movement on the IO executor, item handover back on the
 * region thread. Storage must never run while the region-tripwire flag is set.
 */
class BanknoteFlowTest {

    /** Region executor with a tripwire: storage doubles fail when called under it. */
    static final class TripwireFolia implements FoliaContextExecutor {
        static final ThreadLocal<Boolean> ON_REGION =
                ThreadLocal.withInitial(() -> Boolean.FALSE);

        Player player;
        Integer refuseDispatchAt;
        Integer captureDispatchAt;
        final List<UUID> dispatched = new ArrayList<>();
        final List<Consumer<Player>> captured = new ArrayList<>();
        final List<Runnable> playerDirect = new ArrayList<>();

        static void checkNotOnRegion(String what) {
            if (Boolean.TRUE.equals(ON_REGION.get())) {
                throw new PersistenceException("storage used on the region thread: " + what);
            }
        }

        private <T> T onRegion(java.util.function.Supplier<T> body) {
            ON_REGION.set(Boolean.TRUE);
            try {
                return body.get();
            } finally {
                ON_REGION.set(Boolean.FALSE);
            }
        }

        @Override
        public void runForPlayer(@NotNull Player target, @NotNull Runnable action) {
            playerDirect.add(action);
            onRegion(() -> {
                action.run();
                return null;
            });
        }

        @Override
        public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
            action.run();
        }

        @Override
        public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
            action.run();
        }

        @Override
        public void runGlobal(@NotNull Runnable action) {
            action.run();
        }

        @Override
        public void runAsync(@NotNull Runnable action) {
            action.run();
        }

        @Override
        public boolean runForPlayer(@NotNull UUID playerId, @NotNull Consumer<Player> action) {
            dispatched.add(playerId);
            if (refuseDispatchAt != null && dispatched.size() == refuseDispatchAt) {
                return false;
            }
            if (player == null) {
                return false;
            }
            if (captureDispatchAt != null && dispatched.size() == captureDispatchAt) {
                captured.add(action);
                return true;
            }
            onRegion(() -> {
                action.accept(player);
                return null;
            });
            return true;
        }
    }

    /** Watchdog scheduler that only arms: tests fire the arms manually. */
    static final class CaptureWatchdogs extends ScheduledThreadPoolExecutor {
        final List<Runnable> armed = new ArrayList<>();

        CaptureWatchdogs() {
            super(0);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            armed.add(command);
            return null;
        }
    }

    /** Durable redemption fake with first-writer-wins semantics. */
    static final class FakeRedemptions implements AtomicRedemptionStore {
        final Set<UUID> consumed = ConcurrentHashMap.newKeySet();
        RedemptionResult programmed;
        RuntimeException failure;
        int preparedCalls;

        @Override
        public RedemptionResult redeem(UUID nonce, UUID accountId, String currencyId, Amount amount) {
            throw new UnsupportedOperationException("legacy path not used by the flow");
        }

        @Override
        public RedemptionResult redeemPrepared(UUID nonce,
                                               com.smile.aceeconomy.domain.Account account,
                                               com.smile.aceeconomy.domain.Transaction transaction,
                                               com.smile.aceeconomy.domain.DebtPolicy debtPolicy) {
            TripwireFolia.checkNotOnRegion("redeemPrepared");
            preparedCalls++;
            if (failure != null) {
                throw failure;
            }
            if (programmed != null) {
                return programmed;
            }
            if (!consumed.add(nonce)) {
                return RedemptionResult.replay();
            }
            return RedemptionResult.committed(transaction.balanceBefore(), transaction.balanceAfter(),
                    transaction.id());
        }
    }

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
    private final TripwireFolia folia = new TripwireFolia();
    private final CaptureWatchdogs watchdogs = new CaptureWatchdogs();
    private final EconomyApi api = mock(EconomyApi.class);
    private final FakeRedemptions redemptions = new FakeRedemptions();
    private final InMemoryAccountRepository repo = new InMemoryAccountRepository();
    private final CurrencyRegistry registry = CurrencyRegistry.of(List.of(
            Currency.define("dollar", "金幣", "$", 2, true)));
    private final CurrencyDisplayHolder display = new CurrencyDisplayHolder(registry);

    private EconomyService economy;
    private ExecutorService io;
    private BanknoteFlow flow;
    private PlayerInventory inventory;

    @BeforeEach
    void setUp() {
        economy = new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                repo, tx -> {}, () -> Instant.now(), new InMemoryTransactionEventPublisher());
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "banknote-flow-test-io");
            t.setDaemon(true);
            return t;
        });
        flow = new BanknoteFlow(api, economy, redemptions, banknotes, display, folia, io,
                watchdogs, Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        io.shutdownNow();
    }

    private Player onlinePlayer(UUID playerId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenReturn(0);
        Mockito.doAnswer(invocation -> new HashMap<Integer, ItemStack>())
                .when(inventory).addItem(any(ItemStack.class));
        return player;
    }

    private static Material solidMaterial() {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        return material;
    }

    private static ItemStack handStack(int amount) {
        Material solid = solidMaterial();
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(solid);
        when(stack.getAmount()).thenReturn(amount);
        return stack;
    }

    private void deductSucceeds(UUID playerId, Amount amount) {
        when(api.withdraw(playerId, "dollar", amount))
                .thenAnswer(invocation -> {
                    TripwireFolia.checkNotOnRegion("api.withdraw");
                    return EconomyResult.success(amount);
                });
        when(api.deposit(playerId, "dollar", amount))
                .thenAnswer(invocation -> {
                    TripwireFolia.checkNotOnRegion("api.deposit");
                    return EconomyResult.success(amount);
                });
    }

    private static BanknoteFlow.WithdrawOutcome joinWithdraw(
            CompletableFuture<BanknoteFlow.WithdrawOutcome> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("withdraw pipeline must complete bounded", e);
        }
    }

    private static BanknoteFlow.RedeemOutcome joinRedeem(
            CompletableFuture<BanknoteFlow.RedeemOutcome> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("redeem pipeline must complete bounded", e);
        }
    }

    private static void awaitArms(CaptureWatchdogs watchdogs, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (watchdogs.armed.size() >= expected) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(expected, watchdogs.armed.size(), "timed out waiting for watchdog arms");
    }

    // ---------------- shared non-blocking withdraw ----------------

    @Test
    void withdrawDeliversWithoutStorageOnTheRegionThread() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        deductSucceeds(playerId, amount);

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isDelivered(), "happy path must deliver: " + outcome);
        assertNotNull(outcome.note());
        assertNotNull(outcome.claim());
        assertEquals(100L, outcome.claim().value());
        verify(inventory).addItem(any(ItemStack.class));
        // The delivered note decodes back to the paid claim.
        Optional<BanknoteClaim> decoded = banknotes.decode(outcome.note());
        assertTrue(decoded.isPresent());
        assertEquals(outcome.claim().nonce(), decoded.get().nonce());
    }

    @Test
    void fullInventoryAtProbeRefusesBeforeAnyDeduction() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(-1);
        Amount amount = Amount.of(100L, 2);

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isRefusedFull(), "a full inventory must refuse: " + outcome);
        verify(api, never()).withdraw(any(), any(), any());
        assertTrue(banknotes.minted().isEmpty());
    }

    @Test
    void unknownCurrencyRefusesBeforeAnyDeduction() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "token", amount));

        assertTrue(outcome.isRefused(), "unknown currency must refuse: " + outcome);
        assertEquals(EconomyError.CURRENCY_NOT_FOUND, outcome.error());
        verify(api, never()).withdraw(any(), any(), any());
    }

    @Test
    void insufficientFundsRefusesWithoutMintOrDelivery() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenAnswer(invocation -> {
            TripwireFolia.checkNotOnRegion("api.withdraw");
            return EconomyResult.failure(EconomyError.INSUFFICIENT_FUNDS, "insufficient funds");
        });

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isRefused(), "no money means no note: " + outcome);
        assertEquals(EconomyError.INSUFFICIENT_FUNDS, outcome.error());
        assertTrue(banknotes.minted().isEmpty());
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test
    void gonePlayerAtProbeRefusesWithoutDeduction() {
        UUID playerId = UUID.randomUUID();
        folia.player = null;
        Amount amount = Amount.of(100L, 2);

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isRefused(), "a gone player must refuse: " + outcome);
        verify(api, never()).withdraw(any(), any(), any());
    }

    // ---------------- delivery: full / leftover / throw ----------------

    @Test
    void inventoryFilledDuringDeliveryRefundsAndReportsFull() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0, -1);
        Amount amount = Amount.of(100L, 2);
        deductSucceeds(playerId, amount);

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isRefunded(), "a delivery that cannot place must refund: " + outcome);
        assertTrue(outcome.refundOk());
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(api).deposit(playerId, "dollar", amount);
    }

    @Test
    void leftoverFromAddItemIsProvenUndeliveredAndRefunded() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        deductSucceeds(playerId, amount);
        Mockito.doAnswer(invocation -> {
            ItemStack note = invocation.getArgument(0);
            HashMap<Integer, ItemStack> leftover = new HashMap<>();
            leftover.put(0, note);
            return leftover;
        }).when(inventory).addItem(any(ItemStack.class));

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isRefunded(), "leftover means the note never landed: " + outcome);
        assertTrue(outcome.refundOk());
        verify(api).deposit(playerId, "dollar", amount);
        assertTrue(redemptions.consumed.isEmpty(),
                "a proven-undelivered note needs no nonce voiding");
    }

    @Test
    void addItemThrowIsUncertainAndVoidsTheNoteAtomicallyWithRefund() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        repo.create(playerId, "alice", Map.of("dollar", Amount.of(1000L, 2)));
        Amount amount = Amount.of(100L, 2);
        deductSucceeds(playerId, amount);
        Mockito.doAnswer(invocation -> {
            throw new RuntimeException("slot outage after unknown placement");
        }).when(inventory).addItem(any(ItemStack.class));

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isVoidRefunded(),
                "an uncertain handover must void the note and refund together: " + outcome);
        // The minted nonce is dead: the physical note can never be redeemed.
        assertTrue(redemptions.consumed.contains(outcome.claim().nonce()),
                "the uncertain note's nonce must be consumed with the refund");
        // And the balance is whole again through the real service.
        assertEquals(0, Amount.of(1000L, 2).compareTo(
                repo.load(playerId).orElseThrow().balanceOf("dollar")));
    }

    @Test
    void uncertainDeliveryWithFailingAtomicCompensationKeepsPendingWithoutBlindRefund() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        repo.create(playerId, "alice", Map.of("dollar", Amount.of(1000L, 2)));
        Amount amount = Amount.of(100L, 2);
        deductSucceeds(playerId, amount);
        Mockito.doAnswer(invocation -> {
            throw new RuntimeException("slot outage after unknown placement");
        }).when(inventory).addItem(any(ItemStack.class));
        redemptions.failure = new PersistenceException("compensation storage outage");

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isPending(),
                "a failed atomic void+refund must stay pending, never blind-refund: " + outcome);
        verify(api, never()).deposit(any(), any(), any());
        assertTrue(redemptions.consumed.isEmpty(),
                "nothing was decided, so the nonce must stay untouched for diagnosis");
    }

    @Test
    void uncertainDeliveryWithIndeterminateCompensationKeepsPending() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        repo.create(playerId, "alice", Map.of("dollar", Amount.of(1000L, 2)));
        Amount amount = Amount.of(100L, 2);
        deductSucceeds(playerId, amount);
        Mockito.doAnswer(invocation -> {
            throw new RuntimeException("slot outage after unknown placement");
        }).when(inventory).addItem(any(ItemStack.class));
        redemptions.failure = new IndeterminatePersistenceException(
                "commit response lost", new RuntimeException("conn reset"));

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isPending(), "indeterminate void+refund must stay pending: " + outcome);
        assertNotNull(outcome.pendingCause());
        verify(api, never()).deposit(any(), any(), any());
    }

    @Test
    void plainRefundFailureIsReportedWithSingleAttempt() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        when(inventory.firstEmpty()).thenReturn(0, -1);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenAnswer(invocation -> {
            TripwireFolia.checkNotOnRegion("api.withdraw");
            return EconomyResult.success(amount);
        });
        when(api.deposit(playerId, "dollar", amount)).thenAnswer(invocation -> {
            TripwireFolia.checkNotOnRegion("api.deposit");
            return EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "account missing");
        });

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isRefunded(), "delivery-full must still close the outcome: " + outcome);
        assertFalse(outcome.refundOk(), "the failed refund must stay visible for reconciliation");
        verify(api, times(1)).deposit(playerId, "dollar", amount);
    }

    // ---------------- deduction uncertainty ----------------

    @Test
    void indeterminateDeductionPendsWithoutRefundMintOrDelivery() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        IndeterminatePersistenceException indeterminate = new IndeterminatePersistenceException(
                "commit response lost", new RuntimeException("conn reset"));
        when(api.withdraw(playerId, "dollar", amount)).thenAnswer(invocation -> {
            TripwireFolia.checkNotOnRegion("api.withdraw");
            throw indeterminate;
        });

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isPending(), "an uncertain deduction must pend: " + outcome);
        assertEquals(indeterminate, outcome.pendingCause());
        verify(api, never()).deposit(any(), any(), any());
        assertTrue(banknotes.minted().isEmpty());
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test
    void uncommittedDeductionFailureRefusesWithoutBlindRetry() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenAnswer(invocation -> {
            TripwireFolia.checkNotOnRegion("api.withdraw");
            throw new PersistenceException("read outage before commit");
        });

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isRefused(), "an uncommitted failure must refuse: " + outcome);
        assertEquals(EconomyError.AUDIT_FAILURE, outcome.error());
        verify(api, never()).deposit(any(), any(), any());
    }

    // ---------------- dispatch timeouts and late callbacks ----------------

    @Test
    void retiredDeliveryDispatchRefundsBoundedAndLateCallbackIsNoop() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        deductSucceeds(playerId, amount);
        folia.captureDispatchAt = 2; // probe runs; delivery accepted but never runs

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                flow.withdraw(playerId, "dollar", amount);
        assertFalse(future.isDone(), "a retired delivery must not complete on its own");
        // One arm for the probe, one for the delivery.
        awaitArms(watchdogs, 2);
        watchdogs.armed.get(1).run();

        BanknoteFlow.WithdrawOutcome outcome = joinWithdraw(future);
        assertTrue(outcome.isRefunded(), "the watchdog must refund: " + outcome);
        verify(api, times(1)).deposit(playerId, "dollar", amount);

        // The retired task finally runs late: no delivery, no second refund.
        folia.captured.get(0).accept(folia.player);
        assertEquals(outcome, joinWithdraw(future));
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(api, times(1)).deposit(playerId, "dollar", amount);
    }

    @Test
    void retiredProbeDispatchRefusesBoundedWithoutDeduction() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        folia.captureDispatchAt = 1;

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                flow.withdraw(playerId, "dollar", amount);
        assertFalse(future.isDone());
        watchdogs.armed.get(0).run();

        BanknoteFlow.WithdrawOutcome outcome = joinWithdraw(future);
        assertTrue(outcome.isRefused(), "a probe that never ran must refuse: " + outcome);
        verify(api, never()).withdraw(any(), any(), any());

        folia.captured.get(0).accept(folia.player);
        verify(api, never()).withdraw(any(), any(), any());
    }

    // ---------------- shared redeem pipeline ----------------

    private ItemStack plantNote(BanknoteClaim claim) {
        ItemStack stack = handStack(1);
        banknotes.register(stack, claim);
        return stack;
    }

    private static com.smile.acelib.item.ItemIdentity v2Identity() {
        return new com.smile.acelib.item.ItemIdentity(BanknoteClaim.V2_NAMESPACE,
                BanknoteClaim.V2_KEY, BanknoteClaim.V2_SCHEMA.major(),
                BanknoteClaim.V2_SCHEMA.minor());
    }

    private BanknoteClaim v2Claim(long value, UUID nonce) {
        return new BanknoteClaim(v2Identity(), BanknoteClaim.V2_SCHEMA,
                value, UUID.randomUUID(), nonce, "dollar");
    }

    @Test
    void redeemCommitsOffRegionAndRemovesOneOnRegion() {
        UUID playerId = UUID.randomUUID();
        Player player = onlinePlayer(playerId);
        folia.player = player;
        UUID nonce = UUID.randomUUID();
        ItemStack note = plantNote(v2Claim(100L, nonce));
        when(inventory.getItemInMainHand()).thenReturn(note);
        AtomicReference<DepositResult> commit = new AtomicReference<>();
        BanknoteFlow.RedeemCommit commitFn = (id, snapshot) -> {
            TripwireFolia.checkNotOnRegion("redeem commit");
            DepositResult result = DepositResult.success(100L, "dollar");
            commit.set(result);
            return result;
        };

        BanknoteFlow.RedeemOutcome outcome =
                joinRedeem(flow.redeem(playerId, note, BanknoteFlow.Hand.MAIN_HAND, commitFn));

        assertTrue(outcome.isCredited(), "a committed redeem must remove the note: " + outcome);
        verify(inventory).setItemInMainHand(null);
    }

    @Test
    void redeemRejectionKeepsTheItem() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        ItemStack note = handStack(1);
        BanknoteFlow.RedeemCommit commitFn =
                (id, snapshot) -> DepositResult.rejected("replay.detected");

        BanknoteFlow.RedeemOutcome outcome =
                joinRedeem(flow.redeem(playerId, note, BanknoteFlow.Hand.MAIN_HAND, commitFn));

        assertTrue(outcome.isRejected(), "a replay must reject: " + outcome);
        assertEquals("replay.detected", outcome.reason());
        verify(inventory, never()).setItemInMainHand(any());
    }

    @Test
    void redeemCommitIndeterminateKeepsTheItemAndPends() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        ItemStack note = handStack(1);
        IndeterminatePersistenceException indeterminate = new IndeterminatePersistenceException(
                "commit response lost", new RuntimeException("conn reset"));
        BanknoteFlow.RedeemCommit commitFn = (id, snapshot) -> {
            TripwireFolia.checkNotOnRegion("redeem commit");
            throw indeterminate;
        };

        BanknoteFlow.RedeemOutcome outcome =
                joinRedeem(flow.redeem(playerId, note, BanknoteFlow.Hand.MAIN_HAND, commitFn));

        assertTrue(outcome.isPending(), "an uncertain credit must pend: " + outcome);
        assertEquals(indeterminate, outcome.pendingCause());
        verify(inventory, never()).setItemInMainHand(any());
        verify(inventory, never()).getItemInMainHand();
    }

    @Test
    void redeemSwappedHandKeepsTheCurrentItem() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        ItemStack snapshot = plantNote(v2Claim(100L, UUID.randomUUID()));
        ItemStack swapped = handStack(1);
        when(swapped.isSimilar(snapshot)).thenReturn(false);
        when(inventory.getItemInMainHand()).thenReturn(swapped);
        BanknoteFlow.RedeemCommit commitFn =
                (id, snap) -> DepositResult.success(100L, "dollar");

        BanknoteFlow.RedeemOutcome outcome =
                joinRedeem(flow.redeem(playerId, snapshot, BanknoteFlow.Hand.MAIN_HAND, commitFn));

        assertTrue(outcome.isItemMismatch(), "a swapped hand must not be touched: " + outcome);
        verify(inventory, never()).setItemInMainHand(any());
    }

    @Test
    void redeemRemovalThrowRetainsWithDiagnosis() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        ItemStack note = plantNote(v2Claim(100L, UUID.randomUUID()));
        when(inventory.getItemInMainHand()).thenReturn(note);
        Mockito.doThrow(new RuntimeException("slot outage")).when(inventory)
                .setItemInMainHand(any());
        BanknoteFlow.RedeemCommit commitFn =
                (id, snap) -> DepositResult.success(100L, "dollar");

        BanknoteFlow.RedeemOutcome outcome =
                joinRedeem(flow.redeem(playerId, note, BanknoteFlow.Hand.MAIN_HAND, commitFn));

        assertTrue(outcome.isCreditRetained(),
                "a committed credit with failed removal stays retained: " + outcome);
    }

    @Test
    void redeemOffHandRemovesFromOffHand() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        ItemStack note = plantNote(v2Claim(50L, UUID.randomUUID()));
        when(inventory.getItemInOffHand()).thenReturn(note);
        BanknoteFlow.RedeemCommit commitFn =
                (id, snap) -> DepositResult.success(50L, "dollar");

        BanknoteFlow.RedeemOutcome outcome =
                joinRedeem(flow.redeem(playerId, note, BanknoteFlow.Hand.OFF_HAND, commitFn));

        assertTrue(outcome.isCredited(), "off-hand redeem must clear the off hand: " + outcome);
        verify(inventory).setItemInOffHand(null);
        verify(inventory, never()).setItemInMainHand(any());
    }

    // ---------------- shutdown gate ----------------

    @Test
    void shutdownStopsNewOperationsAndDrainsAcceptedOnes() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        deductSucceeds(playerId, amount);

        BanknoteFlow.ShutdownResult result = flow.shutdown(5_000L);

        assertFalse(flow.isAccepting(), "shutdown must stop accepting new operations");
        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow.withdraw(playerId, "dollar", amount));
        assertTrue(outcome.isPending(), "post-shutdown withdraw must pend: " + outcome);
        verify(api, never()).withdraw(any(), any(), any());

        ItemStack note = handStack(1);
        BanknoteFlow.RedeemOutcome redeemOutcome = joinRedeem(
                flow.redeem(playerId, note, BanknoteFlow.Hand.MAIN_HAND,
                        (id, snapshot) -> DepositResult.success(1L, "dollar")));
        assertTrue(redeemOutcome.isPending(), "post-shutdown redeem must pend: " + redeemOutcome);
        assertEquals(0, result.leftBehind());
    }
}
