package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.AceLibApi.AceLibProvider;
import com.smile.acelib.lifecycle.LifecycleResult;
import com.smile.aceeconomy.acelib.AceLibAccess;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the AceLib lifecycle-host-backed module startup.
 *
 * <p>The production {@link HostModuleLifecycle} is driven directly; only the host boundary
 * (AceLib's own code, not instantiable downstream) is a double ({@link FakeLifecycleHost}),
 * and the facade is a Mockito stub returning that double. Module executables are fakes
 * recording order so reverse-compensation and exactly-once cleanup are observable.
 */
class HostModuleLifecycleTest {

    private static final class FakeProvider implements AceLibProvider {
        private final AceLibApi api;

        FakeProvider(AceLibApi api) {
            this.api = api;
        }

        @Override
        public AceLibApi api() {
            return api;
        }
    }

    private static final class StubAccess extends AceLibAccess {
        private final Optional<AceLibApi> facade;

        StubAccess(JavaPlugin plugin, Optional<AceLibApi> facade) {
            super(plugin);
            this.facade = facade;
        }

        @Override
        public Optional<AceLibApi> resolveReadyApi() {
            return facade;
        }
    }

    private static final class FakeModule implements LifecycleModule {
        private final String name;
        private final List<String> log;
        private final boolean registerBeforeFail;
        private final boolean failStart;
        private final boolean failStop;

        FakeModule(String name, List<String> log) {
            this(name, log, false, false, false);
        }

        FakeModule(String name, List<String> log, boolean registerBeforeFail, boolean failStart) {
            this(name, log, registerBeforeFail, failStart, false);
        }

        FakeModule(String name, List<String> log, boolean registerBeforeFail, boolean failStart,
                boolean failStop) {
            this.name = name;
            this.log = log;
            this.registerBeforeFail = registerBeforeFail;
            this.failStart = failStart;
            this.failStop = failStop;
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
            log.add("start:" + name);
            resources.register(() -> log.add("cleanup:" + name));
        }

        @Override
        public void stop() throws Exception {
            log.add("stop:" + name);
            if (failStop) {
                throw new RuntimeException("stop-boom-" + name);
            }
        }
    }

    private JavaPlugin plugin;
    private AceLibApi api;
    private FakeLifecycleHost host;

    private HostModuleLifecycle newLifecycle(Optional<AceLibApi> facade) {
        plugin = mock(JavaPlugin.class);
        return new HostModuleLifecycle(plugin, new StubAccess(plugin, facade));
    }

    private HostModuleLifecycle readyLifecycle() {
        host = new FakeLifecycleHost(new FakeProvider(apiWithHost()));
        return newLifecycle(Optional.of(apiWithHost()));
    }

    private AceLibApi apiWithHost() {
        if (api == null) {
            api = mock(AceLibApi.class);
            // The host double is created once per test; the stub below replaces this
            // placeholder before any registration runs.
            when(api.getLifecycleHost()).thenAnswer(invocation -> host);
        }
        return api;
    }

    private static void add(HostModuleLifecycle lifecycle, String id, Set<String> deps,
            LifecycleModule module) {
        lifecycle.add(id, deps, module);
    }

    private static boolean hasCode(LifecycleRegistrationException failure,
            LifecycleResult.Code code) {
        return failure.result().problems().stream()
                .anyMatch(problem -> problem.code() == code);
    }

    // ---- topological order --------------------------------------------------

    @Test
    void chainStartsInTopologicalOrder() throws Exception {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:a", Set.of(), new FakeModule("A", log));
        add(lifecycle, "test:b", Set.of("test:a"), new FakeModule("B", log));
        add(lifecycle, "test:c", Set.of("test:b"), new FakeModule("C", log));

        lifecycle.startAll();

        assertEquals(List.of("start:A", "start:B", "start:C"), log);
    }

    @Test
    void enableFollowsDependenciesNotBatchOrder() throws Exception {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:c", Set.of("test:b"), new FakeModule("C", log));
        add(lifecycle, "test:a", Set.of(), new FakeModule("A", log));
        add(lifecycle, "test:b", Set.of("test:a"), new FakeModule("B", log));

        lifecycle.startAll();

        assertEquals(List.of("start:A", "start:B", "start:C"), log);
    }

    // ---- structural rejection: nothing runs ---------------------------------

    @Test
    void missingDependencyRejectedWithNoSideEffects() {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:broken", Set.of("test:ghost"), new FakeModule("X", log));

        LifecycleRegistrationException thrown =
                assertThrows(LifecycleRegistrationException.class, lifecycle::startAll);

        assertTrue(hasCode(thrown, LifecycleResult.Code.MISSING_DEPENDENCY));
        assertTrue(log.isEmpty(), "rejected batch must not run any starter, got " + log);
        assertEquals(LifecycleResult.Outcome.REJECTED, thrown.result().outcome());
    }

    @Test
    void duplicateIdRejectedWithNoSideEffects() {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:dup", Set.of(), new FakeModule("A", log));
        add(lifecycle, "test:dup", Set.of(), new FakeModule("B", log));

        LifecycleRegistrationException thrown =
                assertThrows(LifecycleRegistrationException.class, lifecycle::startAll);

        assertTrue(hasCode(thrown, LifecycleResult.Code.DUPLICATE_ID));
        assertTrue(log.isEmpty(), "rejected batch must not run any starter, got " + log);
    }

    @Test
    void dependencyCycleRejectedWithNoSideEffects() {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:a", Set.of("test:b"), new FakeModule("A", log));
        add(lifecycle, "test:b", Set.of("test:a"), new FakeModule("B", log));

        LifecycleRegistrationException thrown =
                assertThrows(LifecycleRegistrationException.class, lifecycle::startAll);

        assertTrue(hasCode(thrown, LifecycleResult.Code.DEPENDENCY_CYCLE));
        assertTrue(log.isEmpty(), "rejected batch must not run any starter, got " + log);
    }

    // ---- enable failure: reverse compensation --------------------------------

    @Test
    void startFailureCompensatesInReverseAndCleansFailingResources() {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:a", Set.of(), new FakeModule("A", log));
        add(lifecycle, "test:b", Set.of("test:a"), new FakeModule("B", log, true, true));
        add(lifecycle, "test:c", Set.of("test:b"), new FakeModule("C", log));

        LifecycleRegistrationException thrown =
                assertThrows(LifecycleRegistrationException.class, lifecycle::startAll);

        assertTrue(hasCode(thrown, LifecycleResult.Code.ENABLE_FAILED));
        assertEquals(LifecycleResult.Outcome.FAILED, thrown.result().outcome());
        // B's pre-throw resource is cleaned by its own starter at throw time; A started
        // earlier is then compensated in reverse; B was never marked started; C never ran.
        assertEquals(List.of("start:A", "cleanup:B", "stop:A", "cleanup:A"), log);
    }

    // ---- normal stop: reverse order, exactly once ----------------------------

    @Test
    void stopRunsInReverseOrderWithResourceCleanupExactlyOnce() throws Exception {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:a", Set.of(), new FakeModule("A", log));
        add(lifecycle, "test:b", Set.of("test:a"), new FakeModule("B", log));
        add(lifecycle, "test:c", Set.of("test:b"), new FakeModule("C", log));

        lifecycle.startAll();
        log.clear();
        lifecycle.stopAll();
        lifecycle.stopAll();

        assertEquals(List.of(
                "stop:C", "cleanup:C",
                "stop:B", "cleanup:B",
                "stop:A", "cleanup:A"), log);
        assertEquals(1, host.unregisterCalls(), "repeated stop must not re-unregister");
    }

    @Test
    void stopFailureSurfacedAsStructuredResult() throws Exception {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:a", Set.of(), new FakeModule("A", log, false, false, true));

        lifecycle.startAll();

        LifecycleRegistrationException thrown =
                assertThrows(LifecycleRegistrationException.class, lifecycle::stopAll);
        assertTrue(hasCode(thrown, LifecycleResult.Code.CLOSE_FAILED));
        // The resource cleanup still ran even though stop() threw.
        assertEquals(List.of("start:A", "stop:A", "cleanup:A"), log);
    }

    // ---- guards: no re-registration ------------------------------------------

    @Test
    void startTwiceRefusedWithoutReRegister() throws Exception {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:a", Set.of(), new FakeModule("A", log));

        lifecycle.startAll();

        assertThrows(IllegalStateException.class, lifecycle::startAll);
        assertEquals(1, host.registerCalls(), "a second start must not re-register");
    }

    @Test
    void startAfterStopRefusedWithoutReRegister() throws Exception {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:a", Set.of(), new FakeModule("A", log));

        lifecycle.startAll();
        lifecycle.stopAll();

        assertThrows(IllegalStateException.class, lifecycle::startAll);
        assertEquals(1, host.registerCalls(), "restart must not re-register (reload safety)");
    }

    @Test
    void failedStartMakesStopANoOp() {
        HostModuleLifecycle lifecycle = readyLifecycle();
        List<String> log = new ArrayList<>();
        add(lifecycle, "test:a", Set.of(), new FakeModule("A", log, false, true));

        assertThrows(LifecycleRegistrationException.class, lifecycle::startAll);
        int unregistersBefore = host.unregisterCalls();

        // Must not throw: a failed start already compensated everything.
        try {
            lifecycle.stopAll();
        } catch (Exception failure) {
            throw new AssertionError("stop after failed start must be a no-op", failure);
        }
        assertEquals(unregistersBefore, host.unregisterCalls());
    }

    // ---- ready gate -----------------------------------------------------------

    @Test
    void missingFacadeRefusesBeforeAnyStart() {
        HostModuleLifecycle lifecycle = newLifecycle(Optional.empty());
        List<String> log = new ArrayList<>();
        lifecycle.add("test:a", Set.of(), new FakeModule("A", log));

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, lifecycle::startAll);

        assertTrue(thrown.getMessage().contains("not ready"),
                "gate must refuse without a ready facade, got: " + thrown.getMessage());
        assertTrue(log.isEmpty(), "gate must run no starter, got " + log);
    }

    // ---- two consumers ---------------------------------------------------------

    @Test
    void twoConsumersSharingOneHostAreIsolated() throws Exception {
        AceLibApi sharedApi = mock(AceLibApi.class);
        FakeLifecycleHost shared = new FakeLifecycleHost(new FakeProvider(sharedApi));
        when(sharedApi.getLifecycleHost()).thenReturn(shared);
        JavaPlugin pluginA = mock(JavaPlugin.class);
        JavaPlugin pluginB = mock(JavaPlugin.class);
        HostModuleLifecycle lifecycleA =
                new HostModuleLifecycle(pluginA, new StubAccess(pluginA, Optional.of(sharedApi)));
        HostModuleLifecycle lifecycleB =
                new HostModuleLifecycle(pluginB, new StubAccess(pluginB, Optional.of(sharedApi)));

        List<String> logA = new ArrayList<>();
        List<String> logB = new ArrayList<>();
        lifecycleA.add("a:one", Set.of(), new FakeModule("A1", logA));
        lifecycleB.add("b:one", Set.of(), new FakeModule("B1", logB));
        lifecycleB.add("b:two", Set.of("b:one"), new FakeModule("B2", logB));

        lifecycleA.startAll();
        lifecycleB.startAll();
        logA.clear();
        logB.clear();

        lifecycleA.stopAll();

        assertEquals(List.of("stop:A1", "cleanup:A1"), logA);
        assertTrue(logB.isEmpty(), "stopping consumer A must not touch consumer B, got " + logB);

        lifecycleB.stopAll();
        assertEquals(List.of("stop:B2", "cleanup:B2", "stop:B1", "cleanup:B1"), logB);
    }

    @Test
    void duplicateIdAcrossOwnersIsRejected() throws Exception {
        AceLibApi sharedApi = mock(AceLibApi.class);
        FakeLifecycleHost shared = new FakeLifecycleHost(new FakeProvider(sharedApi));
        when(sharedApi.getLifecycleHost()).thenReturn(shared);
        JavaPlugin pluginA = mock(JavaPlugin.class);
        JavaPlugin pluginB = mock(JavaPlugin.class);
        HostModuleLifecycle lifecycleA =
                new HostModuleLifecycle(pluginA, new StubAccess(pluginA, Optional.of(sharedApi)));
        HostModuleLifecycle lifecycleB =
                new HostModuleLifecycle(pluginB, new StubAccess(pluginB, Optional.of(sharedApi)));

        lifecycleA.add("shared:dup", Set.of(), new FakeModule("A1", new ArrayList<>()));
        lifecycleA.startAll();

        List<String> logB = new ArrayList<>();
        lifecycleB.add("shared:dup", Set.of(), new FakeModule("B1", logB));

        LifecycleRegistrationException thrown =
                assertThrows(LifecycleRegistrationException.class, lifecycleB::startAll);
        assertTrue(hasCode(thrown, LifecycleResult.Code.DUPLICATE_ID));
        assertTrue(logB.isEmpty());
    }
}
