package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.TypedContext;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.operations.AuditPage;
import com.smile.aceeconomy.operations.AuditQuery;

import net.kyori.adventure.text.Component;

import java.util.Map;

/**
 * Shared transaction-history query and rendering for the admin
 * ({@code /aceeco history}) and player ({@code /money history}) surfaces.
 *
 * <p>Both paths build the same {@link AuditQuery} shape — an optional account
 * filter, a resolved currency, a 0-based page and this fixed page size with the
 * default newest-first ordering — and render the resulting {@link AuditPage}
 * through the same message keys in the same field order, so the two outputs
 * cannot drift apart. Queries always go through the async history boundary;
 * this helper never blocks waiting for the result.</p>
 */
final class HistoryReply {

    /** Fixed page size for history replies; pagination stays 0-based like {@code AuditQuery}. */
    static final int PAGE_SIZE = 10;

    private HistoryReply() {
    }

    static AuditQuery queryFor(PlayerIdentity target, CurrencyInfo currency, int page) {
        return AuditQuery.builder()
                .accountId(target == null ? null : target.uuid())
                .currencyId(currency.id())
                .page(page)
                .limit(PAGE_SIZE)
                .build();
    }

    static void queryAndReply(CommandServices services, TypedContext context,
            PlayerIdentity target, CurrencyInfo currency, int page) {
        var messages = services.messages();
        var history = services.history();
        java.util.concurrent.CompletableFuture<AuditPage> query;
        try {
            query = history.query(queryFor(target, currency, page));
        } catch (RuntimeException queryFailure) {
            // A rejected query submission (for example a shut-down IO executor) must be
            // reported through the typed path. It cannot propagate here: the admin path
            // calls this helper from inside an async player-lookup callback whose stage
            // is ignored, so letting it escape would lose the diagnosis.
            V2CommandSupport.replyFailure(context, messages, queryFailure);
            return;
        }
        java.util.concurrent.Executor continuation;
        try {
            continuation = history.callbackExecutor();
        } catch (RuntimeException executorFailure) {
            V2CommandSupport.replyFailure(context, messages, executorFailure);
            return;
        }
        // No silent common-pool fallback: a missing executor is a wiring error and is
        // reported explicitly by the async helper through the typed error path.
        V2CommandSupport.replyValueLocalizedAsync(context, messages, query, continuation,
                value -> format(messages, target, currency, value));
    }

    static Component format(ConfigLangAdapter messages, PlayerIdentity target,
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
}
