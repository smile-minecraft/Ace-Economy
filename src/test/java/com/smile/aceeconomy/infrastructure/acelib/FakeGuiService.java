package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.gui.GuiArgument;
import com.smile.acelib.gui.GuiAsyncRequest;
import com.smile.acelib.gui.GuiPage;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiSession;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic fake of AceLib's {@link GuiService} for offline contract tests. It implements the
 * real interface and enforces the same generation/session contract the production
 * {@code GuiServiceImpl} enforces on a live server: every open/close bumps the player's generation,
 * and a click or async update carrying a stale generation is rejected before any action runs.
 *
 * <p>The only deviation from the real service is that it does not call {@code Bukkit.getPlayer}
 * (which requires a live server); session bookkeeping is kept in memory. This is exactly the
 * boundary the v2 bank GUI relies on, so the contract is genuinely exercised.
 */
public final class FakeGuiService implements GuiService {

    private final boolean available;
    private final String unavailableReason;
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong(1);
    // Deferred async-update callbacks, modelling the backend path where applyAsyncUpdate is
    // accepted and the renderer runs later on the player region thread. While deferred, the
    // callback queue holds the pending paints; flushCallbacks() runs them in order on the
    // calling thread. Disabled by default so existing tests keep the inline behaviour.
    private final Queue<Runnable> deferredCallbacks = new ConcurrentLinkedQueue<>();
    private volatile boolean deferCallbacks;

    private FakeGuiService(boolean available, String unavailableReason) {
        this.available = available;
        this.unavailableReason = unavailableReason;
    }

    public static FakeGuiService available() {
        return new FakeGuiService(true, null);
    }

    public static FakeGuiService unavailable(String reason) {
        return new FakeGuiService(false, reason);
    }

    /**
     * Fake whose async-update renderer is deferred: {@code applyAsyncUpdate} answers with the
     * real {@code GuiResult.accepted} contract and queues the callback instead of running it
     * inline. Tests drive the pending paint explicitly with {@link #flushCallbacks()}.
     */
    public static FakeGuiService deferred() {
        FakeGuiService fake = new FakeGuiService(true, null);
        fake.deferCallbacks = true;
        return fake;
    }

    /** Switch between inline (default) and deferred async-update callbacks. */
    public void setDeferredCallbacks(boolean deferred) {
        this.deferCallbacks = deferred;
    }

    /** Number of accepted-but-not-yet-run async-update callbacks. */
    public int pendingCallbackCount() {
        return deferredCallbacks.size();
    }

    /**
     * Run every queued async-update callback in order on the calling thread. A callback that
     * throws (for example a renderer fatal rethrown by the consumer) propagates to the caller
     * and stops the drain, like a region-thread dispatch failure would surface.
     */
    public void flushCallbacks() {
        Runnable next;
        while ((next = deferredCallbacks.poll()) != null) {
            next.run();
        }
    }

    @Override
    public GuiResult openInventory(GuiArgument arg) {
        if (!available) {
            return GuiResult.failed("gui-unavailable", unavailableReason);
        }
        UUID uuid = arg.playerUuid();
        long gen = generation.incrementAndGet();
        GuiSession session = new GuiSession(uuid, gen, "v2-bank", arg.title(), arg.size(), arg.protectedSlots());
        sessions.put(uuid, session);
        return GuiResult.success(session);
    }

    @Override
    public GuiResult closeInventory(UUID uuid, long gen) {
        if (!available) {
            return GuiResult.failed("gui-unavailable", unavailableReason);
        }
        GuiSession s = sessions.get(uuid);
        if (s == null) {
            return GuiResult.rejected("no-session", "no active session");
        }
        if (s.generation() != gen) {
            return GuiResult.rejected("stale-generation", "generation mismatch");
        }
        sessions.remove(uuid);
        // The real GuiService signals a successful close with a SUCCESS result (GuiResult.closed
        // is unusable: its factory passes a null errorCode, which the GuiResult constructor rejects
        // for the CLOSED state). V2BankGuiSession.close() keys off isSuccess(), so model it that way.
        return GuiResult.success(s);
    }

    @Override
    public GuiResult getActiveSession(UUID uuid) {
        if (!available) {
            return GuiResult.failed("gui-unavailable", unavailableReason);
        }
        GuiSession s = sessions.get(uuid);
        if (s == null) {
            return GuiResult.failed("no-session", "no active session");
        }
        return GuiResult.success(s);
    }

    @Override
    public GuiResult validateClick(UUID uuid, long gen, int slot) {
        if (!available) {
            return GuiResult.rejected("gui-unavailable", unavailableReason);
        }
        GuiSession s = sessions.get(uuid);
        if (s == null) {
            return GuiResult.rejected("no-session", "no active session");
        }
        if (s.generation() != gen) {
            return GuiResult.rejected("stale-generation", "generation mismatch");
        }
        // Mirror the production contract: a slot registered as an AceLib
        // protected slot is rejected here (production ACELIB-GUI-010), so a
        // consumer action slot must never be passed as an AceLib protected
        // slot. The fake keeps its simplified code style ("slot-protected").
        if (s.protectedSlots() != null && s.protectedSlots().contains(slot)) {
            return GuiResult.rejected("slot-protected", "slot is protected");
        }
        return GuiResult.allowed(s);
    }

    @Override
    public GuiResult beginAsyncUpdate(UUID uuid, long gen, int pageIndex) {
        if (!available) {
            return GuiResult.rejected("gui-unavailable", unavailableReason);
        }
        GuiSession s = sessions.get(uuid);
        if (s == null) {
            return GuiResult.rejected("no-session", "no active session");
        }
        if (s.generation() != gen) {
            return GuiResult.rejected("stale-generation", "generation mismatch");
        }
        GuiAsyncRequest req = newRequest(uuid, s.generation(), pageIndex, generation.incrementAndGet());
        return GuiResult.success(s, req);
    }

    @Override
    public <T> GuiResult applyAsyncUpdate(GuiAsyncRequest req, GuiPage<T> page, Runnable onApplied) {
        if (!available) {
            return GuiResult.rejected("gui-unavailable", unavailableReason);
        }
        GuiSession s = sessions.get(req.playerUuid());
        if (s == null) {
            return GuiResult.rejected("no-session", "no active session");
        }
        if (req.sessionGeneration() != s.generation()) {
            return GuiResult.rejected("stale-generation", "generation mismatch");
        }
        if (onApplied != null && deferCallbacks) {
            deferredCallbacks.offer(onApplied);
            return GuiResult.accepted(s, "deferred");
        }
        if (onApplied != null) {
            onApplied.run();
        }
        return GuiResult.success(s);
    }

    @Override
    public String getModuleStatus() {
        return available ? "READY" : "UNAVAILABLE";
    }

    @Override
    public void shutdown() {
        sessions.clear();
    }

    private static GuiAsyncRequest newRequest(UUID uuid, long sessionGen, int page, long reqGen) {
        try {
            Constructor<GuiAsyncRequest> ctor = GuiAsyncRequest.class.getDeclaredConstructor(
                    UUID.class, long.class, int.class, long.class);
            ctor.setAccessible(true);
            return ctor.newInstance(uuid, sessionGen, page, reqGen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot construct GuiAsyncRequest in fake", e);
        }
    }

    // default methods getListener / createConfirmation / confirm / cancel are inherited.
}
