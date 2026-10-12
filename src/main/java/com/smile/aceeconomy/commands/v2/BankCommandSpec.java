package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedSubCommand;

/** AceLib spec for opening the bank presentation surface. */
public final class BankCommandSpec {

    private BankCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        TypedSubCommand open = TypedSubCommand.builder("open")
                .description("Open the bank")
                .usage("")
                .permission("aceeconomy.command.bank")
                .playerOnly()
                .executes(context -> {
                    var player = context.requireOnlinePlayer();
                    services.bank().open(player.getUniqueId(), player.getName());
                })
                .build();
        return TypedCommand.builder("bank")
                .description("Open the bank")
                .usage("/bank open")
                .permission("aceeconomy.command.bank")
                .subcommand(open)
                .build()
                .toCommandSpec();
    }
}
