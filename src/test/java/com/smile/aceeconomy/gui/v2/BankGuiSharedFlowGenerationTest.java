package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiButtonClick;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiSession;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The shared-flow click path must carry the click liveness into the flow:
 * a reload that reopens the view while the handover is still queued refunds
 * instead of delivering, and a click that arrives already stale never
 * reaches the ledger.
 */
class BankGuiSharedFlowGenerationTest {

    static final class CapturingFolia implements FoliaContextExecutor {
        Player player;
        Integer captureDispatchAt;
        final List<Consumer<Player>> captured = new ArrayList<>();
        final CountDownLatch captureLatch = new CountDownLatch(1);
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
                captureLatch.countDown();
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

    private static BankGuiLayout size36Layout() {
        Map<String, BankGuiLayout.SlotConfig> actions = new LinkedHashMap<>();
        actions.put("deposit", new BankGuiLayout.SlotConfig(
                "deposit", 4, BankGuiLayout.ActionType.DEPOSIT, 0L, null,
                "CHEST", "gui.bank-deposit-name", List.of("gui.bank-deposit-lore")));
        actions.put("withdraw100", new BankGuiLayout.SlotConfig(
                "withdraw100", 11, BankGuiLayout.ActionType.WITHDRAW, 100L, null,
                "PAPER", "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("close", new BankGuiLayout.SlotConfig(
                "close", 13, BankGuiLayout.ActionType.CLOSE, 0L, null,
                "BARRIER", "gui.bank-close-name", List.of()));
        return BankGuiLayout.of(true, "gui.bank-title", 36, actions);
    }

    private V2BankGuiSession sessionWithFlow(GuiScope scope, ConfigLangAdapter messages,
                                             BankGuiLayout layout) {
        BanknoteFlow flow = new BanknoteFlow(api, economy, mock(AtomicRedemptionStore.class),
                banknotes, display, folia, io, watchdogs, Duration.ofMillis(200));
        return new V2BankGuiSession(scope, folia, new StubBankGuiUseCase(), layout, messages,
                flow, BankGuiTestFixtures.renderer());
    }

    private static void clickButton(GuiView view, UUID uuid, long generation, int slot) {
        String buttonId = view.buttons().get(slot).id();
        view.handlers().get(buttonId).accept(
                new GuiButtonClick(uuid, generation, slot, buttonId, Optional.empty()));
    }

