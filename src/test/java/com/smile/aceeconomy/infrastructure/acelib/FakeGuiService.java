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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
