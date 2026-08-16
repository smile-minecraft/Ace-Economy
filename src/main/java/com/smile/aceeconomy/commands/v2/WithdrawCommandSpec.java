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
        String currencyId = context.commandArgs().size() == 2 ? V2CommandSupport.arg(context, 1) : null;
        CurrencyInfo currency = V2CommandSupport.currency(services, currencyId);
        var amount = V2CommandSupport.amount(context, currency, 0);
        var player = context.requireOnlinePlayer();
        V2CommandSupport.reply(context,
                services.withdrawals().withdraw(player.getUniqueId(), currency.id(), amount),
                value -> receiptMessage(currency, value));
    }

    private static String receiptMessage(CurrencyInfo currency, Object raw) {
        WithdrawReceipt receipt = (WithdrawReceipt) raw;
        return "Withdrew " + currency.symbol() + receipt.value().toPlainString() + " as banknote " + receipt.noteId();
    }
}
