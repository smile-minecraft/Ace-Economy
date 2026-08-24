package com.smile.aceeconomy.operations;

/**
 * Read-only seam for the restore safety gate: reports whether any player is currently online.
 *
 * <p>Production adapters read the live Bukkit/Folia online-player set; tests inject a fake.
 * The restore path hard-rejects while this returns {@code true} — it is an explicit boundary,
 * not a quiesce proof.</p>
 */
public interface PlayerOnlineProbe {

    /** True when at least one player is currently online. */
    boolean hasOnlinePlayers();
}
