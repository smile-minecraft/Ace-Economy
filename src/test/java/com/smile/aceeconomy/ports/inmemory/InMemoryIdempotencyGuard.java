package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.ports.IdempotencyGuard;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IdempotencyGuard} for tests. A nonce is accepted exactly once; a second
 * {@link #consume(UUID)} returns {@code false}, modelling replay rejection.
 */
public final class InMemoryIdempotencyGuard implements IdempotencyGuard {

    private final Set<UUID> consumed = ConcurrentHashMap.newKeySet();

    @Override
    public boolean consume(UUID nonce) {
        return consumed.add(nonce);
    }

    @Override
    public boolean isConsumed(UUID nonce) {
        return consumed.contains(nonce);
    }
}