    private static void awaitKey(ConfigLangAdapter messages, String key) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try {
                verify(messages, Mockito.timeout(50).times(1)).renderMessage(eq(key), anyMap());
                return;
            } catch (Throwable ignored) {
                // Retry until the async reply arrives.
            }
        }
        verify(messages).renderMessage(eq(key), anyMap());
    }

    @Test
    void reloadBeforeLateDeliveryRefundsInsteadOfDelivering() {
        GuiScope scope = BankGuiTestFixtures.scope();
        ConfigLangAdapter messages = BankGuiTestFixtures.messages();
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        folia.captureDispatchAt = 2; // probe runs; the delivery dispatch is retired
        V2BankGuiSession session = sessionWithFlow(scope, messages, layout);
        // The tracked player is the click-time reader; the scope fixture and
        // the flow share the same player id.
        Player opener = Mockito.mock(Player.class);
        Mockito.when(opener.getUniqueId()).thenReturn(playerId);
        Mockito.when(opener.getInventory()).thenReturn(inventory);
        V2BankGuiSession.OpenOutcome open = session.open(opener);
        assertTrue(open.success(), "setup open must succeed: " + open.errorCode());
        long generation = open.session().generation();
        GuiView view = scope.viewOf(playerId).orElseThrow();

        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));

        clickButton(view, playerId, generation, 11);
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
        // A reload reopens the view with a fresh generation while the
        // handover is still queued.
        assertEquals(1, session.applyLayout(size36Layout()));
        folia.captured.get(0).accept(player);

        awaitKey(messages, "general.transaction-cancelled");
        verify(inventory, never()).addItem(any());
        verify(api).deposit(playerId, "dollar", amount);
    }

    @Test
    void alreadyStaleClickNeverReachesTheLedger() {
        GuiScope scope = BankGuiTestFixtures.scope();
        ConfigLangAdapter messages = BankGuiTestFixtures.messages();
        V2BankGuiSession session = sessionWithFlow(scope, messages,
                BankGuiTestFixtures.defaultLayout());
        Player opener = Mockito.mock(Player.class);
        Mockito.when(opener.getUniqueId()).thenReturn(playerId);
        Mockito.when(opener.getInventory()).thenReturn(inventory);
        V2BankGuiSession.OpenOutcome open = session.open(opener);
        assertTrue(open.success());
        GuiView view = scope.viewOf(playerId).orElseThrow();

        clickButton(view, playerId, open.session().generation() + 1, 11);

        awaitKey(messages, "general.transaction-cancelled");
        verify(api, never()).withdraw(any(), any(), any());
        assertTrue(banknotes.minted().isEmpty());
    }

    @Test
    void deliveryDuringReplaceViewWindowRefundsInsteadOfDelivering() throws Exception {
        GuiScope scope = BankGuiTestFixtures.scope();
        ConfigLangAdapter messages = BankGuiTestFixtures.messages();
        BankGuiLayout layout = BankGuiTestFixtures.defaultLayout();
        folia.captureDispatchAt = 2; // probe runs; the delivery dispatch is captured
        V2BankGuiSession session = sessionWithFlow(scope, messages, layout);

        Player opener = Mockito.mock(Player.class);
        Mockito.when(opener.getUniqueId()).thenReturn(playerId);
        Mockito.when(opener.getInventory()).thenReturn(inventory);
        V2BankGuiSession.OpenOutcome open = session.open(opener);
        assertTrue(open.success(), "setup open must succeed: " + open.errorCode());
        long generation = open.session().generation();
        GuiView view = scope.viewOf(playerId).orElseThrow();

        Amount amount = Amount.of(100L, 2);
        when(api.withdraw(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));
        when(api.deposit(playerId, "dollar", amount)).thenReturn(EconomyResult.success(amount));

        clickButton(view, playerId, generation, 11);
        assertTrue(folia.captureLatch.await(10, TimeUnit.SECONDS), "delivery dispatch must be captured");
        assertEquals(1, folia.captured.size(), "the delivery dispatch must be captured");

        // When replaceView is called, simulate upstream scope having replaced the view,
        // and trigger the delivery dispatch before V2BankGuiSession receives the result.
        Mockito.when(scope.replaceView(Mockito.eq(playerId), Mockito.any(GuiView.class)))
                .thenAnswer(invocation -> {
                    UUID uuid = invocation.getArgument(0);
                    GuiView newView = invocation.getArgument(1);
                    GuiSession newSession = new GuiSession(uuid, generation + 1,
                            "bank-test", newView.title(), newView.size(), Set.of());
                    Mockito.when(scope.viewOf(uuid)).thenReturn(Optional.of(newView));
                    folia.captured.get(0).accept(player);
                    return GuiResult.success(newSession);
                });

        assertEquals(1, session.applyLayout(size36Layout()));

        awaitKey(messages, "general.transaction-cancelled");
        verify(inventory, never()).addItem(any());
        verify(api).deposit(playerId, "dollar", amount);

        // Subsequent click with the newly published generation delivers normally:
        GuiSession newSession = session.activeSession(playerId).orElseThrow();
        assertEquals(generation + 1, newSession.generation());
        GuiView newView = scope.viewOf(playerId).orElseThrow();
        folia.captured.clear();
        folia.captureDispatchAt = null;

        clickButton(newView, playerId, newSession.generation(), 11);
        verify(inventory, Mockito.timeout(10_000).times(1)).addItem(any());
    }
}
