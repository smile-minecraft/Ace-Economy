package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;

/**
 * Folia-safe reply helper.
 *
 * <p>Player replies are routed through {@link CommandContext#replyPlayerAsync(String)}, which
 * dispatches onto the player's region thread via AceLib's {@code SafeExecutor}; console replies
 * use the synchronous {@link CommandContext#reply(String)}. Neither path ever touches a Bukkit
 * object directly from an arbitrary async thread — the {@code ReplySink} abstraction owns the
 * region-safe dispatch.</p>
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

    public static void replyError(CommandContext ctx, Throwable error) {
        ctx.replyError(error);
    }
}
