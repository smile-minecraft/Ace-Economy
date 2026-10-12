package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.CommandException;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.ports.operations.ImportSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared AceLib typed arguments for the v2 command surface.
 *
 * <p>Every slot of every v2 subcommand is declared through these factories and parsed by
 * AceLib before the handler runs, so a malformed token fails with the same typed
 * {@link CommandException} code the hand-rolled checks produced. Two validations stay in
 * the handlers on purpose, because they need inputs only the handler can see:</p>
 * <ul>
 *   <li>amount scale: the scale belongs to the <em>resolved</em> currency, which is the
 *   trailing slot, while the amount slot comes first — a positional parser cannot know
 *   it yet. Amount slots therefore parse as trimmed tokens and the handler runs the
 *   shared {@link AmountParser} with the resolved scale, exactly as before;</li>
 *   <li>{@code /aceeco import} tail shapes ({@code [currency]} vs {@code [apply confirm]}
 *   vs {@code [currency apply confirm]}): the meaning of each trailing token depends on
 *   the whole tail, so the slots parse leniently and the handler keeps its existing
 *   shape switch with the same confirm-required codes.</li>
 * </ul>
 *
 * <p>Player names stay plain tokens on purpose as well: resolution is asynchronous
 * ({@code PlayerLookupService#resolve}), with empty, failed and late completions all
 * mapped by the handler, so no synchronous online-player argument could replace it.</p>
 *
 * <p>Several suggest functions mirror the legacy position-based completers slot by slot,
 * quirks included (for example the amount slot suggesting currencies, or the import
 * source slot suggesting nothing): completion output is player-visible and must not
 * change in this migration. Each quirk is locked by {@code TypedCompletionContractTest}.</p>
 */
public final class CommandArguments {

    private CommandArguments() {
    }

    /** Required player slot ({@code <player>}): trimmed token, async resolve stays in the handler. */
    public static CommandArgument<String> playerName(CommandServices services) {
        return token("player", "<player>",
                prefix -> CommandCompletion.byPrefix(
                        services.players().onlinePlayerNames(), prefix));
    }

    /** Optional player slot ({@code [player]}): same parsing, bracket usage token. */
    public static CommandArgument<String> optionalPlayerName(CommandServices services) {
        return token("player", "[player]",
                prefix -> CommandCompletion.byPrefix(
                        services.players().onlinePlayerNames(), prefix));
    }

    /**
     * Required amount slot ({@code <amount>}): trimmed token only. The scale-aware check
     * runs in the handler with the resolved currency's scale (see the class note).
     * Completion mirrors the legacy amount position, which suggests currencies.
     */
    public static CommandArgument<String> amountToken(CommandServices services) {
        return token("amount", "<amount>",
                prefix -> CommandCompletion.byPrefix(
                        services.economy().knownCurrencyIds(), prefix));
    }

    /**
     * Optional currency slot ({@code [currency]}): resolves through the shared resolver,
     * so an unknown id fails with {@code ACELIB-CMD-UNKNOWN-CURRENCY} at parse time.
     * A blank token can never arrive here — AceLib rejects it as a non-single-token
     * before the parser runs — so every parse is an explicit id; omission is served by
     * the {@link #defaultCurrency(CommandServices) default} instead.
     */
    public static CommandArgument<CurrencyInfo> currency(CommandServices services) {
        return CommandArgument.custom("currency", "[currency]",
                (raw, ignored) -> CurrencyArgResolver.resolve(services.messages(),
                        services.economy(), raw, services.economy().defaultCurrencyId()),
                prefix -> CommandCompletion.byPrefix(
                        services.economy().knownCurrencyIds(), prefix));
    }

    /** Default applied when the trailing currency slot is omitted: the blank branch of the shared resolver. */
    public static CurrencyInfo defaultCurrency(CommandServices services) {
        return CurrencyArgResolver.resolve(services.messages(),
                services.economy(), null, services.economy().defaultCurrencyId());
    }

    /** Optional history page ({@code [page]}): a non-negative integer, else {@code ACELIB-CMD-INVALID-PAGE}. */
    public static CommandArgument<Integer> page(CommandServices services) {
        return CommandArgument.custom("page", "[page]",
                (raw, ignored) -> parsePage(services.messages(), raw),
                prefix -> List.of());
    }

    /** Required rollback transaction id: a UUID, else {@code ACELIB-CMD-INVALID-UUID}. */
    public static CommandArgument<UUID> transactionId(CommandServices services) {
        return CommandArgument.custom("transaction-id", "<transaction-id>",
                (raw, ignored) -> parseTransactionId(services.messages(), raw),
                prefix -> List.of());
    }

    /**
     * Required import source: {@code essentials} or {@code cmi}, else
     * {@code ACELIB-CMD-IMPORT-SOURCE-UNKNOWN}. Suggests nothing, mirroring the legacy
     * positional completer at this slot.
     */
    public static CommandArgument<ImportSource> importSource(CommandServices services) {
        return CommandArgument.custom("source", "<essentials|cmi>",
                (raw, ignored) -> parseImportSource(services.messages(), raw),
                prefix -> List.of());
    }

    /** Required import path: any single token (slashes allowed); the path gate validates it later. */
    public static CommandArgument<String> importPath(CommandServices services) {
        return CommandArgument.custom("path", "<path>",
                (raw, ignored) -> raw.trim(),
                prefix -> {
                    List<String> base = new ArrayList<>(services.economy().knownCurrencyIds());
                    base.add("apply");
                    return CommandCompletion.byPrefix(base, prefix);
                });
    }

    /** Optional import third slot: lenient token, handler disambiguates currency vs apply. */
    public static CommandArgument<String> importCurrencySlot() {
        return token("currency", "[currency]",
                prefix -> CommandCompletion.byPrefix(List.of("apply", "confirm"), prefix));
    }

    /** Optional import fourth slot: lenient token, handler pairs it with confirm. */
    public static CommandArgument<String> importApplySlot() {
        return token("apply", "[apply]",
                prefix -> CommandCompletion.byPrefix(List.of("confirm"), prefix));
    }

    /** Optional import fifth slot: lenient token, handler requires the confirm literal. */
    public static CommandArgument<String> importConfirmSlot() {
        return token("confirm", "[confirm]", prefix -> List.of());
    }

    /** Plain single-token slot with no completion (labels, ids, confirm literals). */
    public static CommandArgument<String> token(String name, String usage) {
        return token(name, usage, prefix -> List.of());
    }

    private static CommandArgument<String> token(String name, String usage,
                                                 CommandArgument.Suggester suggester) {
        return CommandArgument.custom(name, usage, (raw, ignored) -> raw.trim(), suggester);
    }

    static int parsePage(ConfigLangAdapter messages, String raw) {
        try {
            int page = Integer.parseInt(raw.trim());
            if (page >= 0) {
                return page;
            }
        } catch (NumberFormatException ignored) {
        }
        String msg = messages != null
                ? messages.plainMessage("command.invalid-page", java.util.Map.of("raw", raw.trim()))
                : "command.invalid-page";
        throw CommandException.custom("ACELIB-CMD-INVALID-PAGE", msg);
    }

    static UUID parseTransactionId(ConfigLangAdapter messages, String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            String msg = messages != null
                    ? messages.plainMessage("command.invalid-uuid", java.util.Map.of("raw", raw.trim()))
                    : "command.invalid-uuid";
            throw CommandException.custom("ACELIB-CMD-INVALID-UUID", msg);
        }
    }

    static ImportSource parseImportSource(ConfigLangAdapter messages, String raw) {
        if ("essentials".equalsIgnoreCase(raw.trim())) {
            return ImportSource.ESSENTIALS;
        }
        if ("cmi".equalsIgnoreCase(raw.trim())) {
            return ImportSource.CMI;
        }
        String msg = messages != null
                ? messages.plainMessage("command.import-unknown-source", java.util.Map.of("raw", raw.trim()))
                : "command.import-unknown-source:" + raw.trim();
        throw CommandException.custom("ACELIB-CMD-IMPORT-SOURCE-UNKNOWN", msg);
    }
}
