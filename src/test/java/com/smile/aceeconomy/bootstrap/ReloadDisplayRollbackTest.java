package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.application.EconomyTestHarness;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.CurrencyReloadPlan;
import com.smile.aceeconomy.infrastructure.acelib.ReloadRuntime;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
     * Post-swap rollback with real state: the layout resolver is exchanged
     * successfully and only the later {@code invalidateAll} throws. The holder,
     * the real economy copy and the layout reference must all return to the
     * pre-publish snapshots, and the original failure must propagate so the
     * outer reload reports failure instead of running mixed versions.
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
        V2BankGuiSession gui = Mockito.mock(V2BankGuiSession.class);
        Mockito.doThrow(new RuntimeException("invalidate down"))
                .when(gui)
                .invalidateAll();
        BankGuiLayout oldLayout =
                BankGuiConfigParser.parse(null, Set.of("dollar", "token"));
        BankGuiLayout newLayout =
                BankGuiConfigParser.parse(null, Set.of("dollar", "token"));

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
        Mockito.verify(gui, Mockito.times(2)).replaceLayout(Mockito.any());
    }
}
