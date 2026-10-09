package com.smile.aceeconomy.infrastructure.integration.discord;

import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.CommittedTransactionListener;

import java.util.Objects;

/**
 * Best-effort post-commit Discord observer.
 *
 * <p>Registered on the transaction publisher, it announces every committed transaction after
 * the balances and audit records are durable. It is the single Discord notification source:
 * the audit sink stays a plain persistence bridge, so an atomically persisted transfer can
 * never be notified twice (or persisted twice by a notifying decorator).</p>
 *
 * <p>Mapping and delivery failures are swallowed: a broken observer can never change the
 * committed result. Delivery itself is asynchronous and bounded inside
 * {@link DiscordNotifier}.</p>
 */
public final class PostCommitDiscordListener implements CommittedTransactionListener {

    private final DiscordNotifier notifier;

    public PostCommitDiscordListener(DiscordNotifier notifier) {
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    @Override
    public void onCommitted(Transaction transaction) {
        if (transaction == null) {
            return;
        }
        try {
            notifier.notify(mapTransaction(transaction));
        } catch (Throwable ignored) {
            // Best-effort: mapping or enqueue failures must not reach the publisher,
            // let alone the committed result. The publisher isolates listeners as well.
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
