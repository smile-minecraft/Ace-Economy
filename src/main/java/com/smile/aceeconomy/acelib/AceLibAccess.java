package com.smile.aceeconomy.acelib;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.event.AceLibEvents;
import com.smile.acelib.event.SafeEventRegistry;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.SafeScheduler;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * v2 facade accessor and external-resource factory for AceLib v1.0.0.
 *
 * <p>Every facade lookup re-resolves through the Bukkit {@link org.bukkit.plugin.ServicesManager}
 * and gates on {@link AceLibApi#isReady()}; a stale or unregistered facade is never cached. Scheduler
 * and event-registry instances are created from the <em>current</em> ready facade and are owned by
 * the caller, which must register their teardown ({@link SafeScheduler#cancelAll()} /
 * {@link SafeEventRegistry#unregisterAll()}) with a {@code ResourceOwner}.
 *
 * <p>Only the {@code (JavaPlugin, Platform, PlatformCapability)} factory variants are used; the
 * {@code AceLibPlugin}-based variants and any internal datastore are intentionally not touched.
 */
public class AceLibAccess {

    private final JavaPlugin plugin;
    private final AceLibConsumer consumer;

    public AceLibAccess(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.consumer = new AceLibConsumer(plugin);
    }

    /**
     * Resolve the current ready AceLib facade, re-resolving on every call.
     *
     * @return a ready facade, or {@link Optional#empty()} when missing or not ready
     */
    @NotNull
    public Optional<AceLibApi> resolveReadyApi() {
        return consumer.resolveReadyApi();
    }

    /**
     * Create a SafeScheduler bound to the given (already-ready) facade's platform and capability.
     */
    @NotNull
    public SafeScheduler createScheduler(@NotNull AceLibApi api) {
        return consumer.createScheduler(api);
    }

    /**
     * Create a SafeEventRegistry bound to the given (already-ready) facade's platform and capability.
     */
    @NotNull
    public SafeEventRegistry createEventRegistry(@NotNull AceLibApi api) {
        Platform platform = api.getPlatform();
        PlatformCapability capability = api.getPlatformCapability();
        return AceLibEvents.create(plugin, platform, capability);
    }
}
