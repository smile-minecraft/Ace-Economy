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
        var messages = services.messages();
        String rawCurrency = context.commandArgs().isEmpty() ? null : V2CommandSupport.arg(messages, context, 0);
        CurrencyInfo currency = V2CommandSupport.currency(services, rawCurrency);
        V2CommandSupport.replyValueLocalized(context, messages,
                services.leaderboard().top(currency.id(), 1, services.leaderboard().pageSize()),
                value -> formatComponent(messages, currency, value));
    }

    private static net.kyori.adventure.text.Component formatComponent(
            com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
            CurrencyInfo currency, Object raw) {
        @SuppressWarnings("unchecked")
        List<LeaderboardEntry> entries = (List<LeaderboardEntry>) raw;
        if (entries.isEmpty()) {
            if (messages == null) {
                return net.kyori.adventure.text.Component.text("baltop.empty-currency:" + currency.displayName());
            }
            return messages.renderMessage("baltop.empty-currency",
                    java.util.Map.of("currency_name", currency.displayName()));
        }
        if (messages == null) {
            return net.kyori.adventure.text.Component.text(entries.stream()
                    .map(entry -> "baltop.entry:" + entry.rank() + ":" + entry.name() + ":" + currency.symbol()
                            + entry.balance().toPlainString())
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        // Build multi-line component via baltop.entry template per line, joined with newline
        net.kyori.adventure.text.Component result = net.kyori.adventure.text.Component.empty();
        boolean first = true;
        for (LeaderboardEntry entry : entries) {
            String amountStr = currency.symbol() + entry.balance().toPlainString();
            net.kyori.adventure.text.Component line = messages.renderMessage("baltop.entry",
                    java.util.Map.of("rank", String.valueOf(entry.rank()),
                            "player", entry.name(),
                            "amount", amountStr));
            if (!first) {
                result = result.append(net.kyori.adventure.text.Component.text("\n"));
            }
            result = result.append(line);
            first = false;
        }
        return result;
    }
}
