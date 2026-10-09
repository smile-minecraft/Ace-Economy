package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;

import org.bukkit.Location;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
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
 * The shared-flow click path must carry the session generation into the
 * flow: a reload that drops the backend session while the handover is still
 * queued refunds instead of delivering, and a click that arrives already
 * stale never reaches the ledger.
 */
class BankGuiSharedFlowGenerationTest {

    static final class CapturingFolia implements FoliaContextExecutor {
        Player player;
        Integer captureDispatchAt;
        final List<Consumer<Player>> captured = new ArrayList<>();
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

    static final class ManualWatchdogs extends ScheduledThreadPoolExecutor {
        final List<Runnable> armed = new ArrayList<>();

        ManualWatchdogs() {
            super(0);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            armed.add(command);
            return null;
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
    private final ManualWatchdogs watchdogs = new ManualWatchdogs();
    private Player player;
    private PlayerInventory inventory;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        economy = new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                new InMemoryAccountRepository(), tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gui-generation-test-io");
            t.setDaemon(true);
            return t;
        });
        playerId = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenReturn(0);
        Mockito.doAnswer(invocation -> new HashMap<Integer, ItemStack>())
                .when(inventory).addItem(any(ItemStack.class));
        folia.player = player;
    }

    @AfterEach
    void tearDown() {
        io.shutdownNow();
    }

    private V2BankGuiSession sessionWithFlow(FakeGuiService gui) {
        BanknoteFlow flow = new BanknoteFlow(api, economy, mock(AtomicRedemptionStore.class),
                banknotes, display, folia, io, watchdogs, Duration.ofMillis(200));
        return new V2BankGuiSession(gui, folia, new StubBankGuiUseCase(),
                slot -> BankGuiAction.withdraw(100L), flow);
    }

    private static V2BankGuiSession.ClickOutcome join(
            CompletableFuture<V2BankGuiSession.ClickOutcome> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("shared-flow click must complete bounded", e);
        }
    }

    @Test
    void reloadBeforeLateDeliveryRefundsInsteadOfDelivering() {
        FakeGuiService gui = FakeGuiService.available();
        folia.captureDispatchAt = 2; // probe runs; the delivery dispatch is retired
        V2BankGuiSession session = sessionWithFlow(gui);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long generation = open.session().generation();

        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));

        CompletableFuture<V2BankGuiSession.ClickOutcome> future =
                session.handleClickAsync(playerId, generation, 0);
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
        // A reload drops the backend session while the handover is queued.
        gui.closeInventory(playerId, generation);
        folia.captured.get(0).accept(player);

        V2BankGuiSession.ClickOutcome outcome = join(future);
        assertTrue(outcome.isRejected(), "a stale delivery must not succeed: " + outcome);
        assertEquals("stale.generation", outcome.reason());
        verify(inventory, never()).addItem(any());
        verify(api).deposit(playerId, "dollar", amount);
    }

    @Test
    void alreadyStaleClickNeverReachesTheLedger() {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = sessionWithFlow(gui);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());

        V2BankGuiSession.ClickOutcome outcome =
                join(session.handleClickAsync(playerId, open.session().generation() + 1, 0));

        assertTrue(outcome.isRejected());
        verify(api, never()).withdraw(any(), any(), any());
        assertTrue(banknotes.minted().isEmpty());
    }
}
