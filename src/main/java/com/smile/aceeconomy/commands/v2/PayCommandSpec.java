package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedContext;
import com.smile.acelib.command.TypedSubCommand;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;

/** AceLib spec for player-to-player transfers exposed as {@code /pay send}. */
public final class PayCommandSpec {

    private PayCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        CommandArgument<String> player = CommandArguments.playerName(services);
        CommandArgument<String> amount = CommandArguments.amountToken(services);
        CommandArgument<CurrencyInfo> currency = CommandArguments.currency(services);
        TypedSubCommand send = TypedSubCommand.builder("send")
                .description("Pay another player")
                .usage("<player> <amount> [currency]")
                .permission("aceeconomy.command.pay")
                .playerOnly()
                .argument(player)
                .argument(amount)
                .optional(currency, sender -> CommandArguments.defaultCurrency(services))
                .executes(ctx -> execute(services, ctx, player, amount, currency))
                .build();
        return TypedCommand.builder("pay")
                .description("Transfer funds to another player")
                .usage("/pay send <player> <amount> [currency]")
                .permission("aceeconomy.command.pay")
                .subcommand(send)
                .build()
                .toCommandSpec();
    }

    private static void execute(CommandServices services, TypedContext context,
                                CommandArgument<String> playerArg,
                                CommandArgument<String> amountArg,
                                CommandArgument<CurrencyInfo> currencyArg) {
        var messages = services.messages();
        String targetName = context.get(playerArg);
        CurrencyInfo currency = context.get(currencyArg);
        var amount = V2CommandSupport.amount(messages, context.get(amountArg), currency);
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
}
