package com.smile.aceeconomy.infrastructure.integration.vault;

import net.milkbowl.vault.economy.Economy;

import java.util.Objects;

/**
 * Lifecycle owner for the v2 Vault {@link Economy} provider.
 *
 * <p>Owns a single provider instance and its registration. {@link #start()} and {@link #stop()} are
 * idempotent: calling {@code start()} twice registers exactly once; calling {@code stop()} when not
 * registered (or after a previous {@code stop()}) is a no-op. {@link #stop()} only ever
 * unregisters the provider this owner created, never a provider owned by another plugin.</p>
 */
public final class VaultEconomyLifecycle {

    private final VaultRegistration registration;
    private final Economy provider;

    private volatile boolean registered = false;

    public VaultEconomyLifecycle(VaultRegistration registration, Economy provider) {
        this.registration = Objects.requireNonNull(registration, "registration");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /** Idempotent: registers the owned provider exactly once. */
    public void start() {
        if (registered) {
            return;
        }
        registration.register(provider);
        registered = true;
    }

    /** Idempotent: unregisters only the owned provider, and only if currently registered. */
    public void stop() {
        if (!registered) {
            return;
        }
        registration.unregister(provider);
        registered = false;
    }

    public boolean isRegistered() {
        return registered;
    }
}
