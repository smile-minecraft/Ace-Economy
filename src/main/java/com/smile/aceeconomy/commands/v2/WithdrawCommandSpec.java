package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedContext;
import com.smile.acelib.command.TypedSubCommand;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.WithdrawReceipt;

/** AceLib spec for banknote withdrawal exposed as {@code /withdraw cash}. */
public final class WithdrawCommandSpec {

    private WithdrawCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        CommandArgument<String> amount = CommandArguments.amountToken(services);
        CommandArgument<CurrencyInfo> currency = CommandArguments.currency(services);
        TypedSubCommand cash = TypedSubCommand.builder("cash")
                .description("Withdraw a banknote")
                .usage("<amount> [currency]")
                .permission("aceeconomy.command.withdraw")
                .playerOnly()
                .argument(amount)
                .optional(currency, sender -> CommandArguments.defaultCurrency(services))
                .executes(ctx -> execute(services, ctx, amount, currency))
                .build();
        return TypedCommand.builder("withdraw")
                .description("Withdraw funds as a banknote")
                .usage("/withdraw cash <amount> [currency]")
                .permission("aceeconomy.command.withdraw")
                .subcommand(cash)
                .build()
                .toCommandSpec();
    }

    private static void execute(CommandServices services, TypedContext context,
                                CommandArgument<String> amountArg,
                                CommandArgument<CurrencyInfo> currencyArg) {
        var messages = services.messages();
        CurrencyInfo currency = context.get(currencyArg);
        var amount = V2CommandSupport.amount(messages, context.get(amountArg), currency);
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
