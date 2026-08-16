package com.smile.aceeconomy.infrastructure.integration.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.util.Objects;

/**
 * Lifecycle owner for the v2 {@link AceEconomyExpansion}.
 *
 * <p>Owns a single expansion instance and its registration. {@link #start()} and {@link #stop()} are
 * idempotent: double {@code start()} registers once; {@code stop()} when not registered (or after a
 * previous {@code stop()}) is a no-op. {@link #stop()} only ever unregisters the expansion this
 * owner created.</p>
 */
public final class PlaceholderLifecycle {

    private final PlaceholderRegistration registration;
    private final PlaceholderExpansion expansion;

    private volatile boolean registered = false;

    public PlaceholderLifecycle(PlaceholderRegistration registration, PlaceholderExpansion expansion) {
        this.registration = Objects.requireNonNull(registration, "registration");
        this.expansion = Objects.requireNonNull(expansion, "expansion");
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
