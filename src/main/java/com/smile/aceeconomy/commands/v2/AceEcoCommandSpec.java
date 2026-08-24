package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.SubCommandSpec;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.operations.AuditPage;
import com.smile.aceeconomy.operations.AuditQuery;
import com.smile.aceeconomy.operations.BackupResult;
import com.smile.aceeconomy.operations.RestoreResult;
import com.smile.aceeconomy.operations.RollbackResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** AceLib spec for administrative economy operations exposed as {@code /aceeco}. */
public final class AceEcoCommandSpec {

    /** Fixed page size for history replies; pagination stays 0-based like {@code AuditQuery}. */
    private static final int HISTORY_PAGE_SIZE = 10;

    private AceEcoCommandSpec() {
    }

    public static CommandSpec create(CommandServices services) {
        return create(services, java.util.List.of());
    }

    /**
     * Alias-aware variant used by the startup composition. Entries are normalized again as a
     * second line of defense; blank entries and the primary name itself are dropped so the
     * default entry point is never duplicated as an alias of its own spec.
     */
    public static CommandSpec create(CommandServices services, java.util.Collection<String> aliases) {
        java.util.List<String> effective = aliases == null ? java.util.List.of()
                : aliases.stream()
                        .filter(alias -> alias != null && !alias.isBlank())
                        .map(alias -> alias.trim().toLowerCase(java.util.Locale.ROOT))
                        .filter(alias -> !alias.equals("aceeco"))
                        .distinct()
                        .toList();
        CommandSpec.Builder builder = CommandSpec.builder("aceeco")
                .description("Administrative economy commands")
                .usage("/aceeco <give|take|set|history|reload|rollback|backup|restore>")
                .permission("aceeconomy.admin")
                .subCommand(mutation(services, "give"))
                .subCommand(mutation(services, "take"))
                .subCommand(mutation(services, "set"))
                .subCommand(history(services))
                .subCommand(rollback(services))
                .subCommand(backup(services))
                .subCommand(restore(services))
                .subCommand(SubCommandSpec.builder("reload")
                        .description("Reload economy configuration")
                        .usage("")
                        .permission("aceeconomy.admin.reload")
                        .consoleOnly()
                        .minArgs(0)
                        .maxArgs(0)
                        .handler(context -> V2CommandSupport.reply(context, services.admin().reload(),
                                ignored -> "AceEconomy reloaded"))
                        .build());
        if (!effective.isEmpty()) {
            builder.aliases(effective.toArray(String[]::new));
        }
        return builder.build();
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

    private static SubCommandSpec history(CommandServices services) {
        return SubCommandSpec.builder("history")
                .description("Query the transaction history")
                .usage("[player] [currency] [page]")
                .permission("aceeconomy.admin.history")
                .minArgs(0)
                .maxArgs(3)
                .args("player", "currency", "page")
                .handler(context -> executeHistory(services, context))
                .completer((context, args) -> completeHistory(services, args))
                .build();
    }

    private static SubCommandSpec rollback(CommandServices services) {
        return SubCommandSpec.builder("rollback")
                .description("Roll back a transaction by id (destructive, console only)")
                .usage("<transaction-id>")
                .permission("aceeconomy.admin.rollback")
                .consoleOnly()
                .minArgs(1)
                .maxArgs(1)
                .args("transaction-id")
                .handler(context -> executeRollback(services, context))
                .build();
    }

    private static SubCommandSpec backup(CommandServices services) {
        return SubCommandSpec.builder("backup")
                .description("Create a logical v2 snapshot under the managed backups folder")
                .usage("[label]")
                .permission("aceeconomy.admin.backup")
                .minArgs(0)
                .maxArgs(1)
                .args("label")
                .handler(context -> executeBackup(services, context))
                .build();
    }

    private static SubCommandSpec restore(CommandServices services) {
        return SubCommandSpec.builder("restore")
                .description("Restore live economy state from a backup (destructive, console only)")
                .usage("<backup-id> confirm")
                .permission("aceeconomy.admin.restore")
                .consoleOnly()
                .minArgs(2)
                .maxArgs(2)
                .args("backup-id", "confirm")
                .handler(context -> executeRestore(services, context))
                .build();
    }

    private static void executeBackup(CommandServices services, CommandContext context) {
        List<String> args = context.commandArgs();
        String label = args.isEmpty() ? null : V2CommandSupport.arg(context, 0);
        CompletableFuture<BackupResult> pending;
        try {
            pending = services.backupRestore().createBackup(label);
        } catch (RuntimeException e) {
            // A synchronous facade failure must reach the typed error reply itself instead of
            // surfacing as the dispatcher's generic execution-failed fallback.
            V2CommandSupport.replyFailure(context, e);
            return;
        }
        if (pending == null) {
            V2CommandSupport.replyFailure(context, CommandException.custom(
                    "ACELIB-CMD-EMPTY-FUTURE", "command service returned no async result"));
            return;
        }
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (result == null) {
                V2CommandSupport.replyFailure(context, CommandException.custom(
                        "ACELIB-CMD-EMPTY-RESULT", "command service returned no result"));
            } else if (!result.isSuccess()) {
                V2CommandSupport.replyFailure(context, BackupErrors.from(result));
            } else {
                V2CommandSupport.replySuccess(context, "Backup created: " + result.backupId()
                        + " (logical v2 snapshot)");
            }
        });
    }

    private static void executeRestore(CommandServices services, CommandContext context) {
        String backupId = V2CommandSupport.arg(context, 0);
        String confirmation = V2CommandSupport.arg(context, 1);
        // The confirmation word is matched exactly and case-sensitively: a destructive
        // restore must never be unlocked by CONFIRM, Confirm or any other spelling.
        if (!"confirm".equals(confirmation)) {
            throw CommandException.custom("ACELIB-CMD-RESTORE-CONFIRM-REQUIRED",
                    "restore is destructive; re-run exactly as: /aceeco restore <backup-id> confirm");
        }
        CompletableFuture<RestoreResult> pending;
        try {
            pending = services.backupRestore().restore(backupId);
        } catch (RuntimeException e) {
            V2CommandSupport.replyFailure(context, e);
            return;
        }
        if (pending == null) {
            V2CommandSupport.replyFailure(context, CommandException.custom(
                    "ACELIB-CMD-EMPTY-FUTURE", "command service returned no async result"));
            return;
        }
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (result == null) {
                V2CommandSupport.replyFailure(context, CommandException.custom(
                        "ACELIB-CMD-EMPTY-RESULT", "command service returned no result"));
            } else if (!result.isSuccess()) {
                V2CommandSupport.replyFailure(context, BackupErrors.from(result));
            } else {
                // No session hot-refresh is claimed: the no-online-player gate plus this
                // explicit restart demand are the documented safety boundary.
                V2CommandSupport.replySuccess(context, "Restored backup "
                        + result.restoredBackupId() + "; safety backup " + result.safetyBackupId()
                        + "; leaderboard cache cleared. Restart the server before players join.");
            }
        });
    }

    private static void executeRollback(CommandServices services, CommandContext context) {
        UUID transactionId = parseTransactionId(V2CommandSupport.arg(context, 0));
        CompletableFuture<RollbackResult> pending;
        try {
            pending = services.rollback().rollback(transactionId);
        } catch (RuntimeException e) {
            // A synchronous facade failure must reach the typed error reply itself instead of
            // surfacing as the dispatcher's generic execution-failed fallback.
            V2CommandSupport.replyFailure(context, e);
            return;
        }
        if (pending == null) {
            // Distinct from ACELIB-CMD-EMPTY-RESULT: nothing was executed at all, so there is
            // no result object to inspect — and never a success reply.
            V2CommandSupport.replyFailure(context, CommandException.custom(
                    "ACELIB-CMD-EMPTY-FUTURE", "command service returned no async result"));
            return;
        }
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (result == null) {
                V2CommandSupport.replyFailure(context, CommandException.custom(
                        "ACELIB-CMD-EMPTY-RESULT", "command service returned no result"));
            } else if (!result.isSuccess()) {
                V2CommandSupport.replyFailure(context, RollbackErrors.from(result));
            } else if (result.isAlreadyReverted()) {
                // Idempotent no-op: the reversal is already durable, so this reply must not
                // read like a freshly executed rollback.
                V2CommandSupport.replySuccess(context, "Transaction " + transactionId
                        + " was already reverted; no changes made");
            } else {
                List<UUID> reversalIds = result.reversalTransactionIds();
                V2CommandSupport.replySuccess(context, "Rolled back transaction " + transactionId
                        + "; reversal audit records (" + reversalIds.size() + "): " + reversalIds);
            }
        });
    }

    private static UUID parseTransactionId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            throw CommandException.custom("ACELIB-CMD-INVALID-UUID",
                    "transaction id must be a valid UUID: " + raw);
        }
    }

    private static void executeHistory(CommandServices services, CommandContext context) {
        List<String> args = context.commandArgs();
        String playerName = args.isEmpty() ? null : V2CommandSupport.arg(context, 0);
        String rawCurrency = args.size() >= 2 ? V2CommandSupport.arg(context, 1) : null;
        int page = args.size() >= 3 ? parsePage(V2CommandSupport.arg(context, 2)) : 0;
        CurrencyInfo currency = V2CommandSupport.currency(services, rawCurrency);
        if (playerName == null) {
            queryAndReply(services, context, null, currency, page);
            return;
        }
        services.players().resolve(playerName).whenComplete((target, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (target.isEmpty()) {
                CommandReply.replyError(context, CommandException.custom(
                        "ACELIB-CMD-ACCOUNT-NOT-FOUND", "unknown player: " + playerName));
            } else {
                queryAndReply(services, context, target.get(), currency, page);
            }
        });
    }

    private static void queryAndReply(CommandServices services, CommandContext context,
                                      PlayerIdentity target, CurrencyInfo currency, int page) {
        AuditQuery query = AuditQuery.builder()
                .accountId(target == null ? null : target.uuid())
                .currencyId(currency.id())
                .page(page)
                .limit(HISTORY_PAGE_SIZE)
                .build();
        V2CommandSupport.replyValue(context, services.history().query(query),
                value -> format(target, currency, value));
    }

    private static int parsePage(String raw) {
        try {
            int page = Integer.parseInt(raw);
            if (page >= 0) {
                return page;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the typed error below
        }
        throw CommandException.custom("ACELIB-CMD-INVALID-PAGE",
                "page must be a non-negative integer: " + raw);
    }

    private static String format(PlayerIdentity target, CurrencyInfo currency, AuditPage result) {
        String who = target == null ? "" : " for " + target.name();
        if (result.entries().isEmpty()) {
            return "History" + who + ": No transactions found (page " + result.page()
                    + ", total " + result.total() + ")";
        }
        long pages = Math.max(1L, (result.total() + result.limit() - 1) / result.limit());
        StringBuilder sb = new StringBuilder("History").append(who)
                .append(" (").append(currency.displayName()).append(") page ")
                .append(result.page()).append('/').append(pages)
                .append(", total ").append(result.total());
        int index = 1;
        for (Transaction tx : result.entries()) {
            sb.append('\n').append(index++).append(". ").append(tx.type())
                    .append(' ').append(CommandFormat.formatAmount(currency, tx.amount()))
                    .append(" -> ").append(CommandFormat.formatAmount(currency, tx.balanceAfter()));
            if (tx.reason() != null && !tx.reason().isBlank()) {
                sb.append(" | ").append(tx.reason());
            }
        }
        return sb.toString();
    }

    private static List<String> completeHistory(CommandServices services, List<String> args) {
        // args[0] is the subcommand name; completing position 1 = player, 2 = currency,
        // 3 = page number (no completion candidates).
        int position = args.size() - 1;
        if (position == 2) {
            return CommandCompletion.byPrefix(
                    services.economy().knownCurrencyIds(), CommandCompletion.last(args));
        }
        if (position <= 1) {
            return CommandCompletion.byPrefix(
                    services.players().onlinePlayerNames(), CommandCompletion.last(args));
        }
        return List.of();
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
