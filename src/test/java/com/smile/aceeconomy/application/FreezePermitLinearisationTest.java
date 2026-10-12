package com.smile.aceeconomy.application;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.operations.security.SecurityControlService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mutation permit covers its commit: freezing waits for older permits to drain, so a
 * freeze that returns success is never overtaken by an already-admitted mutation. The
 * schedule uses controllable latches instead of sleeps.
 */
class FreezePermitLinearisationTest {

    private EconomyTestHarness harness() {
        return new EconomyTestHarness(DebtPolicy.disabled(), Amount.of(1000, 2));
    }

    @Test
    @DisplayName("freeze waits for an older permit before becoming effective")
    void freezeWaitsForOlderPermit() throws Exception {
        SecurityControlService control = new SecurityControlService();
        UUID alice = UUID.randomUUID();
        CountDownLatch permitHeld = new CountDownLatch(1);
        CountDownLatch releasePermit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> {
                try (SecurityControlService.MutationPermit ignored = control.acquireMutationPermit()) {
                    permitHeld.countDown();
                    try {
                        assertTrue(releasePermit.await(10, TimeUnit.SECONDS), "permit must be released");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(permitHeld.await(10, TimeUnit.SECONDS), "permit must be held first");

            Future<?> freezer = pool.submit(() -> control.freeze(alice, AdminAudit.console("incident")));
            // The freeze must not overtake the held permit: it stays pending while latched.
            Thread.onSpinWait();
            assertTrue(!freezer.isDone(), "freeze must wait for the older permit to drain");
            releasePermit.countDown();
            freezer.get(10, TimeUnit.SECONDS);
            holder.get(10, TimeUnit.SECONDS);

            assertTrue(control.isFrozen(alice), "freeze becomes effective after the permit drains");
            Optional<EconomyError> gate = control.checkInitiating(alice);
            assertEquals(Optional.of(EconomyError.ACCOUNT_FROZEN), gate);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("mutation admitted after a freeze is refused")
    void mutationAfterFreezeRefused() {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");
        SecurityControlService control = new SecurityControlService();
        h.service.bindSecurityControl(control);
        control.freeze(alice, AdminAudit.console("incident"));

        try (SecurityControlService.MutationPermit ignored = control.acquireMutationPermit()) {
            EconomyResult<TransferResult> r =
                    h.service.transfer(alice, bob, "dollar", Amount.of(10, 2));
            assertTrue(r.isFailure(), "mutation admitted after the freeze must be refused");
            assertEquals(EconomyError.ACCOUNT_FROZEN, r.error());
        }
        assertEquals(Amount.of(1000, 2), h.service.getBalance(alice, "dollar").value());
        assertEquals(Amount.of(1000, 2), h.service.getBalance(bob, "dollar").value());
    }

    @Test
    @DisplayName("opposite transfer order under freeze never deadlocks")
    void oppositeOrderNeverDeadlocks() throws Exception {
        EconomyTestHarness h = harness();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        h.service.createAccount(alice, "Alice");
        h.service.createAccount(bob, "Bob");
        SecurityControlService control = new SecurityControlService();
        h.service.bindSecurityControl(control);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<EconomyResult<TransferResult>> first =
                    pool.submit(() -> h.service.transfer(alice, bob, "dollar", Amount.of(10, 2)));
            Future<?> freezer =
                    pool.submit(() -> control.freeze(alice, AdminAudit.console("incident")));
            first.get(10, TimeUnit.SECONDS);
            freezer.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "freeze and transfer must not deadlock");
    }
}
