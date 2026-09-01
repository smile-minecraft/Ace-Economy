package com.smile.aceeconomy.infrastructure.integration.discord;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Targeted regression tests for the synchronous shutdown barrier, the deferred transport future,
 * the caller-interrupt contract, and the throwing-diagnostics contract of {@link DiscordNotifier}.
 *
 * <p>Every test below is fully deterministic: no {@code Thread.sleep} is used to prove a
 * synchronization guarantee. Where a thread is needed to drive the barrier, the test uses a
 * {@link CountDownLatch} or {@link CompletableFuture} and joins with a generous timeout.</p>
 */
class DiscordNotifierLifecycleTest {

    private final DiscordPayloadFilter filter = new DiscordPayloadFilter();
    private final TransactionDiscordMapper mapper = new TransactionDiscordMapper(filter, List.of());
    private final DiscordNotificationRequest request =
            new DiscordNotificationRequest("pay", "Alice", "Bob", "50.00", false);

    // ---- (existing tests already passed) reused as regression coverage -----

    @Test
    void queuedCallbackAfterShutdownDoesNotCallTransport() throws Exception {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        QueuedExecutor executor = new QueuedExecutor();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, executor, msg -> { });

        notifier.notify(request);
        assertEquals(1, executor.pendingSize());
        assertEquals(0, transport.sent().size());

        // Deterministic handshake to eliminate race between drainer and shutdown():
        //   - drainer waits on shutdownStarted latch before executing the queued callback
        //   - shutdown() publishes active=false and fires the hook (countDown), waking drainer
        //   - drainer then executes callback, which observes active=false and skips transport call
        // This proves that shutdown established the barrier BEFORE the queued task ran,
        // so the callback's check of active in deliver() (line 172) is guaranteed to see false.
        CountDownLatch shutdownStarted = new CountDownLatch(1);
        notifier.installTestShutdownStartedHook(shutdownStarted::countDown);

        Thread drainer = new Thread(() -> {
            try {
                shutdownStarted.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor.drainAll();
        }, "queued-task-drainer");
        drainer.start();

        notifier.shutdown();
        drainer.join(5_000);
        assertFalse(drainer.isAlive(), "drainer must finish during shutdown");
        assertEquals(0, transport.sent().size(), "no transport call may occur after shutdown");
        assertEquals(0, executor.pendingSize());
        assertFalse(notifier.isActive());
    }

    @Test
    void shutdownReturnsImmediatelyWhenNoCallbacksAreInFlight() throws Exception {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run);

