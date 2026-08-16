package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.SubCommandSpec;

/** AceLib spec for opening the bank presentation surface. */
public final class BankCommandSpec {

    private BankCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        SubCommandSpec open = SubCommandSpec.builder("open")
                .description("Open the bank")
                .usage("")
                .permission("aceeconomy.command.bank")
                .playerOnly()
                .minArgs(0)
                .maxArgs(0)
                .handler(context -> {
                    var player = context.requireOnlinePlayer();
                    services.bank().open(player.getUniqueId(), player.getName());
                })
                .build();
        return CommandSpec.builder("bank")
                .description("Open the bank")
                .usage("/bank open")
                .permission("aceeconomy.command.bank")
                .subCommand(open)
                .build();
    }
}
