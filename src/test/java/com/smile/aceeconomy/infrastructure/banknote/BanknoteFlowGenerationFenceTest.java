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
import com.smile.aceeconomy.ports.DepositResult;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Generation fence: an operation is accepted only while its generation is
 * still current. A stale generation refuses before any money moves; a
 * delivery or removal callback that runs late after a reload must not place
 * or remove items — a deducted amount is refunded, a committed credit stays
 * retained. Once money has committed, settlement stays money-safe.
 */
class BanknoteFlowGenerationFenceTest {

    static final class CapturingFolia implements com.smile.aceeconomy.ports.FoliaContextExecutor {
        Player player;
        final List<Consumer<Player>> captured = new ArrayList<>();
        Integer captureDispatchAt;
        int dispatches;

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
            if (player == null) {
                return false;
            }
            dispatches++;
            if (captureDispatchAt != null && dispatches == captureDispatchAt) {
                captured.add(action);
                return true;
            }
            action.accept(player);
            return true;
        }
    }

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
    private final CapturingFolia folia = new CapturingFolia();
    private final EconomyApi api = mock(EconomyApi.class);
    private final CurrencyRegistry registry = CurrencyRegistry.of(List.of(
            Currency.define("dollar", "金幣", "$", 2, true)));
    private final CurrencyDisplayHolder display = new CurrencyDisplayHolder(registry);

    private EconomyService economy;
    private ExecutorService io;
    private ManualWatchdogs watchdogs;
    private PlayerInventory inventory;

    /** Watchdog scheduler that only arms: tests fire the arms manually. */
    static final class ManualWatchdogs extends java.util.concurrent.ScheduledThreadPoolExecutor {
        final List<Runnable> armed = new ArrayList<>();

        ManualWatchdogs() {
            super(0);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(
                Runnable command, long delay, TimeUnit unit) {
            armed.add(command);
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        economy = new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                new InMemoryAccountRepository(), tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "generation-fence-test-io");
            t.setDaemon(true);
            return t;
        });
        watchdogs = new ManualWatchdogs();
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
        Mockito.doAnswer(invocation -> new HashMap<Integer, org.bukkit.inventory.ItemStack>())
                .when(inventory).addItem(any(org.bukkit.inventory.ItemStack.class));
        return player;
    }

    private BanknoteFlow flow() {
        return new BanknoteFlow(api, economy, mock(AtomicRedemptionStore.class), banknotes,
                display, folia, io, watchdogs, Duration.ofMillis(200));
    }

    private static BanknoteFlow.WithdrawOutcome joinWithdraw(
            CompletableFuture<BanknoteFlow.WithdrawOutcome> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("generation-fenced withdraw must complete bounded", e);
        }
    }

    private CountDownLatch blockIoQueue() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        io.submit(() -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("IO gate was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("IO gate was interrupted", interrupted);
            }
        });
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "IO gate must start");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("IO gate did not start", interrupted);
        }
        return release;
    }

    @Test
    void staleGenerationRefusesBeforeAnyDeduction() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);

        BanknoteFlow.WithdrawOutcome outcome =
                joinWithdraw(flow().withdraw(playerId, "dollar", amount, () -> false));

        assertTrue(outcome.isRefused(), "a stale generation must refuse: " + outcome);
        assertEquals(EconomyError.TRANSACTION_CANCELLED, outcome.error());
        verify(api, never()).withdraw(any(), any(), any());
        verify(inventory, never()).addItem(any());
        assertTrue(banknotes.minted().isEmpty());
    }

    @Test
    void queuedWithdrawIsRejectedAfterGenerationChangesBeforeIoStarts() {
        CountDownLatch release = blockIoQueue();
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        AtomicBoolean current = new AtomicBoolean(true);
        BanknoteFlow banknoteFlow = flow();

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                banknoteFlow.withdraw(playerId, "dollar", amount, current::get);
        current.set(false);
        release.countDown();

        BanknoteFlow.WithdrawOutcome outcome = joinWithdraw(future);
        assertTrue(outcome.isRefused(), "a queued stale withdraw must refuse: " + outcome);
        assertEquals(EconomyError.TRANSACTION_CANCELLED, outcome.error());
        verify(api, never()).withdraw(any(), any(), any());
        assertTrue(banknotes.minted().isEmpty());
        assertTrue(banknoteFlow.shutdown(0).pending().isEmpty());
    }

    @Test
    void lateDeliveryAfterGenerationChangeRefundsInsteadOfDelivering() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        folia.captureDispatchAt = 2; // probe runs; the delivery dispatch is retired
        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        AtomicBoolean current = new AtomicBoolean(true);

        CompletableFuture<BanknoteFlow.WithdrawOutcome> future =
                flow().withdraw(playerId, "dollar", amount, current::get);
        // Wait for the deduction to commit and the delivery to be captured.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (folia.captured.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(1, folia.captured.size(), "the delivery dispatch must be captured");
        // Reload lands while the delivery is still queued.
        current.set(false);
        folia.captured.get(0).accept(folia.player);

        BanknoteFlow.WithdrawOutcome outcome = joinWithdraw(future);
        assertTrue(outcome.isRefunded(), "a stale delivery must refund, never place: " + outcome);
        assertEquals(BanknoteFlow.REASON_STALE_GENERATION, outcome.reason());
        verify(inventory, never()).addItem(any());
        verify(api).deposit(playerId, "dollar", amount);
    }

    @Test
    void staleGenerationRedeemRejectsBeforeAnyCommit() {
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        org.bukkit.inventory.ItemStack note = mock(org.bukkit.inventory.ItemStack.class);

        BanknoteFlow.RedeemOutcome outcome;
        try {
            outcome = flow().redeem(playerId, note, BanknoteFlow.Hand.MAIN_HAND,
                    (id, snapshot) -> DepositResult.success(1L, "dollar"), () -> false)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("generation-fenced redeem must complete bounded", e);
        }

        assertTrue(outcome.isRejected(), "a stale redeem must reject: " + outcome);
        assertEquals("transaction.cancelled", outcome.reason());
        verify(inventory, never()).getItemInMainHand();
    }

    @Test
    void queuedRedeemIsRejectedAfterGenerationChangesBeforeIoStarts() {
        CountDownLatch release = blockIoQueue();
        UUID playerId = UUID.randomUUID();
        folia.player = onlinePlayer(playerId);
        org.bukkit.inventory.ItemStack note = mock(org.bukkit.inventory.ItemStack.class);
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger commitCalls = new AtomicInteger();
        BanknoteFlow banknoteFlow = flow();

        CompletableFuture<BanknoteFlow.RedeemOutcome> future = banknoteFlow.redeem(
                playerId, note, BanknoteFlow.Hand.MAIN_HAND,
                (id, snapshot) -> {
                    commitCalls.incrementAndGet();
                    return DepositResult.success(1L, "dollar");
                }, current::get);
        current.set(false);
        release.countDown();

        BanknoteFlow.RedeemOutcome outcome;
        try {
            outcome = future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("generation-fenced queued redeem must complete bounded", e);
        }
        assertTrue(outcome.isRejected(), "a queued stale redeem must reject: " + outcome);
        assertEquals("transaction.cancelled", outcome.reason());
        assertEquals(0, commitCalls.get(), "a stale queued redeem must not credit");
        verify(inventory, never()).setItemInMainHand(any());
        assertTrue(banknoteFlow.shutdown(0).pending().isEmpty());
    }
}
