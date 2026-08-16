package com.smile.aceeconomy.infrastructure.integration.discord;

/**
 * Outcome of a single Discord webhook delivery attempt.
 *
 * <p>A failed delivery carries a negative {@link #statusCode()} ({@code -1}) and a non-null
 * {@link #error()}; the notifier treats every outcome as best-effort and never acts on it.</p>
 */
public record DiscordSendResult(boolean success, int statusCode, String error) {

    public static DiscordSendResult ok(int statusCode) {
        return new DiscordSendResult(true, statusCode, null);
    }

    public static DiscordSendResult failed(String error) {
        return new DiscordSendResult(false, -1, error);
    }
}
