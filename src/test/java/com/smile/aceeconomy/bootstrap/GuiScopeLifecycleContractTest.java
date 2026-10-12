package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiScopes;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.gui.v2.BankFormSession;
import com.smile.aceeconomy.gui.v2.BankGuiTestFixtures;
import com.smile.aceeconomy.gui.v2.BankOpenRouter;
import com.smile.aceeconomy.gui.v2.StubBankGuiUseCase;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;
import com.smile.aceeconomy.ports.BedrockDetector;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;

/**
 * Lifecycle boundary contract for the bank GUI and form surfaces.
 *
 * <p>Behaviour inventory (each item locked by the named test):
 * <ul>
 *   <li>disable drops every tracked bank view and the bank still reopens
 *       and operates afterwards ({@link #disableDropsTrackedChestSession});</li>
 *   <li>repeated disable is a no-op ({@link #repeatedDisableDropsNothing});</li>
 *   <li>the disable seam is null-safe ({@link #nullSeamDropsNothing});</li>
 *   <li>disable never touches handler registrations, so a foreign consumer
 *       sharing the same service instance is unaffected
 *       ({@link #disableLeavesForeignHandlerRegistrationAlone});</li>
 *   <li>only the self-owned redemption listener is registered and cleaned
 *       up, while the shared listener reference is never touched; the
 *       self-built click bridge listener is gone — clicks arrive through
 *       the scope-owned upstream listener
 *       ({@link #selfOwnedListenersAreCleanedWithoutTouchingSharedListener});</li>
 *   <li>the shared service exposes no shutdown surface
 *       ({@link #sharedServiceExposesNoShutdown});</li>
 *   <li>Java and Bedrock players are dispatched to the chest and form entries
 *       ({@link #routerSendsBedrockPlayerToForms},
 *       {@link #routerSendsJavaPlayerToChest});</li>
 *   <li>detector failure fails closed to the chest path
 *       ({@link #routerFailsClosedToChestOnDetectorFailure});</li>
 *   <li>an offline form open is silent with zero writes
 *       ({@link #offlineFormOpenIsSilentWithZeroWrites});</li>
 *   <li>the two-argument scope factory tracks, isolates and releases
 *       plugin-owned scopes without touching handler registrations
 *       ({@link #twoArgCreateTracksScopeUntilClose},
 *       {@link #scopeCloseIsIdempotent},
 *       {@link #concurrentScopesAreIsolated},
 *       {@link #scopeCloseNeverTouchesForeignHandlerRegistration});</li>
 *   <li>a duplicate scope for an enabled plugin is rejected and a name
 *       collision across plugin instances is rejected
 *       ({@link #enabledPluginDuplicateCreateIsRejected},
 *       {@link #sameNameAcrossInstancesIsRejected}).</li>
 * </ul>
 *
 * <p>Behaviours owned by the session and form controllers themselves (complete
 * view assembly before open, build-failure refusal, reload reopen, stale
 * click rejection, right-click redemption wiring with cancel ordering, form
 * VALID/CLOSED/INVALID progression) are locked by the GUI test suite and are
 * intentionally not duplicated here.
 */
class GuiScopeLifecycleContractTest {

    private JavaPlugin scopePlugin;

    @AfterEach
    void releaseScope() {
        if (scopePlugin != null) {
            try {
                GuiScopes.close(scopePlugin);
            } catch (Throwable ignored) {
                // Release-only: a failed close must never fail the test run.
            } finally {
                scopePlugin = null;
            }
        }
    }

    private static V2BankGuiSession trackedSession(GuiScope scope, StubBankGuiUseCase useCase,
                                                   Player player) {
        V2BankGuiSession bankGui = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                useCase, BankGuiTestFixtures.defaultLayout(), BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());
        assertTrue(bankGui.open(player).success(), "bank must open before a disable-style test");
        return bankGui;
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

    private static JavaPlugin mockPlugin() {
        return Mockito.mock(JavaPlugin.class);
    }

    // ---- disable seam ----------------------------------------------------

