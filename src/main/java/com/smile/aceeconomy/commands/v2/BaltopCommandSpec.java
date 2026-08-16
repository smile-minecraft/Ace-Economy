package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.SubCommandSpec;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.LeaderboardEntry;

import java.util.List;
import java.util.stream.Collectors;

/** AceLib spec for deterministic leaderboard queries exposed as {@code /baltop top}. */
public final class BaltopCommandSpec {

    private BaltopCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        SubCommandSpec top = SubCommandSpec.builder("top")
                .description("Show the richest players")
                .usage("[currency]")
                .permission("aceeconomy.command.baltop")
                .minArgs(0)
                .maxArgs(1)
                .args("currency")
                .handler(context -> execute(services, context))
                .completer((context, args) -> CommandCompletion.byPrefix(
                        services.economy().knownCurrencyIds(), CommandCompletion.last(args)))
                .build();
        return CommandSpec.builder("baltop")
                .description("Show the balance leaderboard")
                .usage("/baltop top [currency]")
                .permission("aceeconomy.command.baltop")
                .subCommand(top)
                .build();
    }

    private static void execute(CommandServices services, CommandContext context) {
        String rawCurrency = context.commandArgs().isEmpty() ? null : V2CommandSupport.arg(context, 0);
        CurrencyInfo currency = V2CommandSupport.currency(services, rawCurrency);
        V2CommandSupport.replyValue(context, services.leaderboard().top(currency.id(), 1, services.leaderboard().pageSize()),
                value -> format(currency, value));
    }

    private static String format(CurrencyInfo currency, Object raw) {
        @SuppressWarnings("unchecked")
        List<LeaderboardEntry> entries = (List<LeaderboardEntry>) raw;
        if (entries.isEmpty()) {
            return "No balances found for " + currency.displayName();
        }
        return entries.stream()
                .map(entry -> entry.rank() + ". " + entry.name() + " " + currency.symbol()
                        + entry.balance().toPlainString())
                .collect(Collectors.joining("\n"));
    }
}