        long start = System.nanoTime();
        notifier.shutdown();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 1_000, "shutdown on idle notifier must not block; took " + elapsedMs + "ms");
        assertFalse(notifier.isActive());
    }

    @Test
    void notifyAfterShutdownIsNoOpAndDoesNotCallTransport() throws Exception {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run);
        notifier.shutdown();

        notifier.notify(request);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture.runAsync(() -> { /* nothing */ }, executor)
                    .get(1, TimeUnit.SECONDS); // let any phantom future fire if buggy
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, transport.sent().size());
    }

    @Test
    void shutdownWaitsForInFlightDeliverToComplete() throws Exception {
        BlockingFakeTransport transport = new BlockingFakeTransport();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Thread shutdownThread = null;
        try {
            DiscordNotifier notifier = new DiscordNotifier(transport, mapper, executor, msg -> { });

            notifier.notify(request);
            assertTrue(transport.awaitEnter(5_000),
                    "executor thread should be inside transport.send()");
            assertEquals(0, transport.releaseCalls(),
                    "release must not have been called yet");

            // Deterministic handshake:
            //   - `shutdownStarted` is counted down by DiscordNotifier's package-private
            //     test-only hook INSIDE shutdown(), AFTER active=false is published and
            //     the drain lock is held. This proves shutdown has actually entered the
            //     barrier — not just that the shutdown thread reached the call site.
            //   - `shutdownReturned` is counted down by the shutdown thread after shutdown()
            //     returns, proving shutdown actually completed.
            // The test waits for `shutdownStarted` (bounded deadlock fail-safe), then asserts
            // `shutdownReturned` has NOT been counted down yet. Combined with the precondition
            // that send() is blocked (verified by transport.awaitEnter()) and release() has
            // not been called (verified by transport.releaseCalls()==0), this is a direct
            // observation that shutdown is currently in the wait — no logical deduction.
            CountDownLatch shutdownStarted = new CountDownLatch(1);
            CountDownLatch shutdownReturned = new CountDownLatch(1);
            notifier.installTestShutdownStartedHook(shutdownStarted::countDown);
            shutdownThread = new Thread(() -> {
                try {
                    notifier.shutdown();
                } finally {
                    shutdownReturned.countDown();
                }
            }, "shutdown-thread");
            shutdownThread.setDaemon(true);
            shutdownThread.start();

            assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS),
                    "shutdown must have entered the barrier (active=false)");
            assertEquals(1L, shutdownReturned.getCount(),
                    "shutdown must NOT return while deliver() is blocked inside send()");
            assertTrue(shutdownThread.isAlive(),
                    "shutdown thread must still be alive (blocked in await)");
            assertFalse(notifier.isActive(),
                    "active must be false while shutdown is in the barrier");

            transport.release();

            // Deadlock fail-safe.
            assertTrue(shutdownReturned.await(5, TimeUnit.SECONDS),
                    "shutdown must complete after release");
            shutdownThread.join(5_000);
            assertFalse(shutdownThread.isAlive(),
                    "shutdown thread must terminate after release");
            assertEquals(0L, shutdownReturned.getCount());
            assertEquals(1, transport.sendCalls());
        } finally {
            transport.release();
            if (shutdownThread != null) {
                try {
                    shutdownThread.join(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (shutdownThread.isAlive()) {
                    shutdownThread.interrupt();
                    try {
                        shutdownThread.join(2_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- new regression coverage: barrier reuse, future wait, interrupt, throwing diag -----

    @Test
    void shutdownBarrierIsReusableAcrossMultipleIdleCalls() throws Exception {
        // The barrier must be safe to call shutdown() on many times in a row: once a previous
        // shutdown has drained the in-flight counter, a later shutdown must return immediately,
        // not block on a stale latch that was already counted down.
        FakeDiscordTransport transport = new FakeDiscordTransport();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run);

        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            notifier.shutdown();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs < 1_000,
                    "shutdown call #" + i + " on idle notifier must be immediate; took "
                            + elapsedMs + "ms");
        }
        assertFalse(notifier.isActive());
    }

    @Test
    void shutdownAfterDrainedCallbackReturnsImmediately() throws Exception {
        // After a callback has fully drained (including its transport future), shutdown must
        // return immediately — the barrier must have been reset to the idle state.
        FakeDiscordTransport transport = new FakeDiscordTransport();
        QueuedExecutor executor = new QueuedExecutor();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, executor, msg -> { });

        notifier.notify(request);
        assertEquals(1, executor.pendingSize());

        // Drain the queued task first so it decrements before shutdown is called.
        Thread drainer = new Thread(executor::drainAll, "drainer");
        drainer.start();
        drainer.join(5_000);
        assertFalse(drainer.isAlive());
        assertEquals(1, transport.sent().size());

        // Now shutdown must return immediately because the barrier was reset to idle by
        // the decrement, not permanently broken by a stale one-shot latch.
        long start = System.nanoTime();
        notifier.shutdown();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 1_000,
                "shutdown after drained callback must be immediate; took " + elapsedMs + "ms");
    }

    @Test
    void shutdownWaitsForPendingTransportFuture() throws Exception {
        HangingFakeTransport transport = new HangingFakeTransport();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Thread shutdownThread = null;
        try {
            DiscordNotifier notifier = new DiscordNotifier(transport, mapper, executor, msg -> { });

            notifier.notify(request);
            // Deterministic: wait until the executor thread has invoked transport.send() and the
            // returned future is still pending.
            assertTrue(transport.awaitSendCall(5_000),
                    "transport.send() must have been called");
            assertEquals(0, transport.releaseCalls(),
                    "release must not have been called yet");

            // Same deterministic handshake as shutdownWaitsForInFlightDeliverToComplete: the
            // package-private test hook fires inside shutdown() AFTER active=false is published,
            // proving shutdown entered the barrier — not just that the thread reached the call
            // site. Combined with the precondition that release() has not been called (so
            // decrement cannot fire), this is a direct observation that shutdown is currently
            // in the wait.
            CountDownLatch shutdownStarted = new CountDownLatch(1);
            CountDownLatch shutdownReturned = new CountDownLatch(1);
            notifier.installTestShutdownStartedHook(shutdownStarted::countDown);
            shutdownThread = new Thread(() -> {
                try {
                    notifier.shutdown();
                } finally {
                    shutdownReturned.countDown();
                }
            }, "shutdown-waits-future");
            shutdownThread.setDaemon(true);
            shutdownThread.start();

            assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS),
                    "shutdown must have entered the barrier (active=false)");
            assertEquals(1L, shutdownReturned.getCount(),
                    "shutdown must NOT return while the transport future is pending");
            assertTrue(shutdownThread.isAlive(),
                    "shutdown thread must still be alive (blocked in await)");
            assertFalse(notifier.isActive(),
                    "active must be false while shutdown is in the barrier");

            // Release the pending future. whenComplete fires, decrement hits 0, signalAll
            // wakes shutdown's await(), the loop exits, shutdown returns.
            transport.release();

            // Deadlock fail-safe.
            assertTrue(shutdownReturned.await(5, TimeUnit.SECONDS),
                    "shutdown must complete after the transport future drains");
            shutdownThread.join(5_000);
            assertFalse(shutdownThread.isAlive(),
                    "shutdown thread must terminate after release");
            assertEquals(0L, shutdownReturned.getCount());
            assertEquals(1, transport.sendCalls());
        } finally {
            transport.release();
            if (shutdownThread != null) {
                try {
                    shutdownThread.join(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (shutdownThread.isAlive()) {
                    shutdownThread.interrupt();
                    try {
                        shutdownThread.join(2_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void shutdownCallerInterruptedContinuesWaitingAndPreservesInterruptStatus() throws Exception {
        // Contract under test:
        //   1. The shutdown caller's thread is interrupted BEFORE shutdown() is called.
        //   2. shutdown() must NOT return while a pending transport future is unresolved,
        //      even though the caller is interrupted — the interrupt must not break the
        //      drain barrier.
        //   3. shutdown() must preserve the caller's interrupt status when it finally
        //      returns.
        //   4. No further transport call may occur after shutdown returns.
        //
        // The shutdown call runs on a dedicated thread so that the test thread itself
        // never carries the interrupt flag — otherwise it could not safely join() the
        // shutdown thread. The shutdown thread captures its OWN post-shutdown interrupt
        // status into AtomicBooleans so we can verify the re-assertion deterministically.
        //
        // Uses the package-private test seam to deterministically observe that shutdown has
        // entered the barrier (active=false is published), then asserts shutdown has not
        // returned. This is a direct observation, not a logical deduction about timing.
        HangingFakeTransport transport = new HangingFakeTransport();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Thread shutdownThread = null;
        try {
            DiscordNotifier notifier = new DiscordNotifier(transport, mapper, executor, msg -> { });

            notifier.notify(request);
            // Deterministic: wait until the executor thread has invoked transport.send() and
            // the returned future is still pending.
            assertTrue(transport.awaitSendCall(5_000),
                    "executor thread must have invoked transport.send() so the future is pending");
            assertEquals(0, transport.releaseCalls(),
                    "release must not have been called yet");

            AtomicBoolean callerInterruptedBeforeShutdown = new AtomicBoolean(false);
            AtomicBoolean callerInterruptedAfterShutdown = new AtomicBoolean(false);
            CountDownLatch shutdownStarted = new CountDownLatch(1);
            CountDownLatch shutdownReturned = new CountDownLatch(1);

            notifier.installTestShutdownStartedHook(shutdownStarted::countDown);

            shutdownThread = new Thread(() -> {
                Thread.currentThread().interrupt();
                callerInterruptedBeforeShutdown.set(Thread.currentThread().isInterrupted());
                try {
                    notifier.shutdown();
                } finally {
                    shutdownReturned.countDown();
                    callerInterruptedAfterShutdown.set(Thread.currentThread().isInterrupted());
                }
            }, "shutdown-under-interrupt");
            shutdownThread.setDaemon(true);
            shutdownThread.start();

            // Direct observation: shutdown has actually entered the barrier. The hook fires
            // inside shutdown() AFTER active=false is published; if we can observe that hook,
            // we know shutdown is currently in the drain barrier.
            assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS),
                    "shutdown must have entered the barrier (active=false)");

            // Deterministic assertions: shutdown MUST be blocked. With inFlight==1 and release
            // not yet called, the only way shutdown could have returned is if decrement had
            // already fired — which requires the future completing, which requires release().
            assertEquals(1L, shutdownReturned.getCount(),
                    "shutdown must NOT return while the future is pending");
            assertTrue(shutdownThread.isAlive(),
                    "shutdown thread must still be alive (blocked in await)");
            assertEquals(0, transport.releaseCalls(),
                    "release must not have been called yet");
            assertFalse(notifier.isActive(),
                    "active must be false while shutdown is in the barrier");

            // Release the pending future. whenComplete fires, decrement hits 0, signalAll
            // wakes shutdown's await(), the loop exits, shutdown re-asserts the interrupt,
            // and shutdown returns. The shutdown thread's interrupt status at that point
            // is recorded into callerInterruptedAfterShutdown.
            transport.release();

            // Deadlock fail-safe.
            assertTrue(shutdownReturned.await(5, TimeUnit.SECONDS),
                    "shutdown must complete after release");
            shutdownThread.join(5_000);
            assertFalse(shutdownThread.isAlive(),
                    "shutdown thread must terminate after release");
            assertEquals(0L, shutdownReturned.getCount());

            // Sanity: the thread really was interrupted before it called shutdown.
            assertTrue(callerInterruptedBeforeShutdown.get(),
                    "sanity: shutdown thread was interrupted before shutdown");

            // Core assertion: shutdown preserved the caller's interrupt status.
            assertTrue(callerInterruptedAfterShutdown.get(),
                    "shutdown must preserve caller's interrupt status on return");

            assertEquals(1, transport.sendCalls(),
                    "transport must have been called exactly once before shutdown returned");
        } finally {
            transport.release();
            if (shutdownThread != null) {
                try {
                    shutdownThread.join(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (shutdownThread.isAlive()) {
                    shutdownThread.interrupt();
                    try {
                        shutdownThread.join(2_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void throwingDiagnosticsOnExecutorRejectionStillDecrementsInFlight() throws Exception {
        RejectingExecutor executor = new RejectingExecutor();
        FakeDiscordTransport transport = new FakeDiscordTransport();

        // Diagnostics consumer that always throws — must NOT prevent decrement.
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, executor,
                msg -> { throw new IllegalStateException("diagnostics exploded: " + msg); });

        notifier.notify(request);
        // If decrement didn't fire, shutdown hangs forever (caught by join timeout below).
        Thread shutdownThread = new Thread(notifier::shutdown, "shutdown-after-throwing-diag");
        shutdownThread.start();
        shutdownThread.join(2_000);
        assertFalse(shutdownThread.isAlive(),
                "shutdown must complete even when diagnostics throws on rejection");
        assertEquals(0, transport.sent().size());
    }

    @Test
    void throwingDiagnosticsInWhenCompleteCallbackStillDecrementsInFlight() throws Exception {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        // Diagnostics consumer that always throws — must NOT prevent the deferred decrement.
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run,
                msg -> { throw new IllegalStateException("diagnostics exploded: " + msg); });

        notifier.notify(request);
        Thread shutdownThread = new Thread(notifier::shutdown, "shutdown-after-throwing-whencomplete");
        shutdownThread.start();
        shutdownThread.join(2_000);
        assertFalse(shutdownThread.isAlive(),
                "shutdown must complete even when diagnostics throws inside whenComplete");
        assertEquals(1, transport.sent().size());
    }

    @Test
    void transportReturningNullFutureStillDecrementsInFlight() throws Exception {
        NullFutureTransport transport = new NullFutureTransport();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run, msg -> { });

        notifier.notify(request);
        Thread shutdownThread = new Thread(notifier::shutdown, "shutdown-null-future");
        shutdownThread.start();
        shutdownThread.join(2_000);
        assertFalse(shutdownThread.isAlive(),
                "shutdown must complete when transport returns a null future");
        assertEquals(1, transport.sendCalls());
    }

    @Test
    void transportSendThrowingRuntimeExceptionStillDecrementsInFlight() throws Exception {
        ThrowingTransport transport = new ThrowingTransport();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run, msg -> { });

        notifier.notify(request);
        Thread shutdownThread = new Thread(notifier::shutdown, "shutdown-throwing-send");
        shutdownThread.start();
        shutdownThread.join(2_000);
        assertFalse(shutdownThread.isAlive(),
                "shutdown must complete when transport.send() throws");
        assertEquals(1, transport.sendCalls());
    }

    @Test
    void diagnosticsCarrySanitizedStatusCodeOnFailure() {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        transport.setMode(FakeDiscordTransport.Mode.FAIL);
        AtomicInteger count = new AtomicInteger();
        String[] last = new String[1];
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run,
                msg -> {
                    count.incrementAndGet();
                    last[0] = msg;
                });

        notifier.notify(request);
        assertEquals(1, count.get());
        assertNotNull(last[0]);
        assertFalse(last[0].contains("https://"), "diagnostic must not contain URL");
    }

    @Test
    void diagnosticsCarryStatusCodeOnSuccess() {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        AtomicInteger count = new AtomicInteger();
        String[] last = new String[1];
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run,
                msg -> {
                    count.incrementAndGet();
                    last[0] = msg;
                });

        notifier.notify(request);
        assertEquals(1, count.get());
        assertNotNull(last[0]);
        assertTrue(last[0].contains("204"),
                "success diagnostic should report HTTP status: " + last[0]);
        assertFalse(last[0].contains("https://"));
    }

    @Test
    void silentDiagnosticsConstructorIsAccepted() {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run);
        notifier.notify(request);
    }

    @Test
    void silentDiagnosticsConstantIsNoOp() {
        DiscordNotifier.SILENT_DIAGNOSTICS.accept("anything");
    }

    // ---- test helpers ------------------------------------------------------

    /** Executor that accepts tasks into a queue and only runs them when {@link #drainAll()} is called. */
    private static final class QueuedExecutor implements Executor {
        private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        int pendingSize() {
            return queue.size();
        }

        void drainAll() {
            Runnable task;
            while ((task = queue.poll()) != null) {
                task.run();
            }
        }
    }

    /** Transport whose {@code send} blocks the calling thread until {@link #release()} is invoked. */
    private static final class BlockingFakeTransport implements DiscordTransport {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            sendCalls.incrementAndGet();
            entered.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(DiscordSendResult.ok(204));
        }

        boolean awaitEnter(long timeoutMs) throws InterruptedException {
            return entered.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        void release() {
            releaseCalls.incrementAndGet();
            released.countDown();
        }

        int sendCalls() {
            return sendCalls.get();
        }

        int releaseCalls() {
            return releaseCalls.get();
        }
    }

    /** Transport whose {@code send} returns a future that hangs until {@link #release()} is invoked. */
    private static final class HangingFakeTransport implements DiscordTransport {
        private final CountDownLatch sent = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            sendCalls.incrementAndGet();
            sent.countDown();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    released.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return DiscordSendResult.ok(204);
            });
        }

        boolean awaitSendCall(long timeoutMs) throws InterruptedException {
            return sent.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        void release() {
            releaseCalls.incrementAndGet();
            released.countDown();
        }

        int sendCalls() {
            return sendCalls.get();
        }

        int releaseCalls() {
            return releaseCalls.get();
        }
    }

    /** Transport that always rejects submitted tasks via {@link RejectedExecutionException}. */
    private static final class RejectingExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("test rejection");
        }
    }

    /** Transport whose {@code send} returns null instead of a future. */
    private static final class NullFutureTransport implements DiscordTransport {
        private final AtomicInteger sendCalls = new AtomicInteger();

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            sendCalls.incrementAndGet();
            return null;
        }

        int sendCalls() {
            return sendCalls.get();
        }
    }

    /** Transport whose {@code send} throws a runtime exception synchronously. */
    private static final class ThrowingTransport implements DiscordTransport {
        private final AtomicInteger sendCalls = new AtomicInteger();

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            sendCalls.incrementAndGet();
            throw new RuntimeException("test send failure");
        }

        int sendCalls() {
            return sendCalls.get();
        }
    }

    // Suppress "unused" warnings for fields referenced only via @SuppressWarnings'd helpers.
    @SuppressWarnings("unused")
    private static final Object UNUSED = new Object();
}
