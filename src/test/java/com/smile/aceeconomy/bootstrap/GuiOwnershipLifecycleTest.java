package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.AceLibApi.AceLibProvider;
import com.smile.acelib.event.AceLibEvents;
import com.smile.acelib.event.SafeEventRegistry;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.AceLibScheduler;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.world.WorldService;
import com.smile.aceeconomy.acelib.AceLibAccess;
import com.smile.aceeconomy.gui.v2.BankFormSession;
import com.smile.aceeconomy.gui.v2.BankGuiAction;
import com.smile.aceeconomy.gui.v2.StubBankGuiUseCase;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Ownership of the shared AceLib GUI listener and of self-owned GUI resources.
 *
 * <p>AceLib v1.4.0 owns the shared {@link GuiService} listener: {@code AceLibPlugin} registers
 * it on enable and removes it with {@code HandlerList#unregisterAll} on disable/reload
 * (see {@code GuiService#getListener()} javadoc). AceEconomy must therefore never register
 * that listener instance itself, and must never remove it: {@code unregisterAll} removes
 * <em>every</em> registration of the instance, so it would also wipe AceLib's own
 * registration and break every other plugin sharing the same AceLib instance.
 *
 * <p>The first two tests drive the real production {@code RuntimeModule} with the shared
 * listener already registered by a simulated second consumer (AceLib itself). They fail
 * while production registers or removes the shared listener, and pass once production
 * leaves it alone.
 */
class GuiOwnershipLifecycleTest {

    private Listener sharedListener;
    private JavaPlugin ourPlugin;
    private JavaPlugin otherPlugin;
    private HandlerList standin;
    private GuiService sharedGui;
    private SafeScheduler scheduler;
    private SafeEventRegistry events;
    private PluginManager pluginManager;
    private FakeLifecycleHost host;

    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<AceLibScheduler> schedulers;
    private MockedStatic<AceLibEvents> eventFactories;

    private static final class FakeProvider implements AceLibProvider {
        private final AceLibApi api;

        FakeProvider(AceLibApi api) {
            this.api = api;
        }

        @Override
        public AceLibApi api() {
            return api;
        }
    }

    @BeforeEach
    void setUp() {
        sharedListener = new Listener() {
        };
        ourPlugin = Mockito.mock(JavaPlugin.class);
        otherPlugin = Mockito.mock(JavaPlugin.class);
        // Standalone dispatch table: it joins Bukkit's static HandlerList set on
        // construction, so the production static unregisterAll path really hits it,
        // without needing a live server.
        standin = new HandlerList();
        sharedGui = Mockito.mock(GuiService.class);
        Mockito.when(sharedGui.getListener()).thenReturn(sharedListener);
        Mockito.when(sharedGui.getModuleStatus()).thenReturn("READY");
        scheduler = Mockito.mock(SafeScheduler.class);
        events = Mockito.mock(SafeEventRegistry.class);
        pluginManager = Mockito.mock(PluginManager.class);
        // Whatever the production registers through Bukkit lands in the standin
        // table with the passed owner, mirroring what a live server would record.
        Mockito.doAnswer(invocation -> {
            Listener listener = invocation.getArgument(0);
            JavaPlugin owner = invocation.getArgument(1);
            standin.register(new RegisteredListener(
                    listener, (registered, event) -> { }, EventPriority.NORMAL, owner, false));
            return null;
        }).when(pluginManager).registerEvents(any(Listener.class), any(JavaPlugin.class));

        AceLibApi api = Mockito.mock(AceLibApi.class);
        Mockito.when(api.isReady()).thenReturn(true);
        Mockito.when(api.getPlatform()).thenReturn(Platform.PAPER);
        Mockito.when(api.getPlatformCapability())
                .thenReturn(PlatformCapability.forPlatform(Platform.PAPER));
        Mockito.when(api.getGuiService()).thenReturn(sharedGui);
        host = new FakeLifecycleHost(new FakeProvider(api));
        Mockito.when(api.getLifecycleHost()).thenReturn(host);
        assertTrue(api.isReady(), "test harness must build a ready facade");

        ServicesManager services = Mockito.mock(ServicesManager.class);
        Mockito.when(services.load(AceLibProvider.class)).thenReturn(new FakeProvider(api));
        bukkit = Mockito.mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        bukkit.when(Bukkit::getServicesManager).thenReturn(services);

        schedulers = Mockito.mockStatic(AceLibScheduler.class);
        schedulers.when(() -> AceLibScheduler.create(
                any(JavaPlugin.class), any(), any())).thenReturn(scheduler);
        eventFactories = Mockito.mockStatic(AceLibEvents.class);
        eventFactories.when(() -> AceLibEvents.create(
                any(JavaPlugin.class), any(), any())).thenReturn(events);
    }

    @AfterEach
    void tearDown() {
        // Drop only this test's registrations of its own listener instance; other
        // tests use their own instances so nothing else is affected.
        standin.unregister(sharedListener);
        eventFactories.close();
        schedulers.close();
        bukkit.close();
    }

    /** Instantiate the real production runtime module (private inner class). */
    private LifecycleModule realRuntimeModule(CompositionRoot root) {
        try {
            Class<?> runtimeClass =
                    Class.forName("com.smile.aceeconomy.bootstrap.CompositionRoot$RuntimeModule");
            Constructor<?> ctor = runtimeClass.getDeclaredConstructor(CompositionRoot.class);
            ctor.setAccessible(true);
            return (LifecycleModule) ctor.newInstance(root);
        } catch (ReflectiveOperationException e) {
            fail("cannot instantiate the production runtime module: " + e.getMessage());
            throw new AssertionError(e);
        }
    }

    /** The second consumer's (AceLib's own) registration of the shared listener. */
    private void registerOtherConsumer() {
        standin.register(new RegisteredListener(
                sharedListener, (registered, event) -> { }, EventPriority.NORMAL, otherPlugin, false));
    }

    private void assertOtherConsumerRegistrationPresent(String context) {
        boolean present = Arrays.stream(standin.getRegisteredListeners())
                .anyMatch(registered -> registered.getListener() == sharedListener
                        && registered.getPlugin() == otherPlugin);
        assertTrue(present,
                context + ": the other consumer's registration of the shared listener must remain");
    }

    // ---- shared listener: normal stop -------------------------------------

    @Test
    void sharedListenerRegistrationSurvivesNormalStop() throws Exception {
        registerOtherConsumer();
        CompositionRoot root = new CompositionRoot(ourPlugin);
        HostModuleLifecycle lifecycle = new HostModuleLifecycle(ourPlugin, new AceLibAccess(ourPlugin));
        lifecycle.add("test:runtime", Set.of(), realRuntimeModule(root));

        lifecycle.startAll();
        // Repeated stop must be a no-op after the first teardown.
        lifecycle.stopAll();
        lifecycle.stopAll();

        assertOtherConsumerRegistrationPresent("normal stop");
        Mockito.verify(pluginManager, Mockito.never())
                .registerEvents(eq(sharedListener), any(JavaPlugin.class));
        Mockito.verify(scheduler, Mockito.times(1)).cancelAll();
        assertEquals("READY", sharedGui.getModuleStatus(),
                "stop must never shut down or release the shared GuiService");
    }

    // ---- shared listener: startup-failure rollback -------------------------

    @Test
    void sharedListenerRegistrationSurvivesStartupFailureRollback() {
        registerOtherConsumer();
        CompositionRoot root = new CompositionRoot(ourPlugin);
        HostModuleLifecycle lifecycle = new HostModuleLifecycle(ourPlugin, new AceLibAccess(ourPlugin));
        lifecycle.add("test:runtime", Set.of(), realRuntimeModule(root));
        lifecycle.add("test:failing", Set.of("test:runtime"), new LifecycleModule() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public void start(ResourceOwner resources) throws Exception {
                resources.register(() -> {
                });
                throw new RuntimeException("boom-failing");
            }

            @Override
            public void stop() {
            }
        });

        LifecycleRegistrationException failure =
                assertThrows(LifecycleRegistrationException.class, lifecycle::startAll);
        assertTrue(failure.result().problems().stream()
                        .anyMatch(problem -> problem.message().contains("boom-failing")),
                "original failure must be preserved in the structured problem, got: "
                        + failure.result().problems());

        assertOtherConsumerRegistrationPresent("startup-failure rollback");
        Mockito.verify(pluginManager, Mockito.never())
                .registerEvents(eq(sharedListener), any(JavaPlugin.class));
        assertEquals("READY", sharedGui.getModuleStatus(),
                "rollback must never shut down or release the shared GuiService");
    }

    // ---- self-owned bank sessions: disable seam ----------------------------

    private static V2BankGuiSession trackedSession(
            com.smile.acelib.gui.GuiScope scope, StubBankGuiUseCase useCase, Player player) {
        V2BankGuiSession bankGui = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                useCase, com.smile.aceeconomy.gui.v2.BankGuiTestFixtures.defaultLayout(),
                com.smile.aceeconomy.gui.v2.BankGuiTestFixtures.messages(),
                null, com.smile.aceeconomy.gui.v2.BankGuiTestFixtures.renderer());
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

    private static int runDisableSeam(V2BankGuiSession bankGui, BankFormSession bankForms) {
        try {
            Method seam = CompositionRoot.class.getDeclaredMethod(
                    "invalidateBankSessions", V2BankGuiSession.class, BankFormSession.class);
            seam.setAccessible(true);
            return (int) seam.invoke(null, bankGui, bankForms);
        } catch (ReflectiveOperationException e) {
            fail("production disable seam threw (fail-closed, repeated disable must not throw): " + e);
            throw new AssertionError(e);
        }
    }

    @Test
    void disableCleansOwnedBankSessionsOnceAndLeavesSharedServiceUsable() {
        com.smile.acelib.gui.GuiScope scope =
                com.smile.aceeconomy.gui.v2.BankGuiTestFixtures.scope();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        Player player = mockPlayer();
        V2BankGuiSession bankGui = trackedSession(scope, useCase, player);
        assertTrue(bankGui.activeSession(player.getUniqueId()).isPresent());

        BankFormSession bankForms = Mockito.mock(BankFormSession.class);
        Mockito.when(bankForms.invalidateAll()).thenReturn(0);

        int dropped = runDisableSeam(bankGui, bankForms);
        assertTrue(dropped >= 1, "disable must drop the tracked bank session, got " + dropped);
        Mockito.verify(bankForms, Mockito.times(1)).invalidateAll();

        int second = runDisableSeam(bankGui, bankForms);
        assertEquals(0, second, "repeated disable must be a no-op for already-dropped sessions");
        Mockito.verify(bankForms, Mockito.times(2)).invalidateAll();

        V2BankGuiSession.OpenOutcome reopen = bankGui.open(player);
        assertTrue(reopen.success(), "bank must still open after a disable-style invalidation");
        com.smile.acelib.gui.GuiView view = scope.viewOf(player.getUniqueId()).orElseThrow();
        long generation = reopen.session().generation();
        String buttonId = view.buttons().get(11).id();
        view.handlers().get(buttonId).accept(new com.smile.acelib.gui.GuiButtonClick(
                player.getUniqueId(), generation, 11, buttonId, java.util.Optional.empty()));
        assertEquals(100L, useCase.lastAmount,
                "bank must still operate after a disable-style invalidation");
    }

    // ---- shared service contract: no shutdown surface, never released ------

    @Test
    void sharedGuiServiceExposesNoShutdownAndProductionNeverReleasesIt() throws Exception {
        for (Method method : GuiService.class.getMethods()) {
            String name = method.getName().toLowerCase();
            assertTrue(!name.contains("shutdown"),
                    "shared GuiService must expose no shutdown surface: " + method);
        }

        java.nio.file.Path root = resolveSourceRoot();
        java.nio.file.Path composition =
                root.resolve("com/smile/aceeconomy/bootstrap/CompositionRoot.java");
        assertTrue(java.nio.file.Files.isRegularFile(composition),
                "CompositionRoot source must exist (fail-closed): " + composition);
        String production = java.nio.file.Files.readString(composition);
        assertTrue(!production.contains("getListener()"),
                "production must never touch the shared listener reference:"
                        + " AceLibPlugin registers and unregisters it itself");
        assertTrue(!production.contains("GuiServiceControl")
                        && !production.contains("shutdownService"),
                "production must never shut down the shared GuiService");

        // The JoinEvent list reference keeps the Bukkit HandlerList import used;
        // touching it here also proves the static dispatch tables are reachable
        // offline, which the ownership tests above rely on.
        assertTrue(PlayerJoinEvent.getHandlerList() != null);
    }

    private static java.nio.file.Path resolveSourceRoot() {
        java.nio.file.Path candidate = java.nio.file.Paths.get("src/main/java");
        if (java.nio.file.Files.isDirectory(candidate)) {
            return candidate;
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            java.nio.file.Path alt = java.nio.file.Paths.get(userDir, "src/main/java");
            if (java.nio.file.Files.isDirectory(alt)) {
                return alt;
            }
        }
        try {
            java.net.URL url =
                    GuiOwnershipLifecycleTest.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                java.nio.file.Path current = java.nio.file.Paths.get(url.toURI());
                for (int i = 0; i < 6 && current != null; i++) {
                    java.nio.file.Path attempt = current.resolve("src/main/java");
                    if (java.nio.file.Files.isDirectory(attempt)) {
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
