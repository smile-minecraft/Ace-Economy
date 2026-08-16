package com.smile.aceeconomy.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit tests for the v2 ordered module lifecycle and the resource-owner cleanup
 * boundary. No server runtime is required: modules and resources are fakes that only record order.
 */
class BootstrapLifecycleTest {

    // ---- fakes ----------------------------------------------------------------

    private static final class FakeModule implements LifecycleModule {
        private final String name;
        private final List<String> log;
        private final boolean registerBeforeFail;
        private final boolean failStart;
        boolean started = false;
        boolean stopped = false;

        FakeModule(String name, List<String> log) {
            this(name, log, false, false);
        }

        FakeModule(String name, List<String> log, boolean registerBeforeFail, boolean failStart) {
            this.name = name;
            this.log = log;
            this.registerBeforeFail = registerBeforeFail;
            this.failStart = failStart;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void start(ResourceOwner resources) throws Exception {
            if (registerBeforeFail) {
                resources.register(() -> log.add("cleanup:" + name));
            }
            if (failStart) {
                throw new RuntimeException("boom-" + name);
            }
            started = true;
            log.add("start:" + name);
            resources.register(() -> log.add("cleanup:" + name));
        }

        @Override
        public void stop() throws Exception {
            stopped = true;
            log.add("stop:" + name);
        }
    }

    // ---- start order ----------------------------------------------------------

    @Test
    void modulesStartInRegistrationOrder() throws Exception {
        List<String> log = new ArrayList<>();
        ModuleLifecycle lifecycle = new ModuleLifecycle();
        lifecycle.add(new FakeModule("A", log));
        lifecycle.add(new FakeModule("B", log));
        lifecycle.add(new FakeModule("C", log));

        lifecycle.startAll();

        assertEquals(List.of("start:A", "start:B", "start:C"), log);
    }

    // ---- start failure rollback ----------------------------------------------

    @Test
    void startFailureRollsBackSucceededModulesInReverseOrderAndPreservesOriginal() {
        List<String> log = new ArrayList<>();
        ModuleLifecycle lifecycle = new ModuleLifecycle();
        lifecycle.add(new FakeModule("A", log));
        lifecycle.add(new FakeModule("B", log, false, true)); // fails to start
        lifecycle.add(new FakeModule("C", log));

        RuntimeException thrown = assertThrows(RuntimeException.class, lifecycle::startAll);

        // original exception preserved, not swallowed or replaced
        assertEquals("boom-B", thrown.getMessage());
        assertTrue(thrown.getSuppressed().length == 0, "no stop errors expected on clean rollback");

        // A started, then was rolled back (stop + cleanup) in reverse; B never started; C never ran
        assertEquals(List.of("start:A", "stop:A", "cleanup:A"), log);
    }

    @Test
    void startFailureCleansUpResourcesRegisteredBeforeThrow() throws Exception {
        List<String> log = new ArrayList<>();
        ModuleLifecycle lifecycle = new ModuleLifecycle();
        lifecycle.add(new FakeModule("A", log, true, true)); // registers cleanup then fails

        RuntimeException thrown = assertThrows(RuntimeException.class, lifecycle::startAll);
        assertEquals("boom-A", thrown.getMessage());

        // the resource registered before the throw must still be torn down during rollback
        assertEquals(List.of("cleanup:A"), log);
    }

    @Test
    void failedStartLeavesFailingModuleNotUsable() throws Exception {
        List<String> log = new ArrayList<>();
        ModuleLifecycle lifecycle = new ModuleLifecycle();
        FakeModule a = new FakeModule("A", log);
        lifecycle.add(a);
        FakeModule b = new FakeModule("B", log, false, true); // fails to start
        lifecycle.add(b);

        assertThrows(RuntimeException.class, lifecycle::startAll);

        assertFalse(b.started, "failing module B must never have started");
        assertFalse(b.stopped, "failing module B must not be stopped (it never started)");
        assertTrue(a.stopped, "module A must have been rolled back (stopped)");
    }

    // ---- normal stop ----------------------------------------------------------

    @Test
    void stopRunsInReverseOrderWithResourceCleanup() throws Exception {
        List<String> log = new ArrayList<>();
        ModuleLifecycle lifecycle = new ModuleLifecycle();
        lifecycle.add(new FakeModule("A", log));
        lifecycle.add(new FakeModule("B", log));
        lifecycle.add(new FakeModule("C", log));

        lifecycle.startAll();
        log.clear();
        lifecycle.stopAll();

        // reverse module order; per module stop() then its resource cleanup()
        assertEquals(List.of(
                "stop:C", "cleanup:C",
                "stop:B", "cleanup:B",
                "stop:A", "cleanup:A"), log);
    }

    // ---- idempotent teardown --------------------------------------------------

    @Test
    void stopIsIdempotent() throws Exception {
        List<String> log = new ArrayList<>();
        ModuleLifecycle lifecycle = new ModuleLifecycle();
        lifecycle.add(new FakeModule("A", log));
        lifecycle.add(new FakeModule("B", log));

        lifecycle.startAll();
        log.clear();
        lifecycle.stopAll();
        lifecycle.stopAll(); // second call must be a no-op

        assertEquals(List.of(
                "stop:B", "cleanup:B",
                "stop:A", "cleanup:A"), log);
    }

    // ---- resource owner boundary ---------------------------------------------

    @Test
    void resourceOwnerRunsCleanupsInReverseOrderAndOnce() {
        List<String> log = new ArrayList<>();
        ResourceOwner owner = new ResourceOwner();
        owner.register(() -> log.add("r1"));
        owner.register(() -> log.add("r2"));
        owner.register(() -> log.add("r3"));

        owner.close();
        owner.close(); // idempotent

        assertEquals(List.of("r3", "r2", "r1"), log);
    }

    @Test
    void resourceOwnerRejectsRegistrationAfterClose() {
        ResourceOwner owner = new ResourceOwner();
        owner.close();
        assertThrows(IllegalStateException.class, () -> owner.register(() -> {
        }));
        assertTrue(owner.isClosed());
    }

    @Test
    void resourceOwnerRunsAllCleanupsEvenIfOneThrowsAggregatingSuppressed() {
        List<String> log = new ArrayList<>();
        ResourceOwner owner = new ResourceOwner();
        owner.register(() -> log.add("r1"));
        owner.register(() -> {
            throw new RuntimeException("boom-r2");
        });
        owner.register(() -> {
            throw new RuntimeException("boom-r3");
        });

        RuntimeException thrown = assertThrows(RuntimeException.class, owner::close);
        // reverse execution order is r3, r2, r1; r3 is the first to throw -> primary
        assertEquals("boom-r3", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("boom-r2", thrown.getSuppressed()[0].getMessage());
        // r1 still ran despite the two throwers
        assertEquals(List.of("r1"), log);
        // close remains idempotent even after a throwing close
        assertTrue(owner.isClosed());
        owner.close();
        assertEquals(List.of("r1"), log);
    }
}
