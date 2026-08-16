package com.smile.aceeconomy.infrastructure.integration.discord;

import java.util.concurrent.CompletableFuture;

/**
 * Delivery seam for a Discord webhook.
 *
 * <p>Abstracts the HTTP client so the notifier and its contract tests never touch a live webhook.
 * The production binding is {@link HttpDiscordTransport}. Implementations must be bounded and
 * best-effort: a slow or failing delivery must never block or veto the caller.</p>
 */
public interface DiscordTransport {

    CompletableFuture<DiscordSendResult> send(DiscordPayload payload);
}
