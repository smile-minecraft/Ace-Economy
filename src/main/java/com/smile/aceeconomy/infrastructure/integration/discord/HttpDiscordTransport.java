package com.smile.aceeconomy.infrastructure.integration.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Production {@link DiscordTransport} backed by {@link HttpClient}.
 *
 * <p>The caller is responsible for supplying an {@link HttpClient} configured with a bounded
 * executor (e.g. a fixed thread pool with a bounded queue) so delivery cannot grow unbounded. The
 * webhook URL is supplied at construction and is never placed into the payload body, so no
 * credential can leak through {@link DiscordPayload}.</p>
 */
public final class HttpDiscordTransport implements DiscordTransport {

    private final HttpClient client;
    private final String webhookUrl;
    private final Duration timeout;

    public HttpDiscordTransport(HttpClient client, String webhookUrl, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.webhookUrl = Objects.requireNonNull(webhookUrl, "webhookUrl");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
        Objects.requireNonNull(payload, "payload");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toJson()))
                .timeout(timeout)
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> DiscordSendResult.ok(response.statusCode()))
                .exceptionally(throwable -> DiscordSendResult.failed(throwable.getMessage()));
    }
}
