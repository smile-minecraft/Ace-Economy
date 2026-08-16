package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.SubCommandSpec;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;

import java.util.List;

/** AceLib spec for player-to-player transfers exposed as {@code /pay send}. */
public final class PayCommandSpec {

    private PayCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        SubCommandSpec send = SubCommandSpec.builder("send")
                .description("Pay another player")
                .usage("<player> <amount> [currency]")
                .permission("aceeconomy.command.pay")
                .playerOnly()
                .minArgs(2)
                .maxArgs(3)
                .args("player", "amount", "currency")
                .handler(context -> execute(services, context))
                .completer((context, args) -> complete(services, args))
                .build();
        return CommandSpec.builder("pay")
                .description("Transfer funds to another player")
                .usage("/pay send <player> <amount> [currency]")
                .permission("aceeconomy.command.pay")
                .subCommand(send)
                .build();
    }

    private static void execute(CommandServices services, CommandContext context) {
        String targetName = V2CommandSupport.arg(context, 0);
        String currencyId = context.commandArgs().size() == 3 ? V2CommandSupport.arg(context, 2) : null;
        CurrencyInfo currency = V2CommandSupport.currency(services, currencyId);
        var amount = V2CommandSupport.amount(context, currency, 1);
        var sender = context.requireOnlinePlayer();
        services.players().resolve(targetName).whenComplete((target, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (target.isEmpty()) {
                CommandReply.replyError(context, com.smile.acelib.command.CommandException.custom(
                        "ACELIB-CMD-ACCOUNT-NOT-FOUND", "unknown player: " + targetName));
            } else {
                PlayerIdentity identity = target.get();
                V2CommandSupport.reply(context,
                        services.economy().transfer(sender.getUniqueId(), identity.uuid(), currency.id(), amount),
                        value -> "Paid " + CommandFormat.formatAmount(currency, amount) + " to " + identity.name());
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
