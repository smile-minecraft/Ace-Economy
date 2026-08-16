package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.ports.SessionError;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded waiter for in-flight flush futures. Never waits indefinitely: each future is bounded by the
 * remaining shared deadline (computed from a single start time so total wait is bounded by the
 * deadline, not N times it). Failures are surfaced as typed {@link SessionError} rather than swallowed.
 */
final class BoundedShutdownCoordinator {

    Map<UUID, SessionError> awaitFlush(Map<UUID, CompletableFuture<Void>> pending, long deadlineMillis) {
        Map<UUID, SessionError> failures = new HashMap<>();
        if (pending.isEmpty()) {
            return failures;
        }
        long start = System.nanoTime();
        long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, deadlineMillis));
        for (Map.Entry<UUID, CompletableFuture<Void>> entry : pending.entrySet()) {
            long remainingNanos = deadlineNanos - (System.nanoTime() - start);
            if (remainingNanos <= 0L) {
                failures.put(entry.getKey(), SessionError.FLUSH_TIMEOUT);
                continue;
            }
            try {
                entry.getValue().get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException ex) {
                failures.put(entry.getKey(), SessionError.FLUSH_TIMEOUT);
            } catch (java.util.concurrent.ExecutionException ex) {
                failures.put(entry.getKey(), SessionError.FLUSH_FAILED);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                failures.put(entry.getKey(), SessionError.FLUSH_INTERRUPTED);
            }
        }
        return failures;
    }
}
