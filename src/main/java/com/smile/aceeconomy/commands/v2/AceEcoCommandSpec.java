package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.SubCommandSpec;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.operations.AuditPage;
import com.smile.aceeconomy.operations.AuditQuery;
import com.smile.aceeconomy.operations.BackupResult;
import com.smile.aceeconomy.operations.RestoreResult;
import com.smile.aceeconomy.operations.RollbackResult;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Map;
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
        var messages = services.messages();
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
                        .handler(context -> V2CommandSupport.replyLocalized(context, messages,
                                services.admin().reload(),
                                ignored -> messages != null
                                        ? messages.renderMessage("general.reload-success", Map.of())
                                        : Component.text("general.reload-success")))
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
        var messages = services.messages();
        List<String> args = context.commandArgs();
        String label = args.isEmpty() ? null : V2CommandSupport.arg(messages, context, 0);
        CompletableFuture<BackupResult> pending;
        try {
            pending = services.backupRestore().createBackup(label);
        } catch (RuntimeException e) {
            V2CommandSupport.replyFailure(context, e);
            return;
        }
        if (pending == null) {
            String msg = messages != null ? messages.plainMessage("command.empty-future", Map.of()) : "command.empty-future";
            V2CommandSupport.replyFailure(context, CommandException.custom("ACELIB-CMD-EMPTY-FUTURE", msg));
            return;
        }
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (result == null) {
                String msg = messages != null ? messages.plainMessage("command.empty-result", Map.of()) : "command.empty-result";
                V2CommandSupport.replyFailure(context, CommandException.custom("ACELIB-CMD-EMPTY-RESULT", msg));
            } else if (!result.isSuccess()) {
                V2CommandSupport.replyFailure(context, BackupErrors.from(messages, result));
            } else {
                Component comp = messages != null
                        ? messages.renderMessage("admin.backup-success", Map.of("backup_id", result.backupId()))
                        : Component.text(result.backupId());
                CommandReply.replyComponent(context, messages, comp);
            }
        });
    }

    private static void executeRestore(CommandServices services, CommandContext context) {
        var messages = services.messages();
        String backupId = V2CommandSupport.arg(messages, context, 0);
        String confirmation = V2CommandSupport.arg(messages, context, 1);
        if (!"confirm".equals(confirmation)) {
            String msg = messages != null
                    ? messages.plainMessage("command.restore-confirm-required", Map.of("backup_id", backupId))
                    : "command.restore-confirm-required";
            throw CommandException.custom("ACELIB-CMD-RESTORE-CONFIRM-REQUIRED", msg);
        }
        CompletableFuture<RestoreResult> pending;
        try {
            pending = services.backupRestore().restore(backupId);
        } catch (RuntimeException e) {
            V2CommandSupport.replyFailure(context, e);
            return;
        }
        if (pending == null) {
            String msg = messages != null ? messages.plainMessage("command.empty-future", Map.of()) : "command.empty-future";
            V2CommandSupport.replyFailure(context, CommandException.custom("ACELIB-CMD-EMPTY-FUTURE", msg));
            return;
        }
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (result == null) {
                String msg = messages != null ? messages.plainMessage("command.empty-result", Map.of()) : "command.empty-result";
                V2CommandSupport.replyFailure(context, CommandException.custom("ACELIB-CMD-EMPTY-RESULT", msg));
            } else if (!result.isSuccess()) {
                V2CommandSupport.replyFailure(context, BackupErrors.from(messages, result));
            } else {
                Component comp = messages != null
                        ? messages.renderMessage("admin.restore-success",
                                Map.of("backup_id", result.restoredBackupId(), "safety_id", result.safetyBackupId()))
                        : Component.text(result.restoredBackupId() + " " + result.safetyBackupId());
                CommandReply.replyComponent(context, messages, comp);
            }
        });
    }

    private static void executeRollback(CommandServices services, CommandContext context) {
        var messages = services.messages();
        UUID transactionId = parseTransactionId(messages, V2CommandSupport.arg(messages, context, 0));
        CompletableFuture<RollbackResult> pending;
        try {
            pending = services.rollback().rollback(transactionId);
        } catch (RuntimeException e) {
            V2CommandSupport.replyFailure(context, e);
            return;
        }
        if (pending == null) {
            String msg = messages != null ? messages.plainMessage("command.empty-future", Map.of()) : "command.empty-future";
            V2CommandSupport.replyFailure(context, CommandException.custom("ACELIB-CMD-EMPTY-FUTURE", msg));
            return;
        }
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (result == null) {
                String msg = messages != null ? messages.plainMessage("command.empty-result", Map.of()) : "command.empty-result";
                V2CommandSupport.replyFailure(context, CommandException.custom("ACELIB-CMD-EMPTY-RESULT", msg));
            } else if (!result.isSuccess()) {
                V2CommandSupport.replyFailure(context, RollbackErrors.from(messages, result));
            } else if (result.isAlreadyReverted()) {
                Component comp = messages != null
                        ? messages.renderMessage("rollback.already-reverted",
                                Map.of("transaction_id", transactionId.toString()))
                        : Component.text("rollback.already-reverted:" + transactionId);
                CommandReply.replyComponent(context, messages, comp);
            } else {
                List<UUID> reversalIds = result.reversalTransactionIds();
                Component comp = messages != null
                        ? messages.renderMessage("rollback.success",
                                Map.of("transaction_id", transactionId.toString(),
                                        "count", String.valueOf(reversalIds.size()),
                                        "ids", reversalIds.toString()))
                        : Component.text("rollback.success:" + transactionId + ":" + reversalIds);
                CommandReply.replyComponent(context, messages, comp);
            }
        });
    }

    private static UUID parseTransactionId(ConfigLangAdapter messages, String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            String msg = messages != null
                    ? messages.plainMessage("command.invalid-uuid", Map.of("raw", raw))
                    : "command.invalid-uuid";
            throw CommandException.custom("ACELIB-CMD-INVALID-UUID", msg);
        }
    }

    private static void executeHistory(CommandServices services, CommandContext context) {
        var messages = services.messages();
        List<String> args = context.commandArgs();
        String playerName = args.isEmpty() ? null : V2CommandSupport.arg(messages, context, 0);
        String rawCurrency = args.size() >= 2 ? V2CommandSupport.arg(messages, context, 1) : null;
        int page = args.size() >= 3 ? parsePage(messages, V2CommandSupport.arg(messages, context, 2)) : 0;
        CurrencyInfo currency = V2CommandSupport.currency(services, rawCurrency);
        if (playerName == null) {
            queryAndReply(services, context, null, currency, page);
            return;
        }
        services.players().resolve(playerName).whenComplete((target, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (target.isEmpty()) {
                String msg = messages != null
                        ? messages.plainMessage("general.player-not-found", Map.of("player", playerName))
                        : "general.player-not-found";
                CommandReply.replyError(context, CommandException.custom("ACELIB-CMD-ACCOUNT-NOT-FOUND", msg));
            } else {
                queryAndReply(services, context, target.get(), currency, page);
            }
        });
    }

    private static void queryAndReply(CommandServices services, CommandContext context,
                                      PlayerIdentity target, CurrencyInfo currency, int page) {
        var messages = services.messages();
        AuditQuery query = AuditQuery.builder()
                .accountId(target == null ? null : target.uuid())
                .currencyId(currency.id())
                .page(page)
                .limit(HISTORY_PAGE_SIZE)
                .build();
        V2CommandSupport.replyValueLocalized(context, messages, services.history().query(query),
                value -> formatComponent(messages, target, currency, value));
    }

    private static int parsePage(ConfigLangAdapter messages, String raw) {
        try {
            int page = Integer.parseInt(raw);
            if (page >= 0) {
                return page;
            }
        } catch (NumberFormatException ignored) {
        }
        String msg = messages != null
                ? messages.plainMessage("command.invalid-page", Map.of("raw", raw))
                : "command.invalid-page";
        throw CommandException.custom("ACELIB-CMD-INVALID-PAGE", msg);
    }

    private static Component formatComponent(ConfigLangAdapter messages, PlayerIdentity target,
                                             CurrencyInfo currency, AuditPage result) {
        if (messages == null) {
            String who = target == null ? "" : ":" + target.name();
            if (result.entries().isEmpty()) {
                return Component.text("history.empty" + who + ":" + result.page() + ":" + result.total());
            }
            StringBuilder sb = new StringBuilder("history.header").append(who)
                    .append(":").append(currency.displayName()).append(":").append(result.page())
                    .append(":").append(result.total());
            int index = 1;
            for (Transaction tx : result.entries()) {
                sb.append('\n').append(index++).append(".").append(tx.type())
                        .append(':').append(CommandFormat.formatAmount(currency, tx.amount()))
                        .append(':').append(CommandFormat.formatAmount(currency, tx.balanceAfter()));
                if (tx.reason() != null && !tx.reason().isBlank()) {
                    sb.append(":").append(tx.reason());
                }
            }
            return Component.text(sb.toString());
        }
        if (result.entries().isEmpty()) {
            String who = target == null ? "" : target.name();
            if (who.isEmpty()) {
                return messages.renderMessage("history.empty", Map.of());
            }
            return messages.renderMessage("history.empty", Map.of("player", who));
        }
        // Header
        String who = target == null ? "" : target.name();
        Component header = who.isEmpty()
                ? messages.renderMessage("history.header", Map.of("player", "Server", "page", String.valueOf(result.page())))
                : messages.renderMessage("history.header", Map.of("player", who, "page", String.valueOf(result.page())));
        Component body = header;
        int index = 1;
        for (Transaction tx : result.entries()) {
            String amountStr = CommandFormat.formatAmount(messages, currency, tx.amount());
            String balanceStr = CommandFormat.formatAmount(messages, currency, tx.balanceAfter());
            Map<String, Object> vars = Map.of(
                    "time", tx.timestamp() == null ? "" : tx.timestamp().toString(),
                    "type", tx.type().toString(),
                    "amount", amountStr,
                    "currency", currency.displayName(),
                    "partner", tx.counterparty() == null ? "" : tx.counterparty().toString(),
                    "old_balance", balanceStr,
                    "new_balance", balanceStr);
            Component line = messages.renderMessage("history.entry-normal", vars);
            body = body.append(Component.text("\n")).append(Component.text(index++ + ". ")).append(line);
            if (tx.reason() != null && !tx.reason().isBlank()) {
                body = body.append(Component.text(" | ")).append(Component.text(tx.reason()));
            }
        }
        return body;
    }

    private static List<String> completeHistory(CommandServices services, List<String> args) {
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
        var messages = services.messages();
        String name = V2CommandSupport.arg(messages, context, 0);
        String rawCurrency = context.commandArgs().size() == 3 ? V2CommandSupport.arg(messages, context, 2) : null;
        CurrencyInfo currency = V2CommandSupport.currency(services, rawCurrency);
        var amount = V2CommandSupport.amount(messages, context, currency, 1);
        services.players().resolve(name).whenComplete((target, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, failure);
            } else if (target.isEmpty()) {
                String msg = messages != null
                        ? messages.plainMessage("general.player-not-found", Map.of("player", name))
                        : "general.player-not-found";
                CommandReply.replyError(context, CommandException.custom("ACELIB-CMD-ACCOUNT-NOT-FOUND", msg));
            } else {
                PlayerIdentity identity = target.get();
                var result = switch (operation) {
                    case "give" -> services.admin().give(identity.uuid(), currency.id(), amount);
                    case "take" -> services.admin().take(identity.uuid(), currency.id(), amount);
                    case "set" -> services.admin().setBalance(identity.uuid(), currency.id(), amount);
                    default -> throw new IllegalArgumentException("unsupported operation: " + operation);
                };
                V2CommandSupport.replyLocalized(context, messages, result, ignored -> {
                    String amountStr = CommandFormat.formatAmount(messages, currency, amount);
                    boolean isDefault = currency.isDefault();
                    String key;
                    if ("give".equals(operation)) {
                        key = isDefault ? "admin.give" : "admin.give-currency";
                    } else if ("take".equals(operation)) {
                        key = isDefault ? "admin.take" : "admin.take-currency";
                    } else {
                        key = isDefault ? "admin.set" : "admin.set-currency";
                    }
                    Map<String, Object> vars = isDefault
                            ? Map.of("player", identity.name(), "amount", amountStr)
                            : Map.of("player", identity.name(), "amount", amountStr, "currency_name", currency.displayName());
                    return messages != null ? messages.renderMessage(key, vars) : Component.text(identity.name() + " " + amountStr);
                });
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
