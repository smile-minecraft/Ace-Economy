package com.smile.aceeconomy.infrastructure.integration.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.bukkit.OfflinePlayer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * v2 PlaceholderAPI expansion for the {@code aceeco} namespace.
 *
 * <p>Delegates all resolution to {@link PlaceholderResolver}; this class only adapts the PAPI
 * lifecycle (identifier, author, version, registration). The namespace is {@code aceeco} and the
 * exact placeholder names are documented in {@link PlaceholderResolver} and {@code docs/integrations.md}.</p>
 */
public final class AceEconomyExpansion extends PlaceholderExpansion {

    /** Documented PAPI namespace for AceEconomy v2. */
    public static final String IDENTIFIER = "aceeco";

    private final PlaceholderResolver resolver;
    private final String version;

    public AceEconomyExpansion(PlaceholderResolver resolver, String version) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public @NotNull String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public @NotNull String getAuthor() {
        return "Smile";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        // Keep the expansion registered across plugin reloads.
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return resolver.resolve(player, params);
    }
}
