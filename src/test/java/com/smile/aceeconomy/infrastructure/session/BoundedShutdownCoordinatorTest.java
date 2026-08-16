package com.smile.aceeconomy.infrastructure.session;

import com.smile.aceeconomy.ports.SessionError;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct, deterministic tests for the bounded wait logic (no sleeps). */
class BoundedShutdownCoordinatorTest {

    @Test
    void zeroDeadlineTimesOutPendingFlush() {
        BoundedShutdownCoordinator coordinator = new BoundedShutdownCoordinator();
        UUID uuid = UUID.randomUUID();
        CompletableFuture<Void> pending = new CompletableFuture<>();
        Map<UUID, SessionError> failures = coordinator.awaitFlush(Map.of(uuid, pending), 0L);
        assertEquals(SessionError.FLUSH_TIMEOUT, failures.get(uuid));
    }

    @Test
    void completedFlushHasNoFailure() {
        BoundedShutdownCoordinator coordinator = new BoundedShutdownCoordinator();
        UUID uuid = UUID.randomUUID();
        CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
        assertTrue(coordinator.awaitFlush(Map.of(uuid, done), 100L).isEmpty());
    }

    @Test
    void failedFlushReportedAsFlushFailed() {
        BoundedShutdownCoordinator coordinator = new BoundedShutdownCoordinator();
        UUID uuid = UUID.randomUUID();
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));
        Map<UUID, SessionError> failures = coordinator.awaitFlush(Map.of(uuid, failed), 100L);
        assertEquals(SessionError.FLUSH_FAILED, failures.get(uuid));
    }

    @Test
    void emptyPendingIsNoOp() {
        BoundedShutdownCoordinator coordinator = new BoundedShutdownCoordinator();
        assertTrue(coordinator.awaitFlush(Map.of(), 100L).isEmpty());
    }
}
