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
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Formal close-order tests: a deduction whose delivery region task was
 * retired must still be compensated when shutdown fires well before the
 * dispatch timeout, while storage and the scheduler are still up. A decided
 * refund closes the outcome with its flag; only an undecidable operation may
 * stay behind, and then only as a diagnosable pending carrying player,
 * amount, nonce and stage — never a bare count.
 */
class BanknoteFlowShutdownCompensationTest {

    /** Region executor with a real scheduler: retired tasks simply never run. */
    static final class RetiringFolia implements com.smile.aceeconomy.ports.FoliaContextExecutor {
        Player player;
        Integer captureDispatchAt;
        final List<UUID> dispatched = new ArrayList<>();
        final List<Consumer<Player>> captured = new ArrayList<>();

        @Override
        public void runForPlayer(@NotNull Player target, @NotNull Runnable action) {
            action.run();
        }

        @Override
        public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
        }

        @Override
        public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
        }

        @Override
        public void runGlobal(@NotNull Runnable action) {
        }

        @Override
        public void runAsync(@NotNull Runnable action) {
        }

        @Override
        public boolean runForPlayer(@NotNull UUID playerId, @NotNull Consumer<Player> action) {
            dispatched.add(playerId);
            if (player == null) {
                return false;
            }
            if (captureDispatchAt != null && dispatched.size() == captureDispatchAt) {
                captured.add(action);
                return true;
            }
            action.accept(player);
            return true;
        }
    }

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
    private final RetiringFolia folia = new RetiringFolia();
    private final EconomyApi api = mock(EconomyApi.class);
    private final CurrencyRegistry registry = CurrencyRegistry.of(List.of(
            Currency.define("dollar", "金幣", "$", 2, true)));
    private final CurrencyDisplayHolder display = new CurrencyDisplayHolder(registry);

    private EconomyService economy;
    private ExecutorService io;
    private java.util.concurrent.ScheduledExecutorService watchdogs;
    private PlayerInventory inventory;

    @BeforeEach
    void setUp() {
        economy = new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                new InMemoryAccountRepository(), tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "shutdown-compensation-io");
            t.setDaemon(true);
            return t;
        });
        watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shutdown-compensation-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        io.shutdownNow();
        watchdogs.shutdownNow();
    }

    private Player onlinePlayer(UUID playerId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenReturn(0);
        Mockito.doAnswer(invocation -> new HashMap<Integer, org.bukkit.inventory.ItemStack>())
                .when(inventory).addItem(any(org.bukkit.inventory.ItemStack.class));
        return player;
    }

    private BanknoteFlow flow(Duration dispatchTimeout) {
        return new BanknoteFlow(api, economy, mock(AtomicRedemptionStore.class), banknotes,
                display, folia, io, watchdogs, dispatchTimeout);
    }

    private BanknoteFlow flowWithEconomy(EconomyService economy, Duration dispatchTimeout) {
        return new BanknoteFlow(api, economy, mock(AtomicRedemptionStore.class), banknotes,
                display, folia, io, watchdogs, dispatchTimeout);
    }

    private static BanknoteFlow.WithdrawOutcome join(
            CompletableFuture<BanknoteFlow.WithdrawOutcome> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("shutdown settlement must complete bounded", e);
        }
    }

    @Test
    void retiredDeliveryIsRefundedDuringShutdownBeforeTheDispatchTimeout() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        folia.captureDispatchAt = 2; // probe runs; the delivery task is retired
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        // Production shape: the dispatch timeout (30s) is longer than the
        // shutdown deadline, so only an active early settlement can compensate
        // while storage is still up.
        BanknoteFlow flow = flow(Duration.ofSeconds(30));

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                flow.withdraw(playerId, "dollar", amount);
        BanknoteFlow.ShutdownResult result = flow.shutdown(2_000L);

        BanknoteFlow.WithdrawOutcome outcome = join(future);
        assertTrue(outcome.isRefunded(),
                "shutdown must compensate the stranded deduction: " + outcome);
        assertTrue(outcome.refundOk());
        verify(api, times(1)).deposit(playerId, "dollar", amount);
        assertEquals(0, result.leftBehind(), "nothing may strand without a diagnosis");
        assertTrue(result.pending().isEmpty());

        // The retired task finally runs late: no delivery, no second refund.
        folia.captured.get(0).accept(folia.player);
        verify(inventory, never()).addItem(any());
        verify(api, times(1)).deposit(playerId, "dollar", amount);
    }

    @Test
    void failedShutdownRefundReportsRefundedWithFailedFlag() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        folia.captureDispatchAt = 2;
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount)).thenReturn(
                EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "account missing"));
        BanknoteFlow flow = flow(Duration.ofSeconds(30));

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                flow.withdraw(playerId, "dollar", amount);
        BanknoteFlow.ShutdownResult result = flow.shutdown(2_000L);

        BanknoteFlow.WithdrawOutcome outcome = join(future);
        assertTrue(outcome.isRefunded(), "a decided refund failure closes refunded: " + outcome);
        assertTrue(!outcome.refundOk(), "the failed refund must stay visible for reconciliation");
        verify(api, times(1)).deposit(playerId, "dollar", amount);
        assertEquals(0, result.leftBehind());
    }

    @Test
    void blockedDeductionLeavesDiagnosablePendingWithStage() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        CountDownLatch releaseDeduction = new CountDownLatch(1);
        when(api.withdraw(playerId, "dollar", amount)).thenAnswer(invocation -> {
            try {
                if (!releaseDeduction.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("deduction gate was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
            return EconomyResult.success(amount);
        });
        when(api.deposit(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        BanknoteFlow flow = flow(Duration.ofSeconds(30));

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                flow.withdraw(playerId, "dollar", amount);
        BanknoteFlow.ShutdownResult result = flow.shutdown(200L);

        assertEquals(1, result.leftBehind());
        assertEquals(1, result.pending().size(), "the stranded case needs a diagnosis, not a count");
        BanknoteFlow.PendingOperation pending = result.pending().get(0);
        assertEquals(playerId, pending.playerId());
        assertEquals(amount.value().toPlainString(), pending.amount());
        assertNull(pending.nonce(), "no note was minted yet");
        assertEquals("deduct", pending.stage());

        releaseDeduction.countDown();
        BanknoteFlow.WithdrawOutcome outcome = join(future);
        assertTrue(outcome.isDelivered(), "releasing the gate settles normally: " + outcome);
    }

    @Test
    void blockedAtomicCompensationLeavesPendingWithNonceAndNoBlindRefund() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        Mockito.doAnswer(invocation -> {
            throw new RuntimeException("slot outage after unknown placement");
        }).when(inventory).addItem(any(org.bukkit.inventory.ItemStack.class));
        CountDownLatch releaseCompensation = new CountDownLatch(1);
        EconomyService blockingEconomy = mock(EconomyService.class);
        when(blockingEconomy.compensateUndeliveredNote(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    try {
                        if (!releaseCompensation.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("compensation gate was never released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(interrupted);
                    }
                    return EconomyResult.success(amount);
                });
        BanknoteFlow flow = flowWithEconomy(blockingEconomy, Duration.ofSeconds(30));

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                flow.withdraw(playerId, "dollar", amount);
        // Wait for the uncertain handover to reach the atomic compensation.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                verify(blockingEconomy, Mockito.timeout(100).times(1))
                        .compensateUndeliveredNote(any(), any(), any(), any(), any(), any());
                break;
            } catch (Throwable t) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("atomic compensation was never reached", t);
                }
            }
        }
        BanknoteFlow.ShutdownResult result = flow.shutdown(200L);

        assertEquals(1, result.leftBehind());
        assertEquals(1, result.pending().size());
        BanknoteFlow.PendingOperation pending = result.pending().get(0);
        assertEquals(playerId, pending.playerId());
        assertNotNull(pending.nonce(), "the uncertain note's nonce must be diagnosed");
        assertEquals("deliver", pending.stage());
        verify(api, never()).deposit(any(), any(), any());

        releaseCompensation.countDown();
        BanknoteFlow.WithdrawOutcome outcome = join(future);
        assertTrue(outcome.isVoidRefunded(), "releasing the gate voids and refunds: " + outcome);
    }

    @Test
    void retiredProbeSettlesDuringShutdownWithoutAnyDeduction() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        folia.captureDispatchAt = 1; // probe accepted but never runs
        Amount amount = Amount.of(100L, 2);
        BanknoteFlow flow = flow(Duration.ofSeconds(30));

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                flow.withdraw(playerId, "dollar", amount);
        BanknoteFlow.ShutdownResult result = flow.shutdown(2_000L);

        BanknoteFlow.WithdrawOutcome outcome = join(future);
        assertTrue(outcome.isRefused(), "a probe that never ran must refuse: " + outcome);
        verify(api, never()).withdraw(any(), any(), any());
        assertEquals(0, result.leftBehind());
    }
}
