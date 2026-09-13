package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the live {@code [ACELIB-CMD-011]} symptom: localized replies used to resolve and
 * message a Bukkit Player directly on the IO completion thread that ran the command's
 * {@code whenComplete}. These tests lock the seam contract — Component replies dispatch through
 * {@link CommandReply.RegionDispatch} (player region thread), and without a seam they degrade to
 * the plain-string async path instead of touching Bukkit.
 */
class CommandReplyRegionDispatchTest {

    /** Recording seam: resolves a programmed player and runs actions synchronously. */
    private static final class RecordingDispatch implements CommandReply.RegionDispatch {
        Player player;
        final List<UUID> ids = new ArrayList<>();
        final List<Consumer<Player>> actions = new ArrayList<>();

        @Override
        public boolean runForPlayer(UUID playerId, Consumer<Player> action) {
            ids.add(playerId);
            actions.add(action);
            if (player == null) {
                return false;
            }
            action.accept(player);
            return true;
        }
    }

    private final RecordingDispatch dispatch = new RecordingDispatch();

    @BeforeEach
    void installSeam() {
        CommandReply.installRegionDispatch(dispatch);
    }

    @AfterEach
    void uninstallSeam() {
        CommandReply.installRegionDispatch(null);
    }

    private record Harness(CommandContext ctx, Sender sender, PlayerHandle handle,
                           Player player, ConfigLangAdapter messages) {
    }

    private Harness harness() {
        UUID playerId = UUID.randomUUID();
        Sender sender = mock(Sender.class);
        PlayerHandle handle = mock(PlayerHandle.class);
        Player player = mock(Player.class);
        when(sender.isPlayer()).thenReturn(true);
        when(sender.asPlayer()).thenReturn(handle);
        when(handle.getUniqueId()).thenReturn(playerId);
        ConfigLangAdapter messages = mock(ConfigLangAdapter.class);
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.sender()).thenReturn(sender);
        dispatch.player = player;
        return new Harness(ctx, sender, handle, player, messages);
    }

    @Test
    void componentReplyDispatchesThroughTheRegionSeamWithoutTouchingBukkit() {
        Harness h = harness();
        Component component = Component.text("withdraw note");

        CommandReply.replyComponent(h.ctx(), h.messages(), component);

        assertEquals(1, dispatch.ids.size());
        assertEquals(h.handle().getUniqueId(), dispatch.ids.get(0));
        // The component was delivered on the region context via the lang adapter…
        verify(h.messages()).sendChatWithFallback(same(h.player()), same(component), isNull());
        // …and the calling thread never degraded to the string fallback.
        verify(h.ctx(), never()).replyPlayerAsync(any(String.class));
    }

    @Test
    void localizedReplyRendersAndDispatchesOnTheRegionSeam() {
        Harness h = harness();
        Component rendered = Component.text("rendered message");
        when(h.messages().renderMessage(eq("economy.withdraw-note"), any(Map.class))).thenReturn(rendered);

        CommandReply.replyLocalized(h.ctx(), h.messages(), "economy.withdraw-note",
                Map.of("amount", "100"));

        verify(h.messages()).sendChatWithFallback(same(h.player()), same(rendered), isNull());
        verify(h.ctx(), never()).replyPlayerAsync(any(String.class));
    }

    @Test
    void failedComponentDeliveryFallsBackToStringAsyncPath() {
        Harness h = harness();
        Component component = Component.text("withdraw note");
        doThrow(new IllegalStateException("region unavailable"))
                .when(h.messages()).sendChatWithFallback(any(Player.class), any(Component.class), any());

        CommandReply.replyComponent(h.ctx(), h.messages(), component);

        // The failure is compensated by the plain-string async path, never swallowed silently.
        verify(h.ctx()).replyPlayerAsync("withdraw note");
    }

    @Test
    void offlinePlayerAtDispatchTimeDropsTheReplySilently() {
        Harness h = harness();
        dispatch.player = null; // region executor cannot resolve the player

        CommandReply.replyComponent(h.ctx(), h.messages(), Component.text("gone"));

        assertEquals(1, dispatch.ids.size(), "the dispatch must still be attempted");
        verify(h.messages(), never()).sendChatWithFallback(any(), any(), any());
        verify(h.ctx(), never()).replyPlayerAsync(any(String.class));
    }

    @Test
    void withoutSeamComponentRepliesDegradeToStringAsyncPathInsteadOfBukkit() {
        Harness h = harness();
        CommandReply.installRegionDispatch(null); // pre-wiring / offline tests

        CommandReply.replyComponent(h.ctx(), h.messages(), Component.text("plain"));

        verify(h.ctx()).replyPlayerAsync("plain");
        verify(h.messages(), never()).sendChatWithFallback(any(), any(), any());
    }

    @Test
    void consoleSenderKeepsTheSynchronousPlainReply() {
        Harness h = harness();
        Sender console = mock(Sender.class);
        when(console.isPlayer()).thenReturn(false);
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.sender()).thenReturn(console);
        when(h.messages().plainMessage("economy.withdraw-note", Map.of("amount", "100")))
                .thenReturn("withdrawn 100");

        CommandReply.replyLocalized(ctx, h.messages(), "economy.withdraw-note", Map.of("amount", "100"));

        verify(ctx).reply("withdrawn 100");
        assertTrue(dispatch.ids.isEmpty(), "console replies never touch the player seam");
        assertFalse(dispatch.ids.contains(null));
    }
}
