package com.smile.aceeconomy.infrastructure.integration.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.util.Objects;

/**
 * Production {@link PlaceholderRegistration} backed by PlaceholderAPI's expansion lifecycle.
 *
 * <p>Registration/unregistration is delegated to the expansion instance itself; the lifecycle owner
 * only ever touches the expansion it created.</p>
 */
public final class BukkitPlaceholderRegistration implements PlaceholderRegistration {

    @Override
    public void register(PlaceholderExpansion expansion) {
        Objects.requireNonNull(expansion, "expansion").register();
    }

    @Override
    public void unregister(PlaceholderExpansion expansion) {
        Objects.requireNonNull(expansion, "expansion").unregister();
    }

    @Override
    public boolean isRegistered(PlaceholderExpansion expansion) {
        return Objects.requireNonNull(expansion, "expansion").isRegistered();
    }
}