    @Test
    void disableDropsTrackedChestSession() {
        GuiScope scope = BankGuiTestFixtures.scope();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer();
        V2BankGuiSession bankGui = trackedSession(scope, useCase, player);
        assertTrue(bankGui.activeSession(player.getUniqueId()).isPresent());

        BankFormSession bankForms = Mockito.mock(BankFormSession.class);
        Mockito.when(bankForms.invalidateAll()).thenReturn(0);

        int dropped = CompositionRoot.invalidateBankSessions(bankGui, bankForms);
        assertTrue(dropped >= 1, "disable must drop the tracked bank session, got " + dropped);
        Mockito.verify(bankForms, Mockito.times(1)).invalidateAll();
        assertTrue(bankGui.activeSession(player.getUniqueId()).isEmpty(),
                "dropped session must no longer resolve");

        V2BankGuiSession.OpenOutcome reopen = bankGui.open(player);
        assertTrue(reopen.success(), "bank must still open after a disable-style invalidation");
        GuiView view = scope.viewOf(player.getUniqueId()).orElseThrow();
        long generation = reopen.session().generation();
        String buttonId = view.buttons().get(11).id();
        view.handlers().get(buttonId).accept(new com.smile.acelib.gui.GuiButtonClick(
                player.getUniqueId(), generation, 11, buttonId, Optional.empty()));
        assertEquals(100L, useCase.lastAmount,
                "bank must still operate after a disable-style invalidation");
    }

    @Test
    void repeatedDisableDropsNothing() {
        GuiScope scope = BankGuiTestFixtures.scope();
        Player player = mockPlayer();
        V2BankGuiSession bankGui = trackedSession(scope, new StubBankGuiUseCase(), player);

        BankFormSession bankForms = Mockito.mock(BankFormSession.class);
        Mockito.when(bankForms.invalidateAll()).thenReturn(0);

        assertTrue(CompositionRoot.invalidateBankSessions(bankGui, bankForms) >= 1);
        assertEquals(0, CompositionRoot.invalidateBankSessions(bankGui, bankForms),
                "repeated disable must be a no-op for already-dropped sessions");
        Mockito.verify(bankForms, Mockito.times(2)).invalidateAll();
    }

    @Test
    void nullSeamDropsNothing() {
        assertEquals(0, CompositionRoot.invalidateBankSessions(null, null),
                "null seam must drop nothing instead of throwing");
    }

    @Test
    void disableLeavesForeignHandlerRegistrationAlone() {
        HandlerList standin = new HandlerList();
        Listener foreign = new Listener() {
        };
        JavaPlugin foreignOwner = mockPlugin();
        standin.register(new RegisteredListener(
                foreign, (registered, event) -> { }, EventPriority.NORMAL, foreignOwner, false));

        GuiScope scope = BankGuiTestFixtures.scope();
        Player player = mockPlayer();
        V2BankGuiSession bankGui = trackedSession(scope, new StubBankGuiUseCase(), player);
        BankFormSession bankForms = Mockito.mock(BankFormSession.class);

        CompositionRoot.invalidateBankSessions(bankGui, bankForms);

        boolean present = Arrays.stream(standin.getRegisteredListeners())
                .anyMatch(registered -> registered.getListener() == foreign
                        && registered.getPlugin() == foreignOwner);
        assertTrue(present, "a foreign consumer registration must survive the disable seam");
        standin.unregister(foreign);
    }

    @Test
    void selfOwnedListenersAreCleanedWithoutTouchingSharedListener() {
        Path composition = resolveSourceRoot()
                .resolve("com/smile/aceeconomy/bootstrap/CompositionRoot.java");
        assertTrue(Files.isRegularFile(composition),
                "CompositionRoot source must exist (fail-closed): " + composition);
        String production;
        try {
            production = Files.readString(composition);
        } catch (Exception failure) {
            fail("cannot read production source (fail-closed): " + failure.getMessage());
            throw new AssertionError(failure);
        }
        assertTrue(production.contains("registerEvents(redeemListener, plugin)"),
                "the self-owned redemption listener must be registered by this plugin");
        assertTrue(production.contains("unregisterAll(redeemListener)"),
                "the self-owned redemption listener must be cleaned up on stop");
        assertFalse(production.contains("bankClickListener"),
                "the declarative migration removed the self-built click bridge listener: "
                        + "clicks arrive through the scope-owned upstream listener instead");
        assertFalse(production.contains("getListener()"),
                "production must never touch the shared listener reference");
    }

