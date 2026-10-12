package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedContext;
import com.smile.acelib.command.TypedSubCommand;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.operations.AuditPage;
import com.smile.aceeconomy.operations.BackupResult;
import com.smile.aceeconomy.operations.ImportOutcome;
import com.smile.aceeconomy.operations.RestoreResult;
import com.smile.aceeconomy.operations.RollbackResult;
import com.smile.aceeconomy.ports.operations.ImportSource;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** AceLib spec for administrative economy operations exposed as {@code /aceeco}. */
public final class AceEcoCommandSpec {

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
        TypedCommand.Builder builder = TypedCommand.builder("aceeco")
                .description("Administrative economy commands")
                .usage("/aceeco <give|take|set|history|reload|rollback|backup|restore|import>")
                .permission("aceeconomy.admin")
                .subcommand(mutation(services, "give"))
                .subcommand(mutation(services, "take"))
                .subcommand(mutation(services, "set"))
                .subcommand(history(services))
                .subcommand(rollback(services))
                .subcommand(backup(services))
                .subcommand(restore(services))
                .subcommand(importBalances(services))
                .subcommand(TypedSubCommand.builder("reload")
                        .description("Reload economy configuration")
                        .usage("")
                        .permission("aceeconomy.admin.reload")
                        .consoleOnly()
                        .executes(context -> reloadWithReport(context, services, messages))
                        .build());
        if (!effective.isEmpty()) {
            builder.aliases(effective.toArray(String[]::new));
        }
        return builder.build().toCommandSpec();
    }

    /**
     * Reload reply with a full transaction report. Failures carry the refusal reason
     * (currency danger, invalid candidate) verbatim instead of a generic type-mapped
     * error; successes append hot-applied and restart-deferred notes as plain text so
     * operator-controlled values are never re-parsed as MiniMessage.
     */
    private static void reloadWithReport(TypedContext context, CommandServices services,
                                         ConfigLangAdapter messages) {
        services.admin().reload().whenComplete((result, failure) -> {
            if (failure != null) {
                CommandReply.replyError(context, failure);
                return;
            }
            if (result == null) {
                String msg = messages != null
                        ? messages.plainMessage("command.empty-result", Map.of())
                        : "command.empty-result";
                CommandReply.replyError(context, CommandException.custom("ACELIB-CMD-EMPTY-RESULT", msg));
                return;
            }
            if (result.isFailure()) {
                String detail = result.message() == null ? "reload failed" : result.message();
                CommandReply.replyError(context,
                        CommandException.custom("ACELIB-CMD-RELOAD-FAILED", detail));
                return;
            }
            Component base = messages != null
                    ? messages.renderMessage("general.reload-success", Map.of())
                    : Component.text("general.reload-success");
            String notes = messages != null && messages.lastReloadResult() != null
                    ? messages.lastReloadResult().detail()
                    : "";
            Component reply = notes.isEmpty() ? base : base.append(Component.text("\n" + notes));
            CommandReply.replyComponent(context, messages, reply);
        });
    }

    private static TypedSubCommand mutation(CommandServices services, String operation) {
        CommandArgument<String> player = CommandArguments.playerName(services);
        CommandArgument<String> amount = CommandArguments.amountToken(services);
        CommandArgument<CurrencyInfo> currency = CommandArguments.currency(services);
        return TypedSubCommand.builder(operation)
                .description(operation + " a player's balance")
                .usage("<player> <amount> [currency]")
                .permission("aceeconomy.admin." + operation)
                .argument(player)
                .argument(amount)
                .optional(currency, sender -> CommandArguments.defaultCurrency(services))
                .executes(context -> executeMutation(services, context, operation, player, amount, currency))
                .build();
    }

    private static TypedSubCommand history(CommandServices services) {
        CommandArgument<String> player = CommandArguments.optionalPlayerName(services);
        CommandArgument<CurrencyInfo> currency = CommandArguments.currency(services);
        CommandArgument<Integer> page = CommandArguments.page(services);
        return TypedSubCommand.builder("history")
                .description("Query the transaction history")
                .usage("[player] [currency] [page]")
                .permission("aceeconomy.admin.history")
                .optional(player, sender -> "")
                .optional(currency, sender -> CommandArguments.defaultCurrency(services))
                .optional(page, sender -> 0)
                .executes(context -> executeHistory(services, context, player, currency, page))
                .build();
    }

    private static TypedSubCommand rollback(CommandServices services) {
        CommandArgument<UUID> transactionId = CommandArguments.transactionId(services);
        return TypedSubCommand.builder("rollback")
                .description("Roll back a transaction by id (destructive, console only)")
                .usage("<transaction-id>")
                .permission("aceeconomy.admin.rollback")
                .consoleOnly()
                .argument(transactionId)
                .executes(context -> executeRollback(services, context, transactionId))
                .build();
    }

    private static TypedSubCommand backup(CommandServices services) {
        CommandArgument<String> label = CommandArguments.token("label", "[label]");
        return TypedSubCommand.builder("backup")
                .description("Create a logical v2 snapshot under the managed backups folder")
                .usage("[label]")
                .permission("aceeconomy.admin.backup")
                .optional(label, sender -> "")
                .executes(context -> executeBackup(services, context, label))
                .build();
    }

    private static TypedSubCommand restore(CommandServices services) {
        CommandArgument<String> backupId = CommandArguments.token("backup-id", "<backup-id>");
        CommandArgument<String> confirm = CommandArguments.token("confirm", "<confirm>");
        return TypedSubCommand.builder("restore")
                .description("Restore live economy state from a backup (destructive, console only)")
                .usage("<backup-id> confirm")
                .permission("aceeconomy.admin.restore")
                .consoleOnly()
                .argument(backupId)
                .argument(confirm)
                .executes(context -> executeRestore(services, context, backupId, confirm))
                .build();
    }

    private static TypedSubCommand importBalances(CommandServices services) {
        CommandArgument<ImportSource> source = CommandArguments.importSource(services);
        CommandArgument<String> path = CommandArguments.importPath(services);
        CommandArgument<String> third = CommandArguments.importCurrencySlot();
        CommandArgument<String> fourth = CommandArguments.importApplySlot();
        CommandArgument<String> fifth = CommandArguments.importConfirmSlot();
        return TypedSubCommand.builder("import")
                .description("Preview or apply an Essentials/CMI balance import (console only, dry-run by default)")
                .usage("<essentials|cmi> <path> [currency] [apply confirm]")
                .permission("aceeconomy.admin.import")
                .consoleOnly()
                .argument(source)
                .argument(path)
                .optional(third, sender -> "")
                .optional(fourth, sender -> "")
                .optional(fifth, sender -> "")
                .executes(context -> executeImport(services, context, source, path))
                .build();
    }

    /**
     * Argument shapes (after {@code <source> <path>}): {@code []} and
     * {@code [currency]} are dry-run previews; {@code [apply confirm]} and
     * {@code [currency apply confirm]} are real applies. A lone
     * {@code apply} without {@code confirm} is rejected before the service
     * is touched. {@code confirm} must match exactly (lowercase), mirroring
     * the restore confirmation; {@code apply} is matched case-insensitively
     * unless it is a known currency id.
     */
    private static void executeImport(CommandServices services, TypedContext context,
                                      CommandArgument<ImportSource> sourceArg,
                                      CommandArgument<String> pathArg) {
        var messages = services.messages();
        ImportSource source = context.get(sourceArg);
        String path = context.get(pathArg);
        List<String> rest = context.commandArgs().subList(2, context.commandArgs().size());
        String rawCurrency = null;
        boolean apply = false;
        if (rest.isEmpty()) {
            // dry-run with the default currency
        } else if (rest.size() == 1) {
            String token = rest.get(0);
            if (isApplyToken(services, token)) {
                throw confirmRequired(messages, source, path);
            }
            rawCurrency = token;
        } else if (rest.size() == 2) {
            if (isApplyWord(rest.get(0)) && "confirm".equals(rest.get(1))) {
                apply = true;
            } else {
                throw confirmRequired(messages, source, path);
            }
        } else if (rest.size() == 3) {
            if (isApplyWord(rest.get(1)) && "confirm".equals(rest.get(2))) {
                rawCurrency = rest.get(0);
                apply = true;
            } else {
                throw confirmRequired(messages, source, path);
            }
        } else {
            throw confirmRequired(messages, source, path);
        }
        CurrencyInfo currency = V2CommandSupport.currency(services, rawCurrency);
        if (services.imports() == null) {
            String msg = messages != null ? messages.plainMessage("command.empty-result", Map.of())
                    : "command.empty-result";
            V2CommandSupport.replyFailure(context, CommandException.custom("ACELIB-CMD-EMPTY-RESULT", msg));
            return;
        }
        CompletableFuture<ImportOutcome> pending;
        try {
            pending = apply ? services.imports().apply(source, path, currency.id())
                    : services.imports().preview(source, path, currency.id());
        } catch (RuntimeException e) {
            V2CommandSupport.replyFailure(context, ImportErrors.from(messages, e));
            return;
        }
        if (pending == null) {
            String msg = messages != null ? messages.plainMessage("command.empty-future", Map.of())
                    : "command.empty-future";
            V2CommandSupport.replyFailure(context, CommandException.custom("ACELIB-CMD-EMPTY-FUTURE", msg));
            return;
        }
        var currencyForReply = currency;
        pending.whenComplete((result, failure) -> {
            if (failure != null) {
                V2CommandSupport.replyFailure(context, ImportErrors.from(messages, failure));
            } else if (result == null) {
                String msg = messages != null
                        ? messages.plainMessage("command.empty-result", Map.of())
                        : "command.empty-result";
                V2CommandSupport.replyFailure(context,
                        CommandException.custom("ACELIB-CMD-EMPTY-RESULT", msg));
            } else {
                CommandReply.replyComponent(context, messages,
                        formatImport(messages, source, currencyForReply, path, result));
            }
        });
    }

    private static boolean isApplyWord(String token) {
        return "apply".equalsIgnoreCase(token);
    }

    /**
     * A lone trailing {@code apply} means the operator started the write
     * path — unless a currency is literally named {@code apply}, in which
     * case the token stays a currency argument.
     */
    private static boolean isApplyToken(CommandServices services, String token) {
        if (!isApplyWord(token)) {
            return false;
        }
        try {
            return !services.economy().knownCurrencyIds().stream().anyMatch("apply"::equalsIgnoreCase);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static CommandException confirmRequired(ConfigLangAdapter messages,
                                                    ImportSource source, String path) {
        String msg = messages != null
                ? messages.plainMessage("command.import-confirm-required",
                        Map.of("source", source.name().toLowerCase(java.util.Locale.ROOT), "path", path))
                : "command.import-confirm-required";
        return CommandException.custom("ACELIB-CMD-IMPORT-CONFIRM-REQUIRED", msg);
    }

    private static Component formatImport(ConfigLangAdapter messages, ImportSource source,
                                          CurrencyInfo currency, String path, ImportOutcome outcome) {
        String sourceName = source.name().toLowerCase(java.util.Locale.ROOT);
        String failures = summarizeFailures(outcome);
        Map<String, Object> vars = outcome.backupId() == null
                ? Map.of("source", sourceName, "path", path, "currency_name", currency.displayName(),
                        "applied", String.valueOf(outcome.report().appliedCount()),
                        "skipped", String.valueOf(outcome.report().skippedCount()),
                        "failed", String.valueOf(outcome.failedCount()),
                        "failures", failures)
                : Map.of("source", sourceName, "path", path, "currency_name", currency.displayName(),
                        "applied", String.valueOf(outcome.report().appliedCount()),
                        "skipped", String.valueOf(outcome.report().skippedCount()),
                        "failed", String.valueOf(outcome.failedCount()),
                        "failures", failures, "backup_id", outcome.backupId());
        if (messages == null) {
            String base = "import." + (outcome.dryRun() ? "preview" : "applied")
                    + ":" + sourceName + ":" + path + ":" + currency.displayName()
                    + ":applied=" + outcome.report().appliedCount()
                    + ":skipped=" + outcome.report().skippedCount()
                    + ":failed=" + outcome.failedCount();
            if (outcome.backupId() != null) {
                base += ":backup=" + outcome.backupId();
            }
            if (!failures.isEmpty()) {
                base += ":failures=" + failures;
            }
            return Component.text(base);
        }
        if (outcome.isEmpty()) {
            return messages.renderMessage("admin.import-empty", Map.of(
                    "source", sourceName, "path", path, "currency_name", currency.displayName()));
        }
        return messages.renderMessage(outcome.dryRun() ? "admin.import-preview" : "admin.import-success", vars);
    }

    /** First few failure lines for the reply; the full detail stays in the console log. */
    private static String summarizeFailures(ImportOutcome outcome) {
        List<String> all = new java.util.ArrayList<>(outcome.parseFailures());
        for (var result : outcome.report().results()) {
            if (result.status() == com.smile.aceeconomy.operations.ImportRecordResult.Status.FAILED) {
                String id = result.record() == null ? "?" : result.record().sourceRecordId();
                all.add(id + ": " + result.message());
            }
        }
        if (all.isEmpty()) {
            return "";
        }
        int shown = Math.min(5, all.size());
        String joined = String.join("; ", all.subList(0, shown));
        return all.size() > shown ? " [" + joined + "; and " + (all.size() - shown) + " more]" : " [" + joined + "]";
    }

    private static void executeBackup(CommandServices services, TypedContext context,
                                      CommandArgument<String> labelArg) {
        var messages = services.messages();
        List<String> args = context.commandArgs();
        String label = args.isEmpty() ? null : context.get(labelArg);
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

    private static void executeRestore(CommandServices services, TypedContext context,
                                       CommandArgument<String> backupIdArg,
                                       CommandArgument<String> confirmArg) {
        var messages = services.messages();
        String backupId = context.get(backupIdArg);
        String confirmation = context.get(confirmArg);
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

    private static void executeRollback(CommandServices services, TypedContext context,
                                        CommandArgument<UUID> transactionIdArg) {
        var messages = services.messages();
        UUID transactionId = context.get(transactionIdArg);
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

    private static void executeHistory(CommandServices services, TypedContext context,
                                       CommandArgument<String> playerArg,
                                       CommandArgument<CurrencyInfo> currencyArg,
                                       CommandArgument<Integer> pageArg) {
        var messages = services.messages();
        List<String> args = context.commandArgs();
        String playerName = args.isEmpty() ? null : context.get(playerArg);
        int page = context.get(pageArg);
        CurrencyInfo currency = context.get(currencyArg);
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

    private static void queryAndReply(CommandServices services, TypedContext context,
                                      PlayerIdentity target, CurrencyInfo currency, int page) {
        HistoryReply.queryAndReply(services, context, target, currency, page);
    }

    private static Component formatComponent(ConfigLangAdapter messages, PlayerIdentity target,
                                             CurrencyInfo currency, AuditPage result) {
        return HistoryReply.format(messages, target, currency, result);
    }

    private static void executeMutation(CommandServices services, TypedContext context, String operation,
                                        CommandArgument<String> playerArg,
                                        CommandArgument<String> amountArg,
                                        CommandArgument<CurrencyInfo> currencyArg) {
        var messages = services.messages();
        String name = context.get(playerArg);
        CurrencyInfo currency = context.get(currencyArg);
        var amount = V2CommandSupport.amount(messages, context.get(amountArg), currency);
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
}
