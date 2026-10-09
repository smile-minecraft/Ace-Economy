package com.smile.aceeconomy.infrastructure.integration.placeholder;

import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.domain.Currency;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Miss-triggered background priming for balance placeholders.
 *
 * <p>A balance placeholder must never perform storage I/O on its calling thread, so a cache
 * miss only enqueues a priming read here and replies with the fail-closed value immediately.
 * The next placeholder refresh then observes the primed entry. The priming call itself runs
 * on the injected background executor and publishes through
 * {@link EconomyApi#primeBalanceIfAbsent}, whose stamp fence plus absent-only publish keep a
 * delayed reply from resurrecting a dropped entry or overwriting a newer write.</p>
 *
 * <p>Overload behavior: concurrent requests for the same owner and currency share one
 * in-flight load (single-flight merge); at most {@link #MAX_PENDING} loads are outstanding
 * at any time and further requests are shed (the caller still replies immediately and the
 * next refresh retries). After {@link #close()} no new work is dispatched and queued work
 * that has not started yet is skipped instead of touching storage.</p>
 */
public final class PlaceholderBalanceLoader {

    /**
     * Upper bound on outstanding (reserved plus running) priming loads. Each entry is one
     * map key plus one queued runnable, so the bound caps both memory and storage pressure
     * from high-frequency placeholder refreshes.
     */
    public static final int MAX_PENDING = 256;

    private record Key(UUID owner, String currencyId) {
    }

    private final EconomyApi api;
    private final Executor background;
    private final ConcurrentHashMap<Key, Boolean> inFlight = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean closed;

    public PlaceholderBalanceLoader(EconomyApi api, Executor background) {
        this.api = Objects.requireNonNull(api, "api");
        this.background = Objects.requireNonNull(background, "background");
    }

    /**
     * Enqueue a background prime for one owner and currency.
     *
     * @return {@code true} when a prime is on its way (newly scheduled or merged into an
     *         in-flight load for the same key); {@code false} when the request was shed
     *         (loader closed or too many outstanding loads). Either way the caller must
     *         reply immediately without waiting.
     */
    public boolean request(UUID owner, String currencyId) {
        if (owner == null || currencyId == null) {
            return false;
        }
        if (closed) {
            return false;
        }
        Key key = new Key(owner, Currency.normalizeId(currencyId));
        // Fast merge before reserving a slot: under pressure, joiners of an in-flight load
        // must not consume the remaining queue budget.
        if (inFlight.containsKey(key)) {
            return true;
        }
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            return false;
        }
        if (closed) {
            pending.decrementAndGet();
            return false;
        }
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            pending.decrementAndGet();
            return true; // lost the race: another thread scheduled it first, share its load
        }
        try {
            background.execute(() -> prime(key));
        } catch (RuntimeException rejected) {
            inFlight.remove(key, Boolean.TRUE);
            pending.decrementAndGet();
            return false;
        }
        return true;
    }

    /** Outstanding (reserved plus running) priming loads. For diagnostics and tests. */
    public int pendingCount() {
        return pending.get();
    }

    /**
     * Stop dispatching new work. Queued loads that have not started yet are skipped; a load
     * already reading storage runs to completion and settles through the service's stamp
     * fence (a real storage call cannot be cancelled mid-flight). Idempotent.
     */
    public void close() {
        closed = true;
    }

    private void prime(Key key) {
        try {
            if (closed) {
                return;
            }
            try {
                api.primeBalanceIfAbsent(key.owner(), key.currencyId());
            } catch (RuntimeException ignored) {
                // Storage failure paths already dropped the entry inside the service; the
                // miss simply stays a miss until the next refresh retries.
            }
        } finally {
            inFlight.remove(key, Boolean.TRUE);
            pending.decrementAndGet();
        }
    }
}
