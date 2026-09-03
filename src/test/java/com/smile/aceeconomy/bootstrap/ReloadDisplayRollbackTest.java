package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.gui.v2.BankGuiAction;
import com.smile.aceeconomy.gui.v2.BankGuiActions;
import com.smile.aceeconomy.gui.v2.StubBankGuiUseCase;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;
import com.smile.aceeconomy.infrastructure.acelib.ReloadRuntime;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Post-publish rollback: when a later apply step throws after the shared holder
 * was already published, the holder, the economy copy and the live reference must
 * all return to the pre-publish snapshot and the failure must propagate so the
 * outer reload reports failure instead of running mixed versions.
 */
class ReloadDisplayRollbackTest {

    private static CurrencyRegistry oldRegistry() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 2, true),
                Currency.define("token", "活動代幣", "ⓒ", 0, false)));
    }

    private static CurrencyRegistry newRegistry() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "€", 2, true),
                Currency.define("token", "活動代幣", "ⓒ", 0, false)));
    }

    private static CurrencyReloadPlan.Classification displayOnlyPlan(CurrencyRegistry candidate) {
        return new CurrencyReloadPlan.Classification(
                CurrencyReloadPlan.Disposition.DISPLAY_ONLY, candidate,
                List.of("currency display changed: dollar"));
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

    private static void trySetField(Object target, String name, Object value) {
        try {
            setField(target, name, value);
        } catch (NoSuchFieldException e) {
            // Field does not exist yet (pre-fix shape); nothing to seed.
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ReloadRuntime runtimeOf(CompositionRoot root) throws Exception {
        Method m = CompositionRoot.class.getDeclaredMethod("reloadRuntime");
        m.setAccessible(true);
        return (ReloadRuntime) m.invoke(root);
    }

    private static CompositionRoot newRoot() {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        return new CompositionRoot(plugin);
    }

    @Test
    void economyFailureAfterPublishRollsBackHolder() throws Exception {
        CompositionRoot root = newRoot();
        CurrencyRegistry oldReg = oldRegistry();
        CurrencyDisplayHolder holder = new CurrencyDisplayHolder(oldReg);
        EconomyService failingEconomy = Mockito.mock(EconomyService.class);
        Mockito.doThrow(new RuntimeException("economy down"))
                .when(failingEconomy)
                .replaceCurrencyDisplay(Mockito.any());

        setField(root, "currencies", oldReg);
        setField(root, "displayHolder", holder);
        setField(root, "economy", failingEconomy);
        setField(root, "bankGui", null);
        trySetField(root, "currentLayout",
                BankGuiConfigParser.parse(null, Set.of("dollar", "token")));

        ReloadRuntime runtime = runtimeOf(root);
        assertThrows(RuntimeException.class,
                () -> runtime.applyApproved(displayOnlyPlan(newRegistry()), null));

        assertSame(oldReg, holder.get(),
                "holder must return to the pre-publish registry when economy apply fails");
        assertSame(oldReg, getField(root, "currencies"),
                "live reference must return to the pre-publish registry");
    }

    @Test
    void layoutFailureAfterPublishRollsBackHolderAndEconomy() throws Exception {
        CompositionRoot root = newRoot();
        CurrencyRegistry oldReg = oldRegistry();
        CurrencyDisplayHolder holder = new CurrencyDisplayHolder(oldReg);
        EconomyService economy = Mockito.mock(EconomyService.class);
        V2BankGuiSession failingGui = Mockito.mock(V2BankGuiSession.class);
        Mockito.doThrow(new RuntimeException("gui down"))
                .when(failingGui)
                .replaceLayout(Mockito.any());
        BankGuiLayout oldLayout =
                BankGuiConfigParser.parse(null, Set.of("dollar", "token"));
        BankGuiLayout newLayout =
                BankGuiConfigParser.parse(null, Set.of("dollar", "token"));

        setField(root, "currencies", oldReg);
        setField(root, "displayHolder", holder);
        setField(root, "economy", economy);
        setField(root, "bankGui", failingGui);
        trySetField(root, "currentLayout", oldLayout);

        ReloadRuntime runtime = runtimeOf(root);
        assertThrows(RuntimeException.class,
                () -> runtime.applyApproved(displayOnlyPlan(newRegistry()), newLayout));

        assertSame(oldReg, holder.get(),
                "holder must return to the pre-publish registry when layout apply fails");
        assertSame(oldReg, getField(root, "currencies"),
                "live reference must return to the pre-publish registry");
        Mockito.verify(economy).replaceCurrencyDisplay(oldReg);
        try {
            assertSame(oldLayout, getField(root, "currentLayout"),
                    "layout reference must stay on the pre-publish version");
        } catch (NoSuchFieldException e) {
            // Pre-fix shape without a layout snapshot; holder/economy rollback is the Red signal.
        }
    }

    /**
     * Post-swap rollback with a real session: the layout resolver is exchanged
     * successfully and only the later {@code invalidateAll} throws. The holder,
     * the real economy copy and the layout reference must all return to the
     * pre-publish snapshots, and the original failure must propagate so the
     * outer reload reports failure instead of running mixed versions.
     *
     * <p>The session is real (only {@code invalidateAll} is forced to throw on a
     * spy), and the old/new layouts are distinguishable (27 slots with
     * withdraw-500 on slot 13 versus 36 slots with close on slot 13 and
     * withdraw-500 moved to slot 20). The two captured resolvers must behave
     * like the new layout first and the old layout second, and a click after
     * the rollback must follow the old rules end to end.
     */
    @Test
    void invalidateAllFailureAfterLayoutSwapRollsBackAllWithRealEconomy() throws Exception {
        CompositionRoot root = newRoot();
        EconomyTestHarness harness =
                new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));
        CurrencyRegistry oldReg = harness.currencies();
        CurrencyRegistry newReg = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "€", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
        CurrencyDisplayHolder holder = new CurrencyDisplayHolder(oldReg);
        EconomyService realEconomy = harness.service();
        FakeGuiService guiService = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        BankGuiLayout oldLayout =
                BankGuiConfigParser.parse(null, Set.of("dollar", "token"));
        BankGuiLayout newLayout = new36Layout();
        V2BankGuiSession gui = Mockito.spy(new V2BankGuiSession(
                guiService, folia, useCase, BankGuiActions.resolver(oldLayout)));
        Mockito.doThrow(new RuntimeException("invalidate down"))
                .when(gui)
                .invalidateAll();

        setField(root, "currencies", oldReg);
        setField(root, "displayHolder", holder);
        setField(root, "economy", realEconomy);
        setField(root, "bankGui", gui);
        setField(root, "currentLayout", oldLayout);

        ReloadRuntime runtime = runtimeOf(root);
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runtime.applyApproved(displayOnlyPlan(newReg), newLayout));
        assertEquals("invalidate down", thrown.getMessage(),
                "the post-swap failure must propagate instead of being masked");

        assertSame(oldReg, holder.get(),
                "holder must return to the pre-publish registry when invalidateAll fails");
        assertSame(oldReg, getField(root, "currencies"),
                "live reference must return to the pre-publish registry");
        Field economyCurrencies = EconomyService.class.getDeclaredField("currencies");
        economyCurrencies.setAccessible(true);
        assertSame(oldReg, economyCurrencies.get(realEconomy),
                "real economy state must return to the pre-publish registry");
        assertSame(oldLayout, getField(root, "currentLayout"),
                "layout reference must return to the pre-publish version");
        assertEquals(2L, gui.layoutGeneration(),
                "both resolver swaps (new, then restore) must land on the real session");

        ArgumentCaptor<Function<Integer, BankGuiAction>> swaps =
                ArgumentCaptor.forClass(Function.class);
        Mockito.verify(gui, Mockito.times(2)).replaceLayout(swaps.capture());
        BankGuiAction firstOn13 = swaps.getAllValues().get(0).apply(13);
        BankGuiAction secondOn13 = swaps.getAllValues().get(1).apply(13);
        assertEquals(BankGuiAction.Type.CLOSE, firstOn13.type(),
                "the first swap must install the new layout (slot 13 closes)");
        assertEquals(BankGuiAction.Type.WITHDRAW, secondOn13.type(),
                "the rollback swap must restore the old layout (slot 13 withdraws)");
        assertEquals(500L, secondOn13.amount());
        assertEquals(BankGuiAction.Type.WITHDRAW,
                swaps.getAllValues().get(0).apply(20).type(),
                "the first swap must install the new layout (slot 20 withdraws)");
        assertEquals(BankGuiAction.Type.NONE,
                swaps.getAllValues().get(1).apply(20).type(),
                "the rollback swap must restore the old layout (slot 20 empty)");

        Player player = mockPlayer();
        V2BankGuiSession.OpenOutcome opened =
                gui.open(player, "Bank", oldLayout.size(), oldLayout.protectedSlots());
        assertTrue(opened.success(), "a post-rollback open must succeed");
        V2BankGuiSession.ClickOutcome click = gui.handleClick(
                player.getUniqueId(), opened.session().generation(), 13);
        assertTrue(click.isSuccess(),
                "slot 13 must withdraw under the restored old layout, got " + click.reason());
        assertEquals(500L, useCase.lastAmount);
    }

    private static BankGuiLayout new36Layout() {
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

    private static Player mockPlayer() {
        Player player = Mockito.mock(Player.class);
        PlayerInventory inventory = Mockito.mock(PlayerInventory.class);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(player.getInventory()).thenReturn(inventory);
        Mockito.when(inventory.firstEmpty()).thenReturn(0);
        Mockito.when(inventory.addItem(Mockito.any(ItemStack.class)))
                .thenReturn(new HashMap<>());
        return player;
    }

    /**
     * Rollback interleave: a session built from the failed candidate after the
     * layout swap but before the failing {@code invalidateAll} finishes must
     * not survive the rollback. Otherwise a new-size session keeps acting
     * under the restored old resolver. Sessions proven to predate the swap
     * stay untouched.
     */
    @Test
    void rollbackDropsSessionOpenedAfterSwapWhenInvalidateFails() throws Exception {
        CompositionRoot root = newRoot();
        EconomyTestHarness harness =
                new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));
        CurrencyRegistry oldReg = harness.currencies();
        CurrencyRegistry newReg = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "€", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
        CurrencyDisplayHolder holder = new CurrencyDisplayHolder(oldReg);
        EconomyService realEconomy = harness.service();
        FakeGuiService guiService = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        BankGuiLayout oldLayout =
                BankGuiConfigParser.parse(null, Set.of("dollar", "token"));
        BankGuiLayout newLayout = new36Layout();
        V2BankGuiSession gui = Mockito.spy(new V2BankGuiSession(
                guiService, folia, useCase, BankGuiActions.resolver(oldLayout)));
        CountDownLatch invalidateEntered = new CountDownLatch(1);
        CountDownLatch invalidateRelease = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            invalidateEntered.countDown();
            assertTrue(invalidateRelease.await(10, TimeUnit.SECONDS),
                    "the post-swap open must land while invalidateAll is parked");
            throw new RuntimeException("invalidate down");
        }).when(gui).invalidateAll();

        setField(root, "currencies", oldReg);
        setField(root, "displayHolder", holder);
        setField(root, "economy", realEconomy);
        setField(root, "bankGui", gui);
        setField(root, "currentLayout", oldLayout);

        // A pre-reload session proven to predate the swap.
        Player oldPlayer = mockPlayer();
        V2BankGuiSession.OpenOutcome oldOpen = gui.open(
                oldPlayer, "Bank", oldLayout.size(), oldLayout.protectedSlots());
        assertTrue(oldOpen.success());

        ReloadRuntime runtime = runtimeOf(root);
        ExecutorService reloader = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> reloadError = new AtomicReference<>();
        try {
            Future<?> reloading = reloader.submit(() -> {
                try {
                    runtime.applyApproved(displayOnlyPlan(newReg), newLayout);
                } catch (Throwable t) {
                    reloadError.compareAndSet(null, t);
                }
                return null;
            });
            assertTrue(invalidateEntered.await(10, TimeUnit.SECONDS),
                    "reload must reach invalidateAll");

            // The interleaved open binds the swapped (failed) generation and
            // completes after the invalidation snapshot but before its failure.
            Player newPlayer = mockPlayer();
            V2BankGuiSession.OpenOutcome leaked = gui.open(
                    newPlayer, "Bank", newLayout.size(), newLayout.protectedSlots(),
                    gui.layoutGeneration());
            assertTrue(leaked.success(), "the interleaved open binds the swapped layout");
            assertEquals(36, leaked.session().size());

            invalidateRelease.countDown();
            reloading.get(10, TimeUnit.SECONDS);
            assertTrue(reloadError.get() instanceof RuntimeException
                            && "invalidate down".equals(reloadError.get().getMessage()),
                    "the post-swap failure must propagate, got " + reloadError.get());

            // The rollback restores every reference and drops the leaked
            // new-size session, while the pre-swap session keeps working under
            // the restored old rules.
            assertSame(oldReg, holder.get());
            assertSame(oldReg, getField(root, "currencies"));
            assertSame(oldLayout, getField(root, "currentLayout"));
            assertFalse(guiService.getActiveSession(newPlayer.getUniqueId()).isSuccess(),
                    "the session built from the failed candidate must be closed by the rollback");
            V2BankGuiSession.ClickOutcome leakedClick = gui.handleClick(
                    newPlayer.getUniqueId(), leaked.session().generation(), 13);
            assertTrue(leakedClick.isRejected(),
                    "the leaked generation must no longer act, got " + leakedClick.reason());
            V2BankGuiSession.ClickOutcome oldClick = gui.handleClick(
                    oldPlayer.getUniqueId(), oldOpen.session().generation(), 13);
            assertTrue(oldClick.isSuccess(),
                    "the pre-swap session must keep working under the restored layout, got "
                            + oldClick.reason());
            assertEquals(500L, useCase.lastAmount);
        } finally {
            reloader.shutdownNow();
        }
    }
}
