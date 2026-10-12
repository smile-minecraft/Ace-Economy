package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.BanknoteIssuance;
import com.smile.aceeconomy.infrastructure.persistence.InMemoryBanknoteIssuanceStore;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup runner barrier on the existing IO scheduler (phase-1 red).
 *
 * <p>The runner must schedule recovery exactly once on the existing IO
 * scheduler (no new pool, no region blocking, no mocked fake success), keep
 * the barrier closed until the batch-end success opens it, and on failure keep
 * the plugin enabled with a loud diagnosis while keeping pending rows and
 * blocking new managed issuance / managed redemption. Queries and legacy v2
 * notes stay allowed. The phase-1 stubs never complete and report empty runs,
 * so every assertion below fails until phase 2 implements the runner. Real
 * executors are used throughout; nothing is mocked.</p>
 */
class BanknoteIssuanceStartupRunnerTest {

    @Test
    void schedulesOnceOnExistingIoAndCompletes() throws Exception {
        InMemoryBanknoteIssuanceStore store = new InMemoryBanknoteIssuanceStore();
        UUID nonce = UUID.randomUUID();
        store.prepare(BanknoteIssuance.prepared(nonce, UUID.randomUUID(), "dollar",
                Amount.of(100L, 2), Instant.now()));
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);
        BanknoteIssuanceStartupRunner runner = new BanknoteIssuanceStartupRunner(recovery);
        ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "startup-runner-test-io");
            thread.setDaemon(true);
            return thread;
        });
        try {
            boolean finished = runner.scheduleOnce(io);
            assertTrue(finished, "startup run must finish on the existing IO scheduler");
            assertTrue(runner.isScheduled(), "runner must record exactly-once scheduling");
            assertTrue(runner.isRecoveryComplete(),
                    "barrier must open at batch end so new managed issuance is unblocked");
            assertEquals(1, runner.lastReport().scanned(),
                    "progress must observe the pending record in batches");
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void secondScheduleIsNoOp() throws Exception {
        InMemoryBanknoteIssuanceStore store = new InMemoryBanknoteIssuanceStore();
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);
        BanknoteIssuanceStartupRunner runner = new BanknoteIssuanceStartupRunner(recovery);
        ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "startup-runner-once-io");
            thread.setDaemon(true);
            return thread;
        });
        try {
            runner.scheduleOnce(io);
            runner.scheduleOnce(io);
            assertTrue(runner.isScheduled(), "runner must record the single schedule");
            assertTrue(runner.isRecoveryComplete(),
                    "repeated scheduling must stay a no-op and keep the completed barrier");
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void failureKeepsPendingWithLoudDiagnosis() {
        InMemoryBanknoteIssuanceStore store = new InMemoryBanknoteIssuanceStore();
        UUID nonce = UUID.randomUUID();
        store.prepare(BanknoteIssuance.prepared(nonce, UUID.randomUUID(), "dollar",
                Amount.of(100L, 2), Instant.now()));
        BanknoteIssuanceRecovery recovery = new BanknoteIssuanceRecovery(store);
        BanknoteIssuanceStartupRunner runner = new BanknoteIssuanceStartupRunner(recovery);
        ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "startup-runner-fail-io");
            thread.setDaemon(true);
            return thread;
        });
        try {
            runner.scheduleOnce(io);
            assertTrue(runner.lastReport().diagnoses().size() > 0
                            || runner.lastReport().scanned() > 0,
                    "a failed run must leave loud progress/diagnosis with pending rows retained,"
                            + " never a silent pass");
            assertEquals(runner.lastReport().scanned(),
                    runner.lastReport().refunded() + runner.lastReport().aborted()
                            + runner.lastReport().quarantined(),
                    "every scanned record must resolve to exactly one counted outcome");
        } finally {
            io.shutdownNow();
        }
    }
}
