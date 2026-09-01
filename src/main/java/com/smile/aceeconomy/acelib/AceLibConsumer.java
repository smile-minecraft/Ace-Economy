package com.smile.aceeconomy.acelib;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.AceLibApi.AceLibProvider;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.scheduler.AceLibScheduler;
import com.smile.acelib.scheduler.SafeScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Minimal AceLib v1.2.0 consumer foundation for AceEconomy v2.
 *
 * <p>Demonstrates the supported consumer contract against AceLib:
 * <ul>
 *     <li>resolve the provider through the Bukkit {@code ServicesManager}
 *         (never cast the plugin implementation class);</li>
 *     <li>treat a missing registration as "AceLib not available" — a normal branch, not an error;</li>
 *     <li>re-resolve the current facade on every call and gate on {@link AceLibApi#isReady()}
 *         (never permanently cache a stale facade);</li>
 *     <li>build a {@link SafeScheduler} from the current facade's platform/capability.</li>
 * </ul>
 *
 * <p>This is intentionally a thin, side-effect-free adapter. It is NOT the v2 CompositionRoot,
 * module lifecycle, or production wiring — those belong to later tasks.
 */
public final class AceLibConsumer {

    private final JavaPlugin plugin;

    public AceLibConsumer(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Look up the AceLib provider from the Bukkit ServicesManager.
     *
     * <p>Registration may be missing (AceLib not installed or not yet enabled). That is a normal
     * branch and must not be treated as a failure.
     *
     * @return the provider, or {@link Optional#empty()} when not registered
     */
    @NotNull
    public Optional<AceLibProvider> lookupProvider() {
        AceLibProvider provider = Bukkit.getServicesManager().load(AceLibProvider.class);
        return Optional.ofNullable(provider);
    }

    /**
     * Resolve the current AceLib facade and gate on readiness.
     *
     * <p>The facade is re-resolved on every call so a stale or unregistered facade is never cached.
     * Returns {@link Optional#empty()} when the provider is missing OR the facade is not ready.
     *
     * @return a ready facade, or {@link Optional#empty()}
     */
    @NotNull
    public Optional<AceLibApi> resolveReadyApi() {
        return lookupProvider()
                .map(AceLibProvider::api)
                .filter(AceLibApi::isReady);
    }

    /**
     * Create a SafeScheduler bound to the given (already-ready) facade's platform and capability.
     *
     * <p>Must only be called after {@link #resolveReadyApi()} returned a present value; the caller
     * owns the readiness decision, so this method does not re-check it.
     *
     * @param api a ready AceLib facade
     * @return a SafeScheduler for the current platform
     */
    @NotNull
    public SafeScheduler createScheduler(@NotNull AceLibApi api) {
        Platform platform = api.getPlatform();
        PlatformCapability capability = api.getPlatformCapability();
        return AceLibScheduler.create(plugin, platform, capability);
    }
}
