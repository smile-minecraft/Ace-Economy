package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiSession;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.gui.v2.BankGuiTestFixtures;
import com.smile.aceeconomy.gui.v2.StubBankGuiUseCase;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;
import com.smile.aceeconomy.infrastructure.acelib.ReloadRuntime;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Open-vs-reload race: opens serialize against the reload swap inside the
 * session, so a queued open either completes fully before the swap (and is
 * then rebuilt by it) or reads the new layout. The converged view always
 * matches the current layout; a stale view can never leak past the reload
 * and no retry exists.
 */
class BankOpenReloadRaceTest {

    private static BankGuiLayout oldLayout() {
        return BankGuiConfigParser.parse(null, Set.of("dollar", "token"));
    }

    private static BankGuiLayout newLayout() {
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
        actions.put("withdraw500", new BankGuiLayout.SlotConfig(
                "withdraw500", 20, BankGuiLayout.ActionType.WITHDRAW, 500L, null,
                "PAPER", "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("close2", new BankGuiLayout.SlotConfig(
                "close2", 15, BankGuiLayout.ActionType.CLOSE, 0L, null,
                "BARRIER", "gui.bank-close-name", List.of()));
        return BankGuiLayout.of(true, "gui.bank-title", 36, actions);
    }

    private static CurrencyRegistry newRegistry() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "€", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static ReloadRuntime runtimeOf(CompositionRoot root) throws Exception {
        Method m = CompositionRoot.class.getDeclaredMethod("reloadRuntime");
        m.setAccessible(true);
        return (ReloadRuntime) m.invoke(root);
    }

    /** Scope miniature that records the size of every opened and replaced view. */
    private static final class RecordingScope {
        final GuiScope scope = Mockito.mock(GuiScope.class);
        final AtomicLong generations = new AtomicLong(200L);
        final Map<UUID, Integer> openSizes = new ConcurrentHashMap<>();
        final Map<UUID, Integer> replaceSizes = new ConcurrentHashMap<>();

        RecordingScope() {
            Mockito.when(scope.openView(Mockito.any(UUID.class), Mockito.any(GuiView.class)))
                    .thenAnswer(invocation -> {
                        UUID uuid = invocation.getArgument(0);
                        GuiView view = invocation.getArgument(1);
                        openSizes.put(uuid, view.size());
                        return GuiResult.success(new GuiSession(uuid,
                                generations.getAndIncrement(), "bank-test",
                                view.title(), view.size(), Set.of()));
                    });
            Mockito.when(scope.replaceView(Mockito.any(UUID.class), Mockito.any(GuiView.class)))
                    .thenAnswer(invocation -> {
                        UUID uuid = invocation.getArgument(0);
                        GuiView view = invocation.getArgument(1);
                        replaceSizes.put(uuid, view.size());
                        return GuiResult.success(new GuiSession(uuid,
                                generations.getAndIncrement(), "bank-test",
                                view.title(), view.size(), Set.of()));
                    });
            Mockito.when(scope.viewOf(Mockito.any(UUID.class)))
                    .thenAnswer(invocation -> Optional.empty());
        }
    }

