package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedContext;
import com.smile.acelib.command.TypedSubCommand;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.LeaderboardEntry;

import java.util.List;

/** AceLib spec for deterministic leaderboard queries exposed as {@code /baltop top}. */
public final class BaltopCommandSpec {

    private BaltopCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        CommandArgument<CurrencyInfo> currency = CommandArguments.currency(services);
        TypedSubCommand top = TypedSubCommand.builder("top")
                .description("Show the richest players")
                .usage("[currency]")
                .permission("aceeconomy.command.baltop")
                .optional(currency, sender -> CommandArguments.defaultCurrency(services))
                .executes(ctx -> execute(services, ctx, currency))
                .build();
        return TypedCommand.builder("baltop")
                .description("Show the balance leaderboard")
                .usage("/baltop top [currency]")
                .permission("aceeconomy.command.baltop")
                .subcommand(top)
                .build()
                .toCommandSpec();
    }

    private static void execute(CommandServices services, TypedContext context,
                                CommandArgument<CurrencyInfo> currencyArg) {
        var messages = services.messages();
        CurrencyInfo currency = context.get(currencyArg);
        V2CommandSupport.replyValueLocalized(context, messages,
                services.leaderboard().top(currency.id(), 0, services.leaderboard().pageSize()),
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
