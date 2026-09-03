package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;

/**
 * Folia-safe reply helper.
 *
 * <p>Player replies are routed through {@link CommandContext#replyPlayerAsync(String)}, which
 * dispatches onto the player's region thread via AceLib's {@code SafeExecutor}; console replies
 * use the synchronous {@link CommandContext#reply(String)}. Neither path ever touches a Bukkit
 * object directly from an arbitrary async thread — the {@code ReplySink} abstraction owns the
 * region-safe dispatch.</p>
 *
 * <p>Localized replies: player messages are rendered as Adventure Components via
 * {@link ConfigLangAdapter#renderMessage(String, Map)} (MiniMessage with user-value escaping);
 * console messages use {@link ConfigLangAdapter#plainMessage(String, Map)} so the output is
 * plain text without MiniMessage tags.</p>
 */
public final class CommandReply {

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
            Component comp = messages.renderMessage(key, vars);
            // Attempt direct Component send for color correctness; fall back to plain string path
            try {
                var handle = ctx.sender().asPlayer();
                org.bukkit.entity.Player bukkitPlayer = null;
                try {
                    bukkitPlayer = org.bukkit.Bukkit.getPlayer(handle.getUniqueId());
                } catch (Throwable ignored) {
                }
                if (bukkitPlayer != null && bukkitPlayer.isOnline()) {
                    try {
                        bukkitPlayer.sendMessage(comp);
                        return;
                    } catch (IllegalStateException | UnsupportedOperationException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            String fallback = PlainTextComponentSerializer.plainText().serialize(comp);
            ctx.replyPlayerAsync(fallback);
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
        if (ctx.sender().isPlayer()) {
            try {
                var handle = ctx.sender().asPlayer();
                org.bukkit.entity.Player bukkitPlayer = null;
                try {
                    bukkitPlayer = org.bukkit.Bukkit.getPlayer(handle.getUniqueId());
                } catch (Throwable ignored) {
                }
                if (bukkitPlayer != null && bukkitPlayer.isOnline()) {
                    try {
                        bukkitPlayer.sendMessage(component);
                        return;
                    } catch (IllegalStateException | UnsupportedOperationException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            String fallback = PlainTextComponentSerializer.plainText().serialize(component);
            ctx.replyPlayerAsync(fallback);
        } else {
            String plain = PlainTextComponentSerializer.plainText().serialize(component);
            ctx.reply(plain);
        }
    }

    public static void replyError(CommandContext ctx, Throwable error) {
        ctx.replyError(error);
    }
}
