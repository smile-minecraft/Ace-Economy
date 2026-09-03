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
        var messages = services.messages();
        String targetName = V2CommandSupport.arg(messages, context, 0);
        String currencyId = context.commandArgs().size() == 3 ? V2CommandSupport.arg(messages, context, 2) : null;
        CurrencyInfo currency = V2CommandSupport.currency(services, currencyId);
        var amount = V2CommandSupport.amount(messages, context, currency, 1);
        var sender = context.requireOnlinePlayer();
        services.players().resolve(targetName).whenComplete((target, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (target.isEmpty()) {
                String msg = messages != null
                        ? messages.plainMessage("general.player-not-found", java.util.Map.of("player", targetName))
                        : "general.player-not-found";
                CommandReply.replyError(context, com.smile.acelib.command.CommandException.custom(
                        "ACELIB-CMD-ACCOUNT-NOT-FOUND", msg));
            } else {
                PlayerIdentity identity = target.get();
                V2CommandSupport.replyLocalized(context, messages,
                        services.economy().transfer(sender.getUniqueId(), identity.uuid(), currency.id(), amount),
                        ignored -> payComponent(messages, currency, amount, identity.name()));
            }
        });
    }

    private static net.kyori.adventure.text.Component payComponent(
            com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
            CurrencyInfo currency, com.smile.aceeconomy.domain.Amount amount, String targetName) {
        String amountStr = CommandFormat.formatAmount(messages, currency, amount);
        if (messages == null) {
            return net.kyori.adventure.text.Component.text("economy.payment-sent:" + amountStr + ":" + targetName);
        }
        boolean isDefault = currency.isDefault();
        if (isDefault) {
            return messages.renderMessage("economy.payment-sent",
                    java.util.Map.of("amount", amountStr, "player", targetName));
        } else {
            return messages.renderMessage("economy.payment-sent-currency",
                    java.util.Map.of("amount", amountStr, "player", targetName, "currency_name", currency.displayName()));
        }
    }

    private static List<String> complete(CommandServices services, List<String> args) {
        if (args.size() >= 3) {
            return CommandCompletion.byPrefix(services.economy().knownCurrencyIds(), CommandCompletion.last(args));
        }
        return CommandCompletion.byPrefix(services.players().onlinePlayerNames(), CommandCompletion.last(args));
    }
}
