package com.smile.aceeconomy.infrastructure.integration.discord;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Bounded, asynchronous, best-effort Discord notifier for already-committed economy events.
 *
 * <p>Contract: by the time {@link #notify(DiscordNotificationRequest)} is called, the underlying
 * economy transaction has already committed. This notifier is strictly best-effort:</p>
 * <ul>
 *   <li><b>Asynchronous</b> — delivery is handed to the injected {@link Executor} and the method
 *       returns immediately; it never blocks on the network.</li>
 *   <li><b>Bounded</b> — the executor is supplied by the caller (typically a fixed pool with a
 *       bounded queue), so a burst of events cannot grow unbounded.</li>
 *   <li><b>Best-effort / non-veto</b> — any mapping error, transport rejection, timeout, or delivery
 *       failure is swallowed. The notifier holds no reference to the committed result and can never
 *       roll it back or veto it.</li>
 * </ul>
 */
public final class DiscordNotifier {

    private final DiscordTransport transport;
    private final TransactionDiscordMapper mapper;
    private final Executor executor;

    public DiscordNotifier(DiscordTransport transport, TransactionDiscordMapper mapper, Executor executor) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Announce a committed event. Returns immediately; delivery is fire-and-forget. */
    public void notify(DiscordNotificationRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            CompletableFuture.runAsync(() -> deliver(request), executor);
        } catch (Exception ignored) {
            // Executor rejected or any failure: best-effort, never veto the committed outcome.
        }
    }

    private void deliver(DiscordNotificationRequest request) {
        try {
            DiscordPayload payload = mapper.map(request);
            transport.send(payload)
                    .whenComplete((result, error) -> {
                        // Intentionally ignore: best-effort delivery must not affect the caller.
                    });
        } catch (Exception ignored) {
            // Mapping or transport setup failure: best-effort, never veto the committed outcome.
        }
    }
}
