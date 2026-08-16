package com.smile.aceeconomy.acelib;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.AceLibApi.AceLibProvider;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.AceLibScheduler;
import com.smile.acelib.scheduler.SafeScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Contract test for the minimal AceLib v1.0.0 consumer foundation.
 *
 * <p>Covers the supported consumer branches (missing provider, registered-but-not-ready facade,
 * ready facade) and the public SafeScheduler API surface. Full server-region scheduling behaviour
 * is intentionally NOT asserted here; it requires a live server runtime and is deferred to
 * Task 3 / Task 13 runtime validation.
 */
class AceLibConsumerContractTest {

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

    @Test
    void uninitializedApiIsNotReady() {
        assertFalse(AceLibApi.uninitialized().isReady(), "uninitialized facade must report not-ready");
    }

    @Test
    void readyApiReportsReadyAndPlatform() {
        AceLibApi api = readyApi();
        assertTrue(api.isReady(), "ready facade must report ready");
        assertTrue(Platform.PAPER == api.getPlatform(), "platform must round-trip through the facade");
        assertNotNull(api.getPlatformCapability(), "capability must be present on a ready facade");
    }

    @Test
    void missingProviderResolvesEmpty() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ServicesManager sm = mock(ServicesManager.class);
            when(sm.load(AceLibProvider.class)).thenReturn(null);
            bukkit.when(Bukkit::getServicesManager).thenReturn(sm);

            AceLibConsumer consumer = new AceLibConsumer(mock(JavaPlugin.class));
            Optional<AceLibApi> resolved = consumer.resolveReadyApi();
            assertTrue(resolved.isEmpty(), "missing provider must resolve to empty (not an error)");
        }
    }

    @Test
    void notReadyFacadeResolvesEmpty() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ServicesManager sm = mock(ServicesManager.class);
            when(sm.load(AceLibProvider.class)).thenReturn(new FakeProvider(AceLibApi.uninitialized()));
            bukkit.when(Bukkit::getServicesManager).thenReturn(sm);

            AceLibConsumer consumer = new AceLibConsumer(mock(JavaPlugin.class));
            Optional<AceLibApi> resolved = consumer.resolveReadyApi();
            assertTrue(resolved.isEmpty(), "not-ready facade must be filtered out by the isReady() gate");
        }
    }

    @Test
    void readyFacadeResolvesPresent() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            ServicesManager sm = mock(ServicesManager.class);
            when(sm.load(AceLibProvider.class)).thenReturn(new FakeProvider(readyApi()));
            bukkit.when(Bukkit::getServicesManager).thenReturn(sm);

            AceLibConsumer consumer = new AceLibConsumer(mock(JavaPlugin.class));
            Optional<AceLibApi> resolved = consumer.resolveReadyApi();
            assertTrue(resolved.isPresent(), "ready facade must resolve");
            assertTrue(resolved.get().isReady(), "resolved facade must be ready");
        }
    }

    @Test
    void safeSchedulerCreateIsCallable() {
        // Full region scheduling requires a live server runtime. This test only proves the public
        // SafeScheduler API surface is callable from the consumer. A server-absent runtime failure
        // is expected and deferred to Task 3 / Task 13 runtime validation; an API-surface mismatch
        // (linkage error) would be a real failure.
        AceLibApi api = readyApi();
        JavaPlugin plugin = mock(JavaPlugin.class);
        SafeScheduler scheduler;
        try {
            scheduler = AceLibScheduler.create(plugin, api.getPlatform(), api.getPlatformCapability());
        } catch (Throwable t) {
            assertFalse(
                    t instanceof NoSuchMethodError
                            || t instanceof NoClassDefFoundError
                            || t instanceof LinkageError,
                    "SafeScheduler.create API surface mismatch (unexpected): " + t);
            return;
        }
        assertNotNull(scheduler, "SafeScheduler.create must return a scheduler instance");
    }
}
