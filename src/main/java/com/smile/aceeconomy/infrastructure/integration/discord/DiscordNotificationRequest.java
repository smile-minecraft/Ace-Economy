package com.smile.aceeconomy.infrastructure.integration.discord;

import java.util.Objects;

/**
 * Immutable description of a committed economy event to announce on Discord.
 *
 * <p>By the time this is handed to {@link DiscordNotifier}, the underlying economy transaction has
 * already committed. The notifier is strictly best-effort and can never veto or roll back that
 * committed result.</p>
 */
public record DiscordNotificationRequest(
        String type,
        String senderName,
        String receiverName,
        String amountText,
        boolean adminAction) {

    public DiscordNotificationRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(receiverName, "receiverName");
        Objects.requireNonNull(amountText, "amountText content");
    }
}
