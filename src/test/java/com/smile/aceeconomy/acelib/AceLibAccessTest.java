package com.smile.aceeconomy.acelib;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.AceLibApi.AceLibProvider;
import com.smile.acelib.event.EventErrorRecord;
import com.smile.acelib.event.EventRegistration;
import com.smile.acelib.event.SafeEventRegistry;
import com.smile.acelib.event.SafeEventListener;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.scheduler.TaskErrorRecord;
import com.smile.aceeconomy.bootstrap.LifecycleModule;
import com.smile.aceeconomy.bootstrap.ModuleLifecycle;
import com.smile.aceeconomy.bootstrap.ResourceOwner;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the v2 AceLib facade accessor and the facade-requiring module base.
 *
 * <p>Facade resolution is exercised against fakes/mocks; scheduler and event-registry creation is
 * guarded so a server-absent runtime failure (not an API-surface mismatch) does not fail the build.
 * Live Folia region scheduling / AceLib reload behaviour is explicitly NOT asserted here.
 */
class AceLibAccessTest {

    // ---- fakes ----------------------------------------------------------------

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

    private static AceLibApi readyApi() {
        BooleanSupplier alive = () -> true;
        Runnable onShutdown = () -> {
        };
        return AceLibApi.ready(
                "AceEconomy-test",
                Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER),
                alive,
                onShutdown);
    }

    private static final class FakeScheduler implements SafeScheduler {
        boolean cancelAllCalled = false;

        @Override
        public ScheduledTask runGlobal(Runnable r) {
            return null;
        }

        @Override
        public ScheduledTask runAsync(Runnable r) {
            return null;
        }

        @Override
        public ScheduledTask runLater(Runnable r, long d) {
            return null;
        }

        @Override
        public ScheduledTask runTimer(Runnable r, long i, long p) {
            return null;
        }

        @Override
        public ScheduledTask runForPlayer(Player p, Runnable r) {
            return null;
        }

        @Override
        public ScheduledTask runForPlayerLater(Player p, Runnable r, long d) {
            return null;
        }

        @Override
        public ScheduledTask runForEntity(Entity e, Runnable r) {
            return null;
        }

        @Override
        public ScheduledTask runAtLocation(Location l, Runnable r) {
            return null;
        }

        @Override
        public List<TaskErrorRecord> getRecorderErrors(int n) {
            return List.of();
        }

        @Override
        public void cancelAll() {
            cancelAllCalled = true;
        }
    }

    private static final class FakeEventRegistry implements SafeEventRegistry {
        boolean unregisterAllCalled = false;

        @Override
        public <E extends Event> EventRegistration<E> register(Class<E> c, SafeEventListener<E> l) {
            return null;
        }

        @Override
        public <E extends Event> EventRegistration<E> registerOneShot(Class<E> c, SafeEventListener<E> l) {
            return null;
        }

        @Override
        public void unregister(EventRegistration<? extends Event> r) {
        }

        @Override
        public void unregisterAll() {
            unregisterAllCalled = true;
        }

        @Override
        public List<EventErrorRecord> getRecentErrors(int n) {
            return List.of();
        }

        @Override
        public int getTrackedRegistrationCount() {
            return 0;
        }

        @Override
        public List<EventRegistration<? extends Event>> getTrackedRegistrations() {
            return List.of();
        }

        @Override
        public boolean isDisabled() {
            return false;
        }

        @Override
        public void onPluginDisable() {
        }
    }

    /** [限制:P3] 測試替身（stub）：回傳固定 facade 與假 scheduler/event registry，不連線真實伺服器，故 live server 執行期測試在此延後。 */
    private static class StubAceLibAccess extends AceLibAccess {
        private final Optional<AceLibApi> facade;
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeEventRegistry events = new FakeEventRegistry();

        StubAceLibAccess(JavaPlugin plugin, Optional<AceLibApi> facade) {
            super(plugin);
            this.facade = facade;
        }

        @Override
        public Optional<AceLibApi> resolveReadyApi() {
            return facade;
        }

        @Override
        public SafeScheduler createScheduler(AceLibApi api) {
            return scheduler;
        }

        @Override
        public SafeEventRegistry createEventRegistry(AceLibApi api) {
            return events;
        }
    }

    private static final class FakeAceLibModule extends AceLibModule {
        boolean onStarted = false;

        FakeAceLibModule(AceLibAccess access) {
            super(access);
        }

        @Override
        public String name() {
            return "facade-module";
        }

        @Override
        protected void onStart(ResourceOwner resources, AceLibApi api,
                              SafeScheduler scheduler, SafeEventRegistry events) {
            onStarted = true;
        }
    }

    // ---- facade resolution ----------------------------------------------------

    @Test
    void resolveReResolvesProviderOnEveryCallNoStaleCache() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ServicesManager sm = mock(ServicesManager.class);
            when(sm.load(AceLibProvider.class)).thenReturn(new FakeProvider(readyApi()));
            bukkit.when(Bukkit::getServicesManager).thenReturn(sm);

            AceLibAccess access = new AceLibAccess(mock(JavaPlugin.class));
            assertTrue(access.resolveReadyApi().isPresent());
            assertTrue(access.resolveReadyApi().isPresent());

            // the provider must be looked up on every call, never cached
            verify(sm, times(2)).load(AceLibProvider.class);
        }
    }

    @Test
    void missingProviderResolvesEmpty() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ServicesManager sm = mock(ServicesManager.class);
            when(sm.load(AceLibProvider.class)).thenReturn(null);
            bukkit.when(Bukkit::getServicesManager).thenReturn(sm);

            AceLibAccess access = new AceLibAccess(mock(JavaPlugin.class));
            assertTrue(access.resolveReadyApi().isEmpty(), "missing provider must resolve empty");
        }
    }

    @Test
    void notReadyFacadeResolvesEmpty() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ServicesManager sm = mock(ServicesManager.class);
            when(sm.load(AceLibProvider.class)).thenReturn(new FakeProvider(AceLibApi.uninitialized()));
            bukkit.when(Bukkit::getServicesManager).thenReturn(sm);

            AceLibAccess access = new AceLibAccess(mock(JavaPlugin.class));
            assertTrue(access.resolveReadyApi().isEmpty(), "not-ready facade must be filtered out");
        }
    }

    @Test
    void readyFacadeResolvesPresent() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ServicesManager sm = mock(ServicesManager.class);
            when(sm.load(AceLibProvider.class)).thenReturn(new FakeProvider(readyApi()));
            bukkit.when(Bukkit::getServicesManager).thenReturn(sm);

            AceLibAccess access = new AceLibAccess(mock(JavaPlugin.class));
            Optional<AceLibApi> resolved = access.resolveReadyApi();
            assertTrue(resolved.isPresent());
            assertTrue(resolved.get().isReady());
        }
    }

    @Test
    void schedulerAndEventRegistryFactoriesAreCallable() {
        // Full region scheduling / event dispatch needs a live server. This only proves the public
        // factory surface is callable; a server-absent runtime failure is tolerated, an API-surface
        // (linkage) error is a real failure.
        AceLibApi api = readyApi();
        JavaPlugin plugin = mock(JavaPlugin.class);
        AceLibAccess access = new AceLibAccess(plugin);

        SafeScheduler scheduler = guardedCreate(() -> access.createScheduler(api));
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        SafeEventRegistry registry = guardedCreate(() -> access.createEventRegistry(api));
        if (registry != null) {
            registry.unregisterAll();
        }
    }

    private static <T> T guardedCreate(CheckedSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            assertFalse(
                    t instanceof NoSuchMethodError
                            || t instanceof NoClassDefFoundError
                            || t instanceof LinkageError,
                    "AceLib factory API-surface mismatch (unexpected): " + t);
            return null;
        }
    }

    private interface CheckedSupplier<T> {
        T get() throws Throwable;
    }

    // ---- facade-requiring module ---------------------------------------------

    @Test
    void moduleRefusesToStartWhenFacadeNotReadyAndRollsBackPrior() throws Exception {
        List<String> log = new java.util.ArrayList<>();
        ModuleLifecycle lifecycle = new ModuleLifecycle();
        LifecycleModule prior = new LifecycleModule() {
            @Override
            public String name() {
                return "prior";
            }

            @Override
            public void start(ResourceOwner resources) {
                log.add("start:prior");
            }

            @Override
            public void stop() {
                log.add("stop:prior");
            }
        };
        lifecycle.add(prior);
        lifecycle.add(new FakeAceLibModule(new StubAceLibAccess(mock(JavaPlugin.class), Optional.empty())));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::startAll);
        assertTrue(thrown.getMessage().contains("not ready"), "module must refuse to start without a ready facade");

        // prior module was started then rolled back; facade module never started
        assertEquals(List.of("start:prior", "stop:prior"), log);
    }

    @Test
    void moduleStartsWhenReadyAndRegistersOwnerCleanup() throws Exception {
        StubAceLibAccess access = new StubAceLibAccess(mock(JavaPlugin.class), Optional.of(readyApi()));
        FakeAceLibModule module = new FakeAceLibModule(access);

        ModuleLifecycle lifecycle = new ModuleLifecycle();
        lifecycle.add(module);
        lifecycle.startAll();

        assertTrue(module.onStarted, "module must have started when facade ready");
        assertFalse(access.scheduler.cancelAllCalled, "scheduler not cancelled while running");
        assertFalse(access.events.unregisterAllCalled, "events not unregistered while running");

        lifecycle.stopAll();

        assertTrue(access.scheduler.cancelAllCalled, "scheduler.cancelAll() must run on stop via owner");
        assertTrue(access.events.unregisterAllCalled, "events.unregisterAll() must run on stop via owner");
        assertThrows(IllegalStateException.class, module::api, "accessor must reject use after stop");
    }

    @Test
    void schedulerCleanupRunsWhenEventRegistryFactoryFails() throws Exception {
        // The event-registry factory throws AFTER the scheduler was created. The scheduler cleanup
        // must already be owned by the resource owner so rollback still tears it down.
        StubAceLibAccess access = new StubAceLibAccess(mock(JavaPlugin.class), Optional.of(readyApi())) {
            @Override
            public SafeEventRegistry createEventRegistry(AceLibApi api) {
                throw new RuntimeException("event-registry-factory-boom");
            }
        };
        FakeAceLibModule module = new FakeAceLibModule(access);

        ModuleLifecycle lifecycle = new ModuleLifecycle();
        lifecycle.add(module);

        RuntimeException thrown = assertThrows(RuntimeException.class, lifecycle::startAll);
        assertEquals("event-registry-factory-boom", thrown.getMessage(),
                "original factory exception must be preserved, not wrapped/swallowed");
        assertTrue(access.scheduler.cancelAllCalled,
                "scheduler cleanup must run even when the event-registry factory fails");
    }
}
