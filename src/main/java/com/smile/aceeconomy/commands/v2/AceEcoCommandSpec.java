package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.SubCommandSpec;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;

import java.util.List;

/** AceLib spec for administrative economy operations exposed as {@code /aceeco}. */
public final class AceEcoCommandSpec {

    private AceEcoCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        return CommandSpec.builder("aceeco")
                .description("Administrative economy commands")
                .usage("/aceeco <give|take|set|reload>")
                .permission("aceeconomy.admin")
                .subCommand(mutation(services, "give"))
                .subCommand(mutation(services, "take"))
                .subCommand(mutation(services, "set"))
                .subCommand(SubCommandSpec.builder("reload")
                        .description("Reload economy configuration")
                        .usage("")
                        .permission("aceeconomy.admin.reload")
                        .consoleOnly()
                        .minArgs(0)
                        .maxArgs(0)
                        .handler(context -> V2CommandSupport.reply(context, services.admin().reload(),
                                ignored -> "AceEconomy reloaded"))
                        .build())
                .build();
    }

    private static SubCommandSpec mutation(CommandServices services, String operation) {
        return SubCommandSpec.builder(operation)
                .description(operation + " a player's balance")
                .usage("<player> <amount> [currency]")
                .permission("aceeconomy.admin." + operation)
                .minArgs(2)
                .maxArgs(3)
                .args("player", "amount", "currency")
                .handler(context -> executeMutation(services, context, operation))
                .completer((context, args) -> complete(services, args))
                .build();
    }

    private static void executeMutation(CommandServices services, CommandContext context, String operation) {
        String name = V2CommandSupport.arg(context, 0);
        String rawCurrency = context.commandArgs().size() == 3 ? V2CommandSupport.arg(context, 2) : null;
        CurrencyInfo currency = V2CommandSupport.currency(services, rawCurrency);
        var amount = V2CommandSupport.amount(context, currency, 1);
        services.players().resolve(name).whenComplete((target, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (target.isEmpty()) {
                CommandReply.replyError(context, com.smile.acelib.command.CommandException.custom(
                        "ACELIB-CMD-ACCOUNT-NOT-FOUND", "unknown player: " + name));
            } else {
                PlayerIdentity identity = target.get();
                var result = switch (operation) {
                    case "give" -> services.admin().give(identity.uuid(), currency.id(), amount);
                    case "take" -> services.admin().take(identity.uuid(), currency.id(), amount);
                    case "set" -> services.admin().setBalance(identity.uuid(), currency.id(), amount);
                    default -> throw new IllegalArgumentException("unsupported operation: " + operation);
                };
                V2CommandSupport.reply(context, result,
                        ignored -> operation + " completed for " + identity.name());
            }
        });
    }

    private static List<String> complete(CommandServices services, List<String> args) {
        if (args.size() >= 3) {
            return CommandCompletion.byPrefix(services.economy().knownCurrencyIds(), CommandCompletion.last(args));
        }
        return CommandCompletion.byPrefix(services.players().onlinePlayerNames(), CommandCompletion.last(args));
    }
}
