package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.command.BukkitReplySink;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Region-safe reply backend for {@link BukkitReplySink}, bound to the consumer's own
 * {@link FoliaContextExecutor} (AceLib SafeScheduler region dispatch).
 *
 * <p>Why this exists: {@code BukkitReplySink.SafeExecutorBackend.detect(plugin)} only accepts a
 * ready {@code AceLibPlugin} as the owner and rejects every other plugin with
 * {@code ACELIB-CMD-011} — an economy plugin must not depend on the AceLib plugin identity, so
 * the documented consumer path is the sink's injected-backend constructor. This class is that
 * injection point in production: player replies that arrive from IO completion threads are handed
 * to the region-aware scheduler and executed on the target player's region thread, exactly like
 * every other player mutation in this plugin. The owner plugin argument is ignored on purpose —
 * logging stays with the sink's owner, dispatch stays with the scheduler.
 */
public final class FoliaRegionReplyBackend implements BukkitReplySink.SafeExecutorBackend {

    private final FoliaContextExecutor folia;

    public FoliaRegionReplyBackend(@NotNull FoliaContextExecutor folia) {
        this.folia = java.util.Objects.requireNonNull(folia, "folia");
    }

    @Override
    public void runOnPlayerRegion(@NotNull JavaPlugin owner, @NotNull Player player,
                                  @NotNull Runnable runnable) {
        folia.runForPlayer(player, runnable);
    }
}
