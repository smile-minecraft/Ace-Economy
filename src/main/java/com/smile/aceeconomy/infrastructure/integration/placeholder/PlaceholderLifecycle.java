package com.smile.aceeconomy.infrastructure.integration.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.util.Objects;

/**
 * Lifecycle owner for the v2 {@link AceEconomyExpansion}.
 *
 * <p>Owns a single expansion instance and its registration. {@link #start()} and {@link #stop()} are
 * idempotent: double {@code start()} registers once; {@code stop()} when not registered (or after a
 * previous {@code stop()}) is a no-op. {@link #stop()} only ever unregisters the expansion this
 * owner created. When constructed with a {@link PlaceholderBalanceLoader}, {@link #stop()} also
 * closes it so no new background primes are dispatched after shutdown; already-running loads
 * settle through the service's stamp fence.</p>
 */
public final class PlaceholderLifecycle {

    private final PlaceholderRegistration registration;
    private final PlaceholderExpansion expansion;
    private final PlaceholderBalanceLoader balanceLoads;

    private volatile boolean registered = false;

    public PlaceholderLifecycle(PlaceholderRegistration registration, PlaceholderExpansion expansion) {
        this(registration, expansion, null);
    }

    public PlaceholderLifecycle(PlaceholderRegistration registration, PlaceholderExpansion expansion,
            PlaceholderBalanceLoader balanceLoads) {
        this.registration = Objects.requireNonNull(registration, "registration");
        this.expansion = Objects.requireNonNull(expansion, "expansion");
        this.balanceLoads = balanceLoads;
    }

    /** Idempotent: registers the owned expansion exactly once. */
    public void start() {
        if (registered) {
            return;
        }
        registration.register(expansion);
        registered = true;
    }

    /** Idempotent: unregisters only the owned expansion, and only if currently registered. */
    public void stop() {
        // Close the loader even when the expansion was never registered: after shutdown no
        // new background primes may be dispatched regardless of how startup unfolded.
        if (balanceLoads != null) {
            balanceLoads.close();
        }
        if (!registered) {
            return;
        }
        registration.unregister(expansion);
        registered = false;
    }

    public boolean isRegistered() {
        return registered;
    }
}
