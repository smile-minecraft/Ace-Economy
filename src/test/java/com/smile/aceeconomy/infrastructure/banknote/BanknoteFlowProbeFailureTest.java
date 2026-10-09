package com.smile.aceeconomy.infrastructure.banknote;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Probe-stage failures happen before any deduction, so they must refuse
 * without charging and without minting. A dispatch that throws before the
 * callback ever runs is the same as a rejected dispatch: nothing happened.
 * An inventory read that throws inside the region callback is also nothing
 * happened: no deduction, no note, no refund masquerading as compensation.
 */
class BanknoteFlowProbeFailureTest {

    static final class ThrowingDispatch implements FoliaContextExecutor {
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
            throw new IllegalStateException("scheduler rejected the probe dispatch");
        }
    }

    static final class ImmediateRegion implements FoliaContextExecutor {
        Player player;

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
            action.accept(player);
            return true;
        }
    }

    private final FakeBanknoteFactory banknotes = new FakeBanknoteFactory();
    private final CurrencyRegistry registry = CurrencyRegistry.of(List.of(
            Currency.define("dollar", "金幣", "$", 2, true)));
    private final CurrencyDisplayHolder display = new CurrencyDisplayHolder(registry);
    private final EconomyApi api = mock(EconomyApi.class);

    private EconomyService economy;
    private ExecutorService io;
    private java.util.concurrent.ScheduledExecutorService watchdogs;

    @BeforeEach
    void setUp() {
        economy = new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                new InMemoryAccountRepository(), tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "probe-failure-test-io");
            t.setDaemon(true);
            return t;
        });
        watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "probe-failure-test-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        io.shutdownNow();
        watchdogs.shutdownNow();
    }

    private static BanknoteFlow.WithdrawOutcome join(
            CompletableFuture<BanknoteFlow.WithdrawOutcome> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("withdraw must complete bounded", e);
        }
    }

    @Test
    void probeDispatchThrowRefusesWithoutDeductionOrMint() {
        UUID playerId = UUID.randomUUID();
        AtomicRedemptionStore redemptions = mock(AtomicRedemptionStore.class);
        BanknoteFlow flow = new BanknoteFlow(api, economy, redemptions, banknotes, display,
                new ThrowingDispatch(), io, watchdogs, Duration.ofMillis(200));
        Amount amount = Amount.of(100L, 2);

        BanknoteFlow.WithdrawOutcome outcome = join(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isRefused(),
                "a probe dispatch that throws before running must refuse, not refund: " + outcome);
        assertEquals(EconomyError.TRANSACTION_CANCELLED, outcome.error());
        verify(api, never()).withdraw(any(), any(), any());
        verify(api, never()).deposit(any(), any(), any());
        assertTrue(banknotes.minted().isEmpty());
    }

    @Test
    void probeInventoryThrowRefusesWithoutDeductionOrMint() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenThrow(new RuntimeException("slot read outage"));
        ImmediateRegion folia = new ImmediateRegion();
        folia.player = player;
        AtomicRedemptionStore redemptions = mock(AtomicRedemptionStore.class);
        BanknoteFlow flow = new BanknoteFlow(api, economy, redemptions, banknotes, display,
                folia, io, watchdogs, Duration.ofMillis(200));
        Amount amount = Amount.of(100L, 2);

        BanknoteFlow.WithdrawOutcome outcome =
                join(flow.withdraw(playerId, "dollar", amount));

        assertTrue(outcome.isRefused(),
                "an inventory read failure before any deduction must refuse: " + outcome);
        verify(api, never()).withdraw(any(), any(), any());
        verify(api, never()).deposit(any(), any(), any());
        assertTrue(banknotes.minted().isEmpty());
        Mockito.verify(inventory, never()).addItem(any());
    }
}
