package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.SubCommandSpec;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;

import java.util.List;

/** AceLib spec for balance queries exposed as {@code /money balance}. */
public final class MoneyCommandSpec {

    private MoneyCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        SubCommandSpec balance = SubCommandSpec.builder("balance")
                .description("Show a player's balance")
                .usage("[player] [currency]")
                .permission("aceeconomy.command.money")
                .minArgs(0)
                .maxArgs(2)
                .args("player", "currency")
                .handler(context -> execute(services, context))
                .completer((context, args) -> complete(services, args))
                .build();
        return CommandSpec.builder("money")
                .aliases("balance")
                .description("View an economy balance")
                .usage("/money balance [player] [currency]")
                .permission("aceeconomy.command.money")
                .subCommand(balance)
                .build();
    }

    private static void execute(CommandServices services, CommandContext context) {
        List<String> args = context.commandArgs();
        if (args.size() > 2) {
            throw com.smile.acelib.command.CommandException.custom(
                    "ACELIB-CMD-INVALID-ARGUMENTS", "too many arguments");
        }
        if (args.isEmpty()) {
            var player = context.requireOnlinePlayer();
            CurrencyInfo currency = V2CommandSupport.currency(services, null);
            V2CommandSupport.reply(context, services.economy().getBalance(player.getUniqueId(), currency.id()),
                    value -> player.getName() + " has " + CommandFormat.formatAmount(currency, value));
            return;
        }
        String playerName = V2CommandSupport.arg(context, 0);
        String currencyId = args.size() == 2 ? V2CommandSupport.arg(context, 1) : null;
        CurrencyInfo currency = V2CommandSupport.currency(services, currencyId);
        services.players().resolve(playerName).whenComplete((target, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (target.isEmpty()) {
                CommandReply.replyError(context, com.smile.acelib.command.CommandException.custom(
                        "ACELIB-CMD-ACCOUNT-NOT-FOUND", "unknown player: " + playerName));
            } else {
                PlayerIdentity identity = target.get();
                V2CommandSupport.reply(context,
                        services.economy().getBalance(identity.uuid(), currency.id()),
                        value -> identity.name() + " has " + CommandFormat.formatAmount(currency, value));
            }
        });
    }

    private static List<String> complete(CommandServices services, List<String> args) {
        if (args.size() >= 3) {
            return CommandCompletion.byPrefix(services.economy().knownCurrencyIds(), CommandCompletion.last(args));
        }
        return CommandCompletion.byPrefix(services.players().onlinePlayerNames(), CommandCompletion.last(args));
    }
}
