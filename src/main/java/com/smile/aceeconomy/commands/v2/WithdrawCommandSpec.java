package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.SubCommandSpec;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.WithdrawReceipt;

import java.util.List;

/** AceLib spec for banknote withdrawal exposed as {@code /withdraw cash}. */
public final class WithdrawCommandSpec {

    private WithdrawCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        SubCommandSpec cash = SubCommandSpec.builder("cash")
                .description("Withdraw a banknote")
                .usage("<amount> [currency]")
                .permission("aceeconomy.command.withdraw")
                .playerOnly()
                .minArgs(1)
                .maxArgs(2)
                .args("amount", "currency")
                .handler(context -> execute(services, context))
                .completer((context, args) -> CommandCompletion.byPrefix(
                        services.economy().knownCurrencyIds(), CommandCompletion.last(args)))
                .build();
        return CommandSpec.builder("withdraw")
                .description("Withdraw funds as a banknote")
                .usage("/withdraw cash <amount> [currency]")
                .permission("aceeconomy.command.withdraw")
                .subCommand(cash)
                .build();
    }

    private static void execute(CommandServices services, CommandContext context) {
        var messages = services.messages();
        String currencyId = context.commandArgs().size() == 2 ? V2CommandSupport.arg(messages, context, 1) : null;
        CurrencyInfo currency = V2CommandSupport.currency(services, currencyId);
        var amount = V2CommandSupport.amount(messages, context, currency, 0);
        var player = context.requireOnlinePlayer();
        V2CommandSupport.replyLocalized(context, messages,
                services.withdrawals().withdraw(player.getUniqueId(), currency.id(), amount),
                value -> receiptComponent(messages, currency, value));
    }

    private static net.kyori.adventure.text.Component receiptComponent(
            com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
            CurrencyInfo currency, Object raw) {
        WithdrawReceipt receipt = (WithdrawReceipt) raw;
        String amountStr = CommandFormat.formatAmount(messages, currency,
                com.smile.aceeconomy.domain.Amount.of(receipt.value(), currency.scale()));
        if (messages == null) {
            return net.kyori.adventure.text.Component.text(
                    "economy.withdraw-note:" + amountStr + ":" + receipt.noteId());
        }
        return messages.renderMessage("economy.withdraw-note",
                java.util.Map.of("amount", amountStr, "note_id", receipt.noteId().toString()));
    }
}
