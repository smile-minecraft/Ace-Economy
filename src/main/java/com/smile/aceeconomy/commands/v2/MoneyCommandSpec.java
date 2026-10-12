package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedContext;
import com.smile.acelib.command.TypedSubCommand;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;

import java.util.List;

/** AceLib spec for balance queries and self history exposed as {@code /money}. */
public final class MoneyCommandSpec {

    private MoneyCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        CommandArgument<String> player = CommandArguments.optionalPlayerName(services);
        CommandArgument<CurrencyInfo> currency = CommandArguments.currency(services);
        TypedSubCommand balance = TypedSubCommand.builder("balance")
                .description("Show a player's balance")
                .usage("[player] [currency]")
                .permission("aceeconomy.command.money")
                .optional(player, sender -> "")
                .optional(currency, sender -> CommandArguments.defaultCurrency(services))
                .executes(ctx -> execute(services, ctx, player, currency))
                .build();
        CommandArgument<CurrencyInfo> historyCurrency = CommandArguments.currency(services);
        CommandArgument<Integer> historyPage = CommandArguments.page(services);
        TypedSubCommand history = TypedSubCommand.builder("history")
                .description("Show your own transaction history")
                .usage("[currency] [page]")
                .permission("aceeconomy.command.history")
                .playerOnly()
                .optional(historyCurrency, sender -> CommandArguments.defaultCurrency(services))
                .optional(historyPage, sender -> 0)
                .executes(ctx -> executeHistory(services, ctx, historyCurrency, historyPage))
                .build();
        return TypedCommand.builder("money")
                .aliases("balance")
                .description("View an economy balance")
                .usage("/money balance [player] [currency]")
                .permission("aceeconomy.command.money")
                .subcommand(balance)
                .subcommand(history)
                .build()
                .toCommandSpec();
    }

    private static void execute(CommandServices services, TypedContext context,
                                CommandArgument<String> playerArg,
                                CommandArgument<CurrencyInfo> currencyArg) {
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
            CurrencyInfo currency = context.get(currencyArg);
            V2CommandSupport.replyLocalized(context, messages,
                    services.economy().getBalance(player.getUniqueId(), currency.id()),
                    value -> balanceComponent(messages, currency, value, player.getName(), true));
            return;
        }
        String playerName = context.get(playerArg);
        CurrencyInfo currency = context.get(currencyArg);
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

    /**
     * Player self-history: the queried account is always the executor. There is
     * deliberately no player argument, so no token can ever address another
     * account — an unknown first token fails as an unknown currency at parse
     * time. Like the balance path, a trailing surplus is rejected explicitly
     * instead of being silently ignored.
     */
    private static void executeHistory(CommandServices services, TypedContext context,
                                       CommandArgument<CurrencyInfo> currencyArg,
                                       CommandArgument<Integer> pageArg) {
        var messages = services.messages();
        List<String> args = context.commandArgs();
        if (args.size() > 2) {
            String msg = messages != null
                    ? messages.plainMessage("command.missing-argument", java.util.Map.of("index", "3"))
                    : "command.missing-argument";
            throw com.smile.acelib.command.CommandException.custom("ACELIB-CMD-INVALID-ARGUMENTS", msg);
        }
        var player = context.requireOnlinePlayer();
        CurrencyInfo currency = context.get(currencyArg);
        int page = context.get(pageArg);
        PlayerIdentity self = new PlayerIdentity(player.getUniqueId(), player.getName(), true);
        HistoryReply.queryAndReply(services, context, self, currency, page);
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
}
