package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.gui.v2.BankGuiActions;
import com.smile.aceeconomy.gui.v2.StubBankGuiUseCase;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;
import com.smile.aceeconomy.infrastructure.acelib.ReloadRuntime;

import com.smile.acelib.gui.GuiResult;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Open-vs-reload race: an open that has already read the old layout must not
 * build an old-version session after a reload swaps the layout and drops open
 * sessions. The queued open is either rejected or rebuilt from the new layout;
 * it must never leave an old-size session behind.
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

    @Test
    void queuedOpenReadingOldLayoutMustNotBuildOldSessionAfterReload() throws Exception {
        EconomyTestHarness harness =
                new EconomyTestHarness(DebtPolicy.disabled(), Amount.zero(2));
        CurrencyRegistry oldReg = harness.currencies();
        CurrencyDisplayHolder holder = new CurrencyDisplayHolder(oldReg);
        FakeGuiService guiService = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        BankGuiLayout oldLayout = oldLayout();
        BankGuiLayout newLayout = newLayout();
        V2BankGuiSession session = new V2BankGuiSession(
                guiService, folia, useCase, BankGuiActions.resolver(oldLayout));

        CompositionRoot root = new CompositionRoot(Mockito.mock(JavaPlugin.class));
        setField(root, "currencies", oldReg);
        setField(root, "displayHolder", holder);
        setField(root, "economy", harness.service());
        setField(root, "bankGui", session);
        setField(root, "currentLayout", oldLayout);
        ReloadRuntime runtime = runtimeOf(root);

        // The queued open reads whatever layout is current, then pauses so the
        // reload lands strictly between the layout read and the session build.
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

        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.plainMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn("Bank");
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
                    new ProductionAdapters.Bank(session, layouts, messages, Runnable::run);
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

            GuiResult active = guiService.getActiveSession(id);
            assertTrue(active.isSuccess() && active.session() != null,
                    "the queued open must converge on the new layout instead of vanishing");
            assertEquals(36, active.session().size(),
                    "the queued open read the old 27-slot layout but ran after the reload: "
                            + "it must not build an old-version session");
        } finally {
            reloader.shutdownNow();
        }
    }
}
