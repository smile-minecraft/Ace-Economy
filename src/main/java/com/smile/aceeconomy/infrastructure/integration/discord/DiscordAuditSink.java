package com.smile.aceeconomy.infrastructure.integration.discord;

import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.AuditException;
import com.smile.aceeconomy.ports.AuditSink;

import java.util.Objects;

/**
 * Best-effort {@link AuditSink} decorator that sends a Discord notification after the
 * underlying audit record succeeds. Notification failures are swallowed and never
 * propagated as {@link AuditException}, so the transaction result and audit-failure
 * semantics remain unpolluted.
 */
public final class DiscordAuditSink implements AuditSink {

    private final AuditSink inner;
    private final DiscordNotifier notifier;

    public DiscordAuditSink(AuditSink inner, DiscordNotifier notifier) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    @Override
    public void record(Transaction transaction) throws AuditException {
        inner.record(transaction);
        try {
            DiscordNotificationRequest request = mapTransaction(transaction);
            notifier.notify(request);
        } catch (Exception ignored) {
            // Best-effort: notification failure must not become AuditException.
        }
    }

    private DiscordNotificationRequest mapTransaction(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        String type = transaction.type() != null ? transaction.type().name() : "unknown";
        String senderName = transaction.accountId() != null ? transaction.accountId().toString() : "unknown";
        String receiverName = transaction.counterparty() != null ? transaction.counterparty().toString() : senderName;
        String amountText = transaction.amount() != null ? transaction.amount().toString() : "0";
        boolean adminAction = false;
        return new DiscordNotificationRequest(type, senderName, receiverName, amountText, adminAction);
    }
}
