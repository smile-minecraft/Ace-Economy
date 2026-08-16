package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.SubCommandCompleter;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.domain.EconomyResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class V2CommandSupport {

    private V2CommandSupport() {
    }

    static String arg(CommandContext context, int index) {
        List<String> args = context.commandArgs();
        if (index < 0 || index >= args.size() || args.get(index) == null || args.get(index).isBlank()) {
            throw CommandException.custom("ACELIB-CMD-MISSING-ARGUMENT", "argument " + (index + 1) + " is required");
        }
        return args.get(index).trim();
    }

    static CurrencyInfo currency(CommandServices services, String raw) {
        return CurrencyArgResolver.resolve(services.economy(), raw, services.economy().defaultCurrencyId());
    }

    static com.smile.aceeconomy.domain.Amount amount(CommandContext context, CurrencyInfo currency, int index) {
        return AmountParser.parse(arg(context, index), currency.scale());
    }

    static <T> void reply(CommandContext context, CompletableFuture<? extends EconomyResult<T>> future,
                          Function<T, String> successMessage) {
        Objects.requireNonNull(future, "future").whenComplete((result, failure) -> {
            if (failure != null) {
                CommandReply.replyError(context, unwrap(failure));
            } else if (result == null) {
                CommandReply.replyError(context, CommandException.custom(
                        "ACELIB-CMD-EMPTY-RESULT", "command service returned no result"));
            } else if (result.isFailure()) {
                CommandReply.replyError(context, TypedErrors.from(result));
            } else if (result.auditFailure().isPresent()) {
                CommandReply.replyError(context, CommandException.custom(
                        "ACELIB-CMD-AUDIT-FAILURE", "command completed but audit recording failed"));
            } else {
                CommandReply.reply(context, successMessage.apply(result.value()));
            }
        });
    }

    static <T> void replyValue(CommandContext context, CompletableFuture<T> future,
                               Function<T, String> successMessage) {
        Objects.requireNonNull(future, "future").whenComplete((value, failure) -> {
            if (failure != null) {
                CommandReply.replyError(context, unwrap(failure));
            } else {
                CommandReply.reply(context, successMessage.apply(value));
            }
        });
    }

    static void replySuccess(CommandContext context, String message) {
        CommandReply.reply(context, message);
    }

    static void replyFailure(CommandContext context, Throwable failure) {
        CommandReply.replyError(context, unwrap(failure));
    }

    static SubCommandCompleter currencies(CommandServices services) {
        return (context, args) -> CommandCompletion.byPrefix(
                services.economy().knownCurrencyIds(), CommandCompletion.last(args));
    }

    static SubCommandCompleter players(CommandServices services) {
        return (context, args) -> CommandCompletion.byPrefix(
                services.players().onlinePlayerNames(), CommandCompletion.last(args));
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return failure;
    }
}
