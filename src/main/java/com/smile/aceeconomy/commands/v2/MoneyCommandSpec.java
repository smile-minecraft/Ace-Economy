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
        var messages = services.messages();
        List<String> args = context.commandArgs();
        if (args.size() > 2) {
            String msg = messages != null
                    ? messages.plainMessage("command.missing-argument", java.util.Map.of("index", "3"))
                    : "command.missing-argument";
            throw com.smile.acelib.command.CommandException.custom("ACELIB-CMD-INVALID-ARGUMENTS", msg);
        }
        if (args.isEmpty()) {
            var player = context.requireOnlinePlayer();
            CurrencyInfo currency = V2CommandSupport.currency(services, null);
            V2CommandSupport.replyLocalized(context, messages,
                    services.economy().getBalance(player.getUniqueId(), currency.id()),
                    value -> balanceComponent(messages, currency, value, player.getName(), true));
            return;
        }
        String playerName = V2CommandSupport.arg(messages, context, 0);
        String currencyId = args.size() == 2 ? V2CommandSupport.arg(messages, context, 1) : null;
        CurrencyInfo currency = V2CommandSupport.currency(services, currencyId);
        services.players().resolve(playerName).whenComplete((target, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (target.isEmpty()) {
                String msg = messages != null
                        ? messages.plainMessage("general.player-not-found", java.util.Map.of("player", playerName))
                        : "general.player-not-found";
                CommandReply.replyError(context, com.smile.acelib.command.CommandException.custom(
                        "ACELIB-CMD-ACCOUNT-NOT-FOUND", msg));
            } else {
                PlayerIdentity identity = target.get();
                V2CommandSupport.replyLocalized(context, messages,
                        services.economy().getBalance(identity.uuid(), currency.id()),
                        value -> balanceComponent(messages, currency, value, identity.name(), false));
            }
        });
    }

    private static net.kyori.adventure.text.Component balanceComponent(
            com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
            CurrencyInfo currency, com.smile.aceeconomy.domain.Amount value,
            String playerName, boolean isSelf) {
        String amountStr = CommandFormat.formatAmount(messages, currency, value);
        if (messages == null) {
            return net.kyori.adventure.text.Component.text("economy.balance-check:" + playerName + ":" + amountStr);
        }
        boolean isDefault = currency.isDefault();
        if (isSelf) {
            if (isDefault) {
                return messages.renderMessage("economy.balance-check", java.util.Map.of("balance", amountStr));
            } else {
                return messages.renderMessage("economy.balance-check-currency",
                        java.util.Map.of("balance", amountStr, "currency_name", currency.displayName()));
            }
        } else {
            if (isDefault) {
                return messages.renderMessage("economy.balance-check-other",
                        java.util.Map.of("player", playerName, "balance", amountStr));
            } else {
                return messages.renderMessage("economy.balance-check-currency-other",
                        java.util.Map.of("player", playerName, "balance", amountStr, "currency_name", currency.displayName()));
            }
        }
    }

    private static List<String> complete(CommandServices services, List<String> args) {
        if (args.size() >= 3) {
            return CommandCompletion.byPrefix(services.economy().knownCurrencyIds(), CommandCompletion.last(args));
        }
        return CommandCompletion.byPrefix(services.players().onlinePlayerNames(), CommandCompletion.last(args));
    }
}
