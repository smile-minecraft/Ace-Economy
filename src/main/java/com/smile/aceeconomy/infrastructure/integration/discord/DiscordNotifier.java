package com.smile.aceeconomy.infrastructure.integration.discord;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

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
 *   <li><b>Shutdown barrier</b> — {@link #shutdown()} is synchronous: it sets the notifier to
 *       inactive and blocks until every queued or in-flight task has fully drained, including
 *       the pending {@link CompletableFuture} returned by the transport. The barrier is reusable:
 *       it is reset whenever the in-flight counter reaches zero, so a subsequent shutdown call
 *       on an idle notifier returns immediately. Even if the caller thread is interrupted while
 *       waiting, {@code shutdown()} does not return early — it preserves the interrupt status
 *       and continues waiting until drain.</li>
 *   <li><b>Diagnostics contract</b> — every delivery attempt (success or failure) reports a
 *       sanitized diagnostic string to the optional {@link Consumer} supplied at construction.
 *       The diagnostic consumer is best-effort: it is invoked inside a {@code try/catch} so a
 *       throwing diagnostics consumer cannot leak the in-flight counter or strand the shutdown
 *       barrier.</li>
 * </ul>
 */
public final class DiscordNotifier {

    /** Default diagnostic consumer — silent no-op so production code can opt out of diagnostics. */
    public static final Consumer<String> SILENT_DIAGNOSTICS = msg -> { };

    private final DiscordTransport transport;
    private final TransactionDiscordMapper mapper;
    private final Executor executor;
    private final Consumer<String> diagnostics;

    private volatile boolean active = true;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ReentrantLock drainLock = new ReentrantLock();
    private final Condition idle = drainLock.newCondition();

    /**
     * Package-private test-only seam: invoked exactly once per {@link #shutdown()} call, after
     * {@code active} is set to {@code false} and the drain lock is held. Tests use this to
     * deterministically observe that {@code shutdown()} has actually entered the barrier
     * (i.e., {@code active=false} is now visible to other threads). Production code paths do
     * not install any hook, so there is no runtime overhead outside of tests.
     *
     * <p>This hook exists so lifecycle tests can observe {@code shutdown} having actually
     * entered the wait, not just the thread having reached the {@code shutdown()}
     * call site.</p>
     */
    private volatile Runnable testShutdownStartedHook = null;

    public DiscordNotifier(DiscordTransport transport, TransactionDiscordMapper mapper, Executor executor) {
        this(transport, mapper, executor, SILENT_DIAGNOSTICS);
    }

    public DiscordNotifier(
            DiscordTransport transport,
            TransactionDiscordMapper mapper,
            Executor executor,
            Consumer<String> diagnostics) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.diagnostics = diagnostics != null ? diagnostics : SILENT_DIAGNOSTICS;
    }

    /**
     * Mark the notifier inactive and block until every queued or in-flight task has fully
     * drained (including any pending transport future). Subsequent calls are safe and return
     * immediately when the in-flight counter is zero.
     *
     * <p>If the caller thread is interrupted while waiting, this method does NOT return early:
     * it preserves the interrupt status and continues waiting. The interrupt status is
     * re-asserted on the caller thread just before {@code shutdown()} returns.</p>
     */
    public void shutdown() {
        drainLock.lock();
        try {
            active = false;
            Runnable hook = testShutdownStartedHook;
            if (hook != null) {
                try {
                    hook.run();
                } catch (Throwable ignored) {
                    // best-effort
                }
            }
            boolean callerInterrupted = false;
            while (inFlight.get() > 0) {
                try {
                    idle.await();
                } catch (InterruptedException ie) {
                    // Condition.await() clears the thread interrupt status when it throws.
                    // We must continue waiting because the contract forbids early return; we
                    // remember the interrupt so we can re-assert it before returning.
                    callerInterrupted = true;
                }
            }
            if (callerInterrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Package-private test seam: install a hook that fires inside {@link #shutdown()} after
     * {@code active=false} is set, allowing tests to deterministically observe that
     * {@code shutdown()} has actually entered the barrier. The hook runs while the drain
     * lock is held, so any observer that sees the hook fire also sees the new {@code active}
     * value. Passing {@code null} clears any previously installed hook.
     *
     * <p>This is test-only: production code does not install hooks, and the field is
     * {@code volatile} so a freshly-installed hook is visible to the next {@code shutdown()}
     * call.</p>
     */
    void installTestShutdownStartedHook(Runnable hook) {
        this.testShutdownStartedHook = hook;
    }

    public boolean isActive() {
        return active;
    }

    /** Announce a committed event. Returns immediately; delivery is fire-and-forget. */
    public void notify(DiscordNotificationRequest request) {
        Objects.requireNonNull(request, "request");
        if (!active) {
            return;
        }
        // Atomically claim a slot in the in-flight counter BEFORE re-checking active so the
        // shutdown barrier cannot miss a callback that is about to be submitted.
        inFlight.incrementAndGet();
        if (!active) {
            // Shutdown raced ahead of us between our active check and the increment. Release
            // the slot and return without scheduling.
            decrementInFlight();
            return;
        }
        try {
            executor.execute(() -> deliver(request));
        } catch (RejectedExecutionException rejected) {
            safeAcceptDiagnostic(sanitize(
                    "RejectedExecutionException: " + rejected.getMessage()));
            decrementInFlight();
        } catch (RuntimeException unexpected) {
            safeAcceptDiagnostic(sanitize(
                    unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage()));
            decrementInFlight();
        }
    }

    private void deliver(DiscordNotificationRequest request) {
        CompletableFuture<DiscordSendResult> future = null;
        try {
            if (!active) {
                // Shutdown raced ahead of us between executor pickup and the active check;
                // never call the transport and decrement to unblock any waiting shutdown.
                decrementInFlight();
                return;
            }
            DiscordPayload payload;
            try {
                payload = mapper.map(request);
            } catch (RuntimeException mapFailure) {
                safeAcceptDiagnostic(sanitize(
                        "mapping failed: " + mapFailure.getClass().getSimpleName()));
                decrementInFlight();
                return;
            }
            try {
                future = transport.send(payload);
            } catch (RuntimeException sendFailure) {
                safeAcceptDiagnostic(sanitize(
                        "transport error: " + sendFailure.getClass().getSimpleName()));
                decrementInFlight();
                return;
            }
            if (future == null) {
                safeAcceptDiagnostic("transport returned null future");
                decrementInFlight();
                return;
            }
            // Decrement is deferred to future completion: shutdown must wait for the actual
            // HTTP round-trip (or its failure) before declaring the callback drained. The
            // whenComplete callback fires whether the future completes normally or exceptionally.
            future.whenComplete((result, error) -> onFutureComplete(result, error));
        } catch (Throwable unexpected) {
            // Defensive: never let an unexpected exception escape deliver(). Best-effort.
            safeAcceptDiagnostic(sanitize(
                    "deliver failed: " + unexpected.getClass().getSimpleName()));
            decrementInFlight();
        }
    }

    private void onFutureComplete(DiscordSendResult result, Throwable error) {
        try {
            if (error != null) {
                safeAcceptDiagnostic(sanitize(
                        "transport error: " + error.getClass().getSimpleName()));
            } else if (result != null) {
                if (result.success()) {
                    safeAcceptDiagnostic("delivered: http " + result.statusCode());
                } else {
                    safeAcceptDiagnostic(sanitize(
                            "delivery failed: http " + result.statusCode()));
                }
            } else {
                safeAcceptDiagnostic("delivery completed with no result");
            }
        } finally {
            // ALWAYS decrement, even if diagnostics throws. The decrement is the only thing
            // that can release a waiting shutdown; if it were skipped because diagnostics
            // threw, the barrier would deadlock forever.
            decrementInFlight();
        }
    }

    private void decrementInFlight() {
        if (inFlight.decrementAndGet() == 0) {
            drainLock.lock();
            try {
                idle.signalAll();
            } finally {
                drainLock.unlock();
            }
        }
    }

    /**
     * Run the diagnostics consumer inside a defensive try/catch. A throwing diagnostics consumer
     * must not affect the in-flight counter, the active flag, or any thread's interrupt status.
     */
    private void safeAcceptDiagnostic(String message) {
        try {
            diagnostics.accept(message);
        } catch (Throwable ignored) {
            // best-effort: diagnostics failures never leak into shutdown or thread lifecycle.
        }
    }

    /** Strip any URL-shaped substring from a diagnostic so secrets never leak through logs. */
    static String sanitize(String message) {
        if (message == null) {
            return "delivery failed";
        }
        // Case-insensitive and DOTALL: from first https:// to end of string, so residues
        // after space or quote (e.g. "https://h.example/p q SECRET") are fully masked.
        return message.replaceAll("(?is)https?://.*", "***");
    }
}
