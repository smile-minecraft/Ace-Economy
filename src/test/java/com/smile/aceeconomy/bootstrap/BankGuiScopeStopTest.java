package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.command.CommandRegistry;
import com.smile.acelib.gui.GuiScope;
import com.smile.aceeconomy.gui.v2.BankFormSession;
import com.smile.aceeconomy.gui.v2.BankGuiTestFixtures;
import com.smile.aceeconomy.gui.v2.StubBankGuiUseCase;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wiring of the plugin-owned bank GUI scope into the presentation stop path.
 *
 * <p>Every test drives the real production {@code stopPresentation} with
 * reflection-injected collaborators (never a reimplementation): after the
 * stop the scope is closed and its reference dropped, every tracked bank
 * view is dropped, the form entries are invalidated, the shared service
 * stays usable, and a foreign consumer registration is untouched. A
 * repeated stop, a stop without a started presentation, and a scope close
 * that throws are all safe.
 */
class BankGuiScopeStopTest {

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = CompositionRoot.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            fail("cannot inject production field " + name + " (fail-closed): " + failure);
            throw new AssertionError(failure);
        }
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = CompositionRoot.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            fail("cannot read production field " + name + " (fail-closed): " + failure);
            throw new AssertionError(failure);
        }
    }

    private static void runStopPresentation(CompositionRoot root) {
        try {
            Method stop = CompositionRoot.class.getDeclaredMethod("stopPresentation");
            stop.setAccessible(true);
            stop.invoke(root);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            fail("production stop path threw (fail-closed): " + cause);
            throw new AssertionError(failure);
        }
    }

    @Test
    void stopClosesOwnedScopeAndDrainsSessions() {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("BankGuiScopeStopTest"));
        HandlerList standin = new HandlerList();
        Listener foreign = new Listener() {
        };
        JavaPlugin foreignOwner = Mockito.mock(JavaPlugin.class);
        standin.register(new RegisteredListener(
                foreign, (registered, event) -> { }, EventPriority.NORMAL, foreignOwner, false));

        FakeGuiService shared = FakeGuiService.available();
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession bankGui = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), BankGuiTestFixtures.defaultLayout(),
                BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());
        Player player = BankGuiTestFixtures.player();
        assertTrue(bankGui.open(player).success(), "setup open must succeed");

        BankFormSession bankForms = Mockito.mock(BankFormSession.class);
        Mockito.when(bankForms.invalidateAll()).thenReturn(2);

        CompositionRoot root = new CompositionRoot(plugin);
        setField(root, "bankGui", bankGui);
        setField(root, "bankForms", bankForms);
        setField(root, "bankGuiScope", scope);

        try {
            runStopPresentation(root);

            Mockito.verify(scope).close();
            assertTrue(readField(root, "bankGuiScope") == null,
                    "stop must drop the scope reference");
            assertTrue(bankGui.activeSession(player.getUniqueId()).isEmpty(),
                    "stop must drop every tracked bank view");
            Mockito.verify(bankForms, Mockito.times(1)).invalidateAll();
            assertEquals("READY", shared.getModuleStatus(),
                    "stop must never shut down or release the shared service");

            boolean present = Arrays.stream(standin.getRegisteredListeners())
                    .anyMatch(registered -> registered.getListener() == foreign
                            && registered.getPlugin() == foreignOwner);
            assertTrue(present, "a foreign consumer registration must survive the stop");

            runStopPresentation(root);
            Mockito.verify(scope, Mockito.times(1)).close();
            Mockito.verify(bankForms, Mockito.times(2)).invalidateAll();
            assertEquals("READY", shared.getModuleStatus());
        } finally {
            standin.unregister(foreign);
        }
    }

    @Test
    void stopWithThrowingScopeStillDrainsSessions() {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("BankGuiScopeStopTest"));
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession bankGui = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), BankGuiTestFixtures.defaultLayout(),
                BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());
        Player player = BankGuiTestFixtures.player();
        assertTrue(bankGui.open(player).success(), "setup open must succeed");
        Mockito.doThrow(new IllegalStateException("probe-boom")).when(scope).close();

        BankFormSession bankForms = Mockito.mock(BankFormSession.class);
        Mockito.when(bankForms.invalidateAll()).thenReturn(0);
        CommandRegistry commandRegistry = Mockito.mock(CommandRegistry.class);

        CompositionRoot root = new CompositionRoot(plugin);
        setField(root, "bankGui", bankGui);
        setField(root, "bankForms", bankForms);
        setField(root, "bankGuiScope", scope);
        setField(root, "commandRegistry", commandRegistry);

        runStopPresentation(root);

        assertTrue(readField(root, "bankGuiScope") == null,
                "the scope reference must be dropped even when its close throws");
        assertTrue(bankGui.activeSession(player.getUniqueId()).isEmpty(),
                "tracked views must be dropped even when the scope close throws");
        Mockito.verify(bankForms, Mockito.times(1)).invalidateAll();
        Mockito.verify(scope, Mockito.times(1)).close();
        Mockito.verify(commandRegistry, Mockito.times(1)).onPluginDisable();

        runStopPresentation(root);
        Mockito.verify(scope, Mockito.times(1)).close();
        Mockito.verify(bankForms, Mockito.times(2)).invalidateAll();
        Mockito.verify(commandRegistry, Mockito.times(2)).onPluginDisable();
    }

    @Test
    void stopWithoutPresentationIsSafe() {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(Logger.getLogger("BankGuiScopeStopTest"));
        CompositionRoot root = new CompositionRoot(plugin);

        runStopPresentation(root);
        runStopPresentation(root);
    }
}
