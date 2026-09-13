package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.command.BukkitReplySink;
import com.smile.acelib.command.BukkitSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the reply-dispatch contract behind the live {@code [ACELIB-CMD-011]} incident: a plain
 * {@code BukkitReplySink(plugin)} cannot dispatch player replies at all (its auto-detected backend
 * refuses every non-AceLibPlugin owner), while the consumer-owned {@link FoliaRegionReplyBackend}
 * routes them onto the Folia player-region executor.
 */
class FoliaReplyBackendTest {

    /** Recording region executor standing in for {@link SafeSchedulerFoliaContext}. */
    private static final class RecordingFolia implements com.smile.aceeconomy.ports.FoliaContextExecutor {
        final List<Player> players = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        @Override
        public void runForPlayer(org.bukkit.entity.Player player, Runnable action) {
            players.add(player);
            actions.add(action);
        }

        @Override
        public void runForEntity(org.bukkit.entity.Entity entity, Runnable action) { }

        @Override
        public void runAtLocation(org.bukkit.Location location, Runnable action) { }

        @Override
        public void runGlobal(Runnable action) { }

        @Override
        public void runAsync(Runnable action) { }
    }

    private static JavaPlugin pluginMock() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FoliaReplyBackendTest"));
        return plugin;
    }

    @Test
    void nonAceLibOwnerBackendRefusesDispatchWithTheStableErrorCode() {
        // Root cause of the live incident: detect() on a plain plugin produces a backend that
        // must never execute the runnable inline — it rejects instead.
        JavaPlugin plugin = pluginMock();
        BukkitReplySink.SafeExecutorBackend backend = BukkitReplySink.SafeExecutorBackend.detect(plugin);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> backend.runOnPlayerRegion(plugin, mock(Player.class), () -> { }));
        assertTrue(ex.getMessage().contains("ACELIB-CMD-011"),
                "the refusal must carry the stable error code: " + ex.getMessage());
    }

    @Test
    void consumerBackendDispatchesRepliesOntoTheRegionExecutor() {
        RecordingFolia folia = new RecordingFolia();
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Alex");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        JavaPlugin plugin = pluginMock();

        BukkitReplySink sink = new BukkitReplySink(plugin, new FoliaRegionReplyBackend(folia));
        sink.send(new BukkitSender(player), "hello world");

        assertEquals(1, folia.players.size(), "the reply must be dispatched to the region executor");
        assertEquals(player, folia.players.get(0));
        // Executing the dispatched action performs the actual send on the region thread.
        folia.actions.get(0).run();
        verify(player).sendMessage("hello world");
    }

    @Test
    void consumerBackendDelegatesToTheWiredExecutorWithoutTouchingTheOwner() {
        RecordingFolia folia = new RecordingFolia();
        Player player = mock(Player.class);
        Runnable action = () -> { };

        new FoliaRegionReplyBackend(folia).runOnPlayerRegion(pluginMock(), player, action);

        assertEquals(List.of(player), folia.players);
        assertEquals(List.of(action), folia.actions);
        assertNotNull(folia.actions.get(0));
    }
}
