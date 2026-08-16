package com.smile.aceeconomy.infrastructure.integration.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * Registration seam for a PlaceholderAPI {@link PlaceholderExpansion}.
 *
 * <p>Abstracts PAPI's registration so the lifecycle owner and its tests never touch a live server.
 * The production binding is {@link BukkitPlaceholderRegistration}.</p>
 */
public interface PlaceholderRegistration {

    void register(PlaceholderExpansion expansion);

    void unregister(PlaceholderExpansion expansion);

    boolean isRegistered(PlaceholderExpansion expansion);
}
