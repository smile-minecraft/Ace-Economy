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
 *
 * <p>Only HTTP 2xx responses count as success. Anything else (4xx, 5xx, timeout, network
 * exception) produces a {@link DiscordSendResult#failed failed} result whose {@code error} string
 * is sanitized to never echo the webhook URL.</p>
 *
 * <p><b>Ownership Transfer:</b> This transport takes ownership of the supplied {@link HttpClient}.
 * When {@link #close()} is invoked, the transport will close the client. The caller must not close
 * the client independently after passing it to this transport.</p>
 */
public final class HttpDiscordTransport implements DiscordTransport, AutoCloseable {

    private final HttpClient client;
    private final String webhookUrl;
    private final Duration timeout;

    public HttpDiscordTransport(HttpClient client, String webhookUrl, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.webhookUrl = Objects.requireNonNull(webhookUrl, "webhookUrl");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    private static String rootCauseSimpleName(Throwable throwable) {
        Throwable cause = throwable.getCause();
        if (cause != null) {
            return cause.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName();
    }

    @Override
    public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toJson()))
                    .timeout(timeout)
                    .build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        int status = response.statusCode();
                        if (status >= 200 && status < 300) {
                            return DiscordSendResult.ok(status);
                        }
                        return DiscordSendResult.failed("http " + status);
                    })
                    .exceptionally(throwable -> DiscordSendResult.failed(
                            "discord delivery failed: " + rootCauseSimpleName(throwable)));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    DiscordSendResult.failed("discord request rejected: " + e.getClass().getSimpleName()));
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