    @Test
    void queuedOpenReadingOldLayoutConvergesOnNewLayoutAfterReload() throws Exception {
        EconomyTestHarness harness =
                new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));
        CurrencyRegistry oldReg = harness.currencies();
        CurrencyDisplayHolder holder = new CurrencyDisplayHolder(oldReg);
        RecordingScope recording = new RecordingScope();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        BankGuiLayout oldLayout = oldLayout();
        BankGuiLayout newLayout = newLayout();
        V2BankGuiSession session = new V2BankGuiSession(recording.scope, folia, useCase,
                oldLayout, BankGuiTestFixtures.messages(), null, BankGuiTestFixtures.renderer());

        CompositionRoot root = new CompositionRoot(Mockito.mock(JavaPlugin.class));
        setField(root, "currencies", oldReg);
        setField(root, "displayHolder", holder);
        setField(root, "economy", harness.service());
        setField(root, "bankGui", session);
        setField(root, "currentLayout", oldLayout);
        ReloadRuntime runtime = runtimeOf(root);

        // The queued open reads whatever layout is current, then pauses so the
        // reload lands strictly between the layout read and the view build.
        CountDownLatch layoutRead = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        Supplier<BankGuiLayout> layouts = () -> {
            BankGuiLayout seen;
            try {
                seen = (BankGuiLayout) getField(root, "currentLayout");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (layoutRead.getCount() > 0) {
                layoutRead.countDown();
                try {
                    assertTrue(releaseOpen.await(10, TimeUnit.SECONDS),
                            "reload must run while the queued open is paused");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return seen;
        };

        UUID id = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(id);

        ExecutorService reloader = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> reloadError = new AtomicReference<>();
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            // Direct executor: the open runs on the test thread (where the
            // static Bukkit stub is visible) and pauses after reading the old
            // layout; the reload runs on the worker thread, which never touches
            // Bukkit. The interleaving is the same queued-open race.
            ProductionAdapters.Bank bank =
                    new ProductionAdapters.Bank(session, layouts, Runnable::run);
            Future<?> reloading = reloader.submit(() -> {
                try {
                    assertTrue(layoutRead.await(10, TimeUnit.SECONDS));
                    runtime.applyApproved(
                            new CurrencyReloadPlan.Classification(
                                    CurrencyReloadPlan.Disposition.DISPLAY_ONLY, newRegistry(),
                                    List.of("currency display changed: dollar")),
                            newLayout);
                } catch (Throwable t) {
                    reloadError.compareAndSet(null, t);
                } finally {
                    releaseOpen.countDown();
                }
                return null;
            });

            bank.open(id, "someone");
            reloading.get(10, TimeUnit.SECONDS);
            assertTrue(reloadError.get() == null,
                    "reload must succeed, got " + reloadError.get());

            // Either the open completed first (old 27, then rebuilt to 36 by
            // the reload) or it waited and built 36 directly. Both converge.
            Integer replaced = recording.replaceSizes.get(id);
            Integer opened = recording.openSizes.get(id);
            assertTrue(opened != null, "the queued open must converge instead of vanishing");
            if (replaced != null) {
                assertEquals(36, replaced,
                        "the reload must rebuild the raced open with the new layout");
            } else {
                assertEquals(36, opened,
                        "an open that waited out the swap must build from the new layout");
            }
        } finally {
            reloader.shutdownNow();
        }
    }

    @Test
    void openDuringReloadRebuildWaitsAndUsesNewLayout() throws Exception {
        RecordingScope recording = new RecordingScope();
        CountDownLatch replaceEntered = new CountDownLatch(1);
        CountDownLatch replaceRelease = new CountDownLatch(1);
        Mockito.when(recording.scope.replaceView(Mockito.any(UUID.class),
                        Mockito.any(GuiView.class)))
                .thenAnswer(invocation -> {
                    replaceEntered.countDown();
                    assertTrue(replaceRelease.await(10, TimeUnit.SECONDS),
                            "the racing open must land while the rebuild is parked");
                    UUID uuid = invocation.getArgument(0);
                    GuiView view = invocation.getArgument(1);
                    recording.replaceSizes.put(uuid, view.size());
                    return GuiResult.success(new GuiSession(uuid,
                            recording.generations.getAndIncrement(), "bank-test",
                            view.title(), view.size(), Set.of()));
                });
        BankGuiLayout oldLayout = oldLayout();
        BankGuiLayout newLayout = newLayout();
        V2BankGuiSession session = new V2BankGuiSession(recording.scope,
                new RecordingFoliaContext(), new StubBankGuiUseCase(), oldLayout, BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());

        Player first = Mockito.mock(Player.class);
        UUID firstId = UUID.randomUUID();
        Mockito.when(first.getUniqueId()).thenReturn(firstId);
        assertTrue(session.open(first).success());

        Player late = Mockito.mock(Player.class);
        UUID lateId = UUID.randomUUID();
        Mockito.when(late.getUniqueId()).thenReturn(lateId);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService opener = Executors.newSingleThreadExecutor();
        try {
            // The rebuild runs on a worker and parks inside replaceView; the
            // racing open must then wait on the session lock instead of
            // leaking a stale view past the swap.
            Future<Integer> rebuilding = worker.submit(() -> session.applyLayout(newLayout));
            assertTrue(replaceEntered.await(10, TimeUnit.SECONDS));
            Future<V2BankGuiSession.OpenOutcome> racing =
                    opener.submit(() -> session.open(late));
            Thread.sleep(200);
            assertTrue(!racing.isDone(),
                    "an open racing the rebuild must wait instead of building stale");
            replaceRelease.countDown();

            assertEquals(1, rebuilding.get(10, TimeUnit.SECONDS));
            V2BankGuiSession.OpenOutcome outcome = racing.get(10, TimeUnit.SECONDS);
            assertTrue(outcome.success(), "the racing open must converge: " + outcome.errorCode());
            assertEquals(36, recording.openSizes.get(lateId),
                    "an open racing the rebuild must build from the new layout");
            assertEquals(36, recording.replaceSizes.get(firstId),
                    "the tracked view must be rebuilt with the new layout");
        } finally {
            replaceRelease.countDown();
            worker.shutdownNow();
            opener.shutdownNow();
        }
    }
}
