package com.smile.aceeconomy.application;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-account lock registry. Transfers acquire both account locks in deterministic
 * (lexical UUID) order to prevent deadlock. Uses reentrant locks so a repeated
 * lock on the same account (e.g. a defensive same-account call) is safe.
 */
public final class LockRegistry {

    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lockFor(UUID uuid) {
        return locks.computeIfAbsent(uuid, k -> new ReentrantLock());
    }

    /** Acquire both account locks in deterministic (lexical UUID) order. */
    public void lockBoth(UUID a, UUID b) {
        UUID first = min(a, b);
        UUID second = first.equals(a) ? b : a;
        lockFor(first).lock();
        lockFor(second).lock();
    }

    /** Release both account locks in the reverse of acquisition order. */
    public void unlockBoth(UUID a, UUID b) {
        UUID first = min(a, b);
        UUID second = first.equals(a) ? b : a;
        lockFor(second).unlock();
        lockFor(first).unlock();
    }

    private static UUID min(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