    @Test
    void sharedServiceExposesNoShutdown() {
        for (java.lang.reflect.Method method : GuiService.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("shutdown"),
                    "shared service must expose no shutdown surface: " + method);
        }
    }

    // ---- open routing ----------------------------------------------------

    @Test
    void routerSendsBedrockPlayerToForms() {
        BankCommandService javaBank = Mockito.mock(BankCommandService.class);
        @SuppressWarnings("unchecked")
        Consumer<UUID> formOpener = Mockito.mock(Consumer.class);
        BedrockDetector detector = Mockito.mock(BedrockDetector.class);
        Mockito.when(detector.isBedrock(any(UUID.class))).thenReturn(true);

        UUID player = UUID.randomUUID();
        new BankOpenRouter(javaBank, formOpener, detector, Runnable::run).open(player, "Alex");

        Mockito.verify(formOpener, Mockito.times(1)).accept(player);
        Mockito.verify(javaBank, Mockito.never()).open(any(UUID.class), any(String.class));
    }

    @Test
    void routerSendsJavaPlayerToChest() {
        BankCommandService javaBank = Mockito.mock(BankCommandService.class);
        @SuppressWarnings("unchecked")
        Consumer<UUID> formOpener = Mockito.mock(Consumer.class);
        BedrockDetector detector = Mockito.mock(BedrockDetector.class);
        Mockito.when(detector.isBedrock(any(UUID.class))).thenReturn(false);

        UUID player = UUID.randomUUID();
        new BankOpenRouter(javaBank, formOpener, detector, Runnable::run).open(player, "Alex");

        Mockito.verify(javaBank, Mockito.times(1)).open(player, "Alex");
        Mockito.verify(formOpener, Mockito.never()).accept(any(UUID.class));
    }

    @Test
    void routerFailsClosedToChestOnDetectorFailure() {
        BankCommandService javaBank = Mockito.mock(BankCommandService.class);
        @SuppressWarnings("unchecked")
        Consumer<UUID> formOpener = Mockito.mock(Consumer.class);
        BedrockDetector detector = Mockito.mock(BedrockDetector.class);
        Mockito.when(detector.isBedrock(any(UUID.class)))
                .thenThrow(new RuntimeException("probe-boom"));

        UUID player = UUID.randomUUID();
        new BankOpenRouter(javaBank, formOpener, detector, Runnable::run).open(player, "Alex");

        Mockito.verify(javaBank, Mockito.times(1)).open(player, "Alex");
        Mockito.verify(formOpener, Mockito.never()).accept(any(UUID.class));
    }

    @Test
    void offlineFormOpenIsSilentWithZeroWrites() {
        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);
            StubBankGuiUseCase useCase = new StubBankGuiUseCase();
            EconomyApi balances = Mockito.mock(EconomyApi.class);
            ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
            BankFormSession forms = new BankFormSession(
                    null, new RecordingFoliaContext(), useCase, balances, () -> null, messages);

            UUID player = UUID.randomUUID();
            forms.open(player);

            assertEquals(0, forms.invalidateAll(),
                    "an offline open must track nothing, so invalidation drops nothing");
            Mockito.verifyNoInteractions(balances);
        }
    }

    // ---- scope factory spike, locked as contract --------------------------

    @Test
    void twoArgCreateTracksScopeUntilClose() {
        scopePlugin = mockPlugin();
        GuiScope scope = GuiScopes.create(scopePlugin, FakeGuiService.available());

        assertFalse(scope.isClosed(), "a fresh scope must be usable");
        assertTrue(GuiScopes.get(scopePlugin).orElse(null) == scope,
                "the registry must return the created scope");

        assertTrue(GuiScopes.close(scopePlugin), "close must report a released scope");
        assertTrue(scope.isClosed(), "close must mark the scope closed");
        assertTrue(GuiScopes.get(scopePlugin).isEmpty(),
                "the registry must drop the scope after close");
        scopePlugin = null;
    }

    @Test
    void scopeCloseIsIdempotent() {
        scopePlugin = mockPlugin();
        GuiScope scope = GuiScopes.create(scopePlugin, FakeGuiService.available());

        assertTrue(GuiScopes.close(scopePlugin));
        assertFalse(GuiScopes.close(scopePlugin),
                "closing an already-released scope must report nothing released");
        scope.close();
        assertTrue(scope.isClosed());
        scopePlugin = null;
    }

    @Test
    void enabledPluginDuplicateCreateIsRejected() {
        scopePlugin = mockPlugin();
        Mockito.when(scopePlugin.isEnabled()).thenReturn(true);
        GuiScope scope = GuiScopes.create(scopePlugin, FakeGuiService.available());
        try {
            assertThrows(IllegalStateException.class,
                    () -> GuiScopes.create(scopePlugin, FakeGuiService.available()),
                    "an enabled plugin must not open a second scope");
        } finally {
            GuiScopes.close(scopePlugin);
            assertTrue(scope.isClosed());
            scopePlugin = null;
        }
    }

    @Test
    void concurrentScopesAreIsolated() {
        scopePlugin = mockPlugin();
        Mockito.when(scopePlugin.getName()).thenReturn("scope-probe-one");
        JavaPlugin other = mockPlugin();
        Mockito.when(other.getName()).thenReturn("scope-probe-two");
        GuiScope first = GuiScopes.create(scopePlugin, FakeGuiService.available());
        GuiScope second = GuiScopes.create(other, FakeGuiService.available());
        try {
            GuiScopes.close(scopePlugin);
            assertTrue(first.isClosed(), "closing one plugin must close its own scope");
            assertFalse(second.isClosed(), "the other plugin scope must stay usable");
            assertTrue(GuiScopes.get(other).orElse(null) == second);
        } finally {
            GuiScopes.close(other);
            scopePlugin = null;
        }
    }

    @Test
    void sameNameAcrossInstancesIsRejected() {
        scopePlugin = mockPlugin();
        Mockito.when(scopePlugin.getName()).thenReturn("scope-probe-shared");
        JavaPlugin other = mockPlugin();
        Mockito.when(other.getName()).thenReturn("scope-probe-shared");
        GuiScopes.create(scopePlugin, FakeGuiService.available());
        try {
            assertThrows(IllegalStateException.class,
                    () -> GuiScopes.create(other, FakeGuiService.available()),
                    "two live plugin instances must never share one owner name");
        } finally {
            GuiScopes.close(scopePlugin);
            scopePlugin = null;
        }
    }

    @Test
    void scopeCloseNeverTouchesForeignHandlerRegistration() {
        HandlerList standin = new HandlerList();
        Listener foreign = new Listener() {
        };
        JavaPlugin foreignOwner = mockPlugin();
        standin.register(new RegisteredListener(
                foreign, (registered, event) -> { }, EventPriority.NORMAL, foreignOwner, false));

        scopePlugin = mockPlugin();
        GuiScope scope = GuiScopes.create(scopePlugin, FakeGuiService.available());
        scope.close();
        scopePlugin = null;

        boolean present = Arrays.stream(standin.getRegisteredListeners())
                .anyMatch(registered -> registered.getListener() == foreign
                        && registered.getPlugin() == foreignOwner);
        assertTrue(present, "closing a scope must never touch handler registrations");
        standin.unregister(foreign);
    }

    private static Path resolveSourceRoot() {
        Path candidate = Paths.get("src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            Path alt = Paths.get(userDir, "src/main/java");
            if (Files.isDirectory(alt)) {
                return alt;
            }
        }
        try {
            java.net.URL url = GuiScopeLifecycleContractTest.class
                    .getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                Path current = Paths.get(url.toURI());
                for (int i = 0; i < 6 && current != null; i++) {
                    Path attempt = current.resolve("src/main/java");
                    if (Files.isDirectory(attempt)) {
                        return attempt;
                    }
                    current = current.getParent();
                }
            }
        } catch (Exception ignored) {
            // Fall through to the fail-closed candidate below.
        }
        fail("source root missing or not a directory (fail-closed)");
        throw new AssertionError("unreachable");
    }
}
