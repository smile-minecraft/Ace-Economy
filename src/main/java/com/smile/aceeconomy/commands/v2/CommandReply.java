package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;

/**
 * Folia-safe reply helper.
 *
 * <p>Plain-string player replies are routed through {@link CommandContext#replyPlayerAsync(String)},
 * which dispatches onto the player's region thread via the registry's {@code ReplySink}; console
 * replies use the synchronous {@link CommandContext#reply(String)}. Localized Component replies
 * are handed to the installed {@link RegionDispatch} seam so {@code sendChatWithFallback} executes
 * on the player's region thread (keeping Bedrock click degradation and full component styling);
 * the calling thread — an IO completion thread for async commands — never resolves or touches a
 * Bukkit Player. When no seam is installed, Component replies degrade to the plain-string async
 * path rather than performing direct Bukkit access.</p>
 *
 * <p>Localized replies: player messages are rendered as Adventure Components via
 * {@link ConfigLangAdapter#renderMessage(String, Map)} (MiniMessage with user-value escaping)
 * and delivered through {@link ConfigLangAdapter#sendChatWithFallback} so Bedrock
 * players get readable click hints while Java players receive the original Component;
 * console messages use {@link ConfigLangAdapter#plainMessage(String, Map)} so the output is
 * plain text without MiniMessage tags.</p>
 */
public final class CommandReply {

    /**
     * Region-safe player dispatch seam for Component replies. The composition root installs the
     * production binding (a {@code FoliaContextExecutor.runForPlayer(UUID, Consumer)} method
     * reference) once at startup; when no seam is installed — offline unit tests, or a plain
     * {@code reply} before wiring — Component replies degrade to the plain-string async path.
     * Without this seam, a Component send would have to resolve and message a Bukkit Player
     * directly from the IO completion thread, which is exactly the Folia-unsafe shortcut the
     * reply layer must never take again.
     */
    @FunctionalInterface
    public interface RegionDispatch {
        boolean runForPlayer(java.util.UUID playerId, java.util.function.Consumer<org.bukkit.entity.Player> action);
    }

    private static volatile RegionDispatch regionDispatch;

    /** Install the region-safe dispatch seam; pass {@code null} to uninstall (shutdown/tests). */
    public static void installRegionDispatch(RegionDispatch dispatch) {
        regionDispatch = dispatch;
    }

    private CommandReply() {
    }

    public static void reply(CommandContext ctx, String message) {
        if (ctx.sender().isPlayer()) {
            ctx.replyPlayerAsync(message);
        } else {
            ctx.reply(message);
        }
    }

    public static void replyLocalized(CommandContext ctx, ConfigLangAdapter messages,
                                      String key, Map<String, Object> vars) {
        if (messages == null) {
            reply(ctx, key);
            return;
        }
        if (ctx.sender().isPlayer()) {
            replyComponent(ctx, messages, messages.renderMessage(key, vars));
        } else {
            String plain = messages.plainMessage(key, vars);
            ctx.reply(plain);
        }
    }

    public static void replyComponent(CommandContext ctx, ConfigLangAdapter messages, Component component) {
        if (messages == null) {
            String fallback = PlainTextComponentSerializer.plainText().serialize(component);
            reply(ctx, fallback);
            return;
        }
        if (!ctx.sender().isPlayer()) {
            ctx.reply(PlainTextComponentSerializer.plainText().serialize(component));
            return;
        }
        String fallback = PlainTextComponentSerializer.plainText().serialize(component);
        RegionDispatch dispatch = regionDispatch;
        if (dispatch == null) {
            // No seam installed (offline tests / pre-wiring): degrade to the plain-string
            // async path instead of resolving a Bukkit Player on the calling thread.
            ctx.replyPlayerAsync(fallback);
            return;
        }
        // Component delivery runs on the player's region thread; the calling thread (an IO
        // completion thread for async commands) never resolves or touches a Bukkit Player.
        dispatch.runForPlayer(ctx.sender().asPlayer().getUniqueId(), player -> {
            try {
                messages.sendChatWithFallback(player, component, null);
            } catch (IllegalStateException | UnsupportedOperationException ignored) {
                ctx.replyPlayerAsync(fallback);
            }
        });
    }

    public static void replyError(CommandContext ctx, Throwable error) {
        ctx.replyError(error);
    }
}
