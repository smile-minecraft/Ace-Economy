package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.ports.IdempotencyGuard;
import com.smile.aceeconomy.ports.persistence.NonceStore;

import java.util.Objects;
import java.util.UUID;

/**
 * Production {@link IdempotencyGuard} backed by a durable {@link NonceStore}, so consumed
 * banknote nonces survive process restarts and backend rebuilds instead of being forgotten
 * with the process heap.
 *
 * <p>Persistence failures propagate as {@code PersistenceException} (a RuntimeException)
 * rather than being collapsed into an accept/reject decision: an undecided consume must never
 * silently look like a fresh nonce.</p>
 */
public final class PersistentIdempotencyGuard implements IdempotencyGuard {

    private final NonceStore store;

    public PersistentIdempotencyGuard(NonceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public boolean consume(UUID nonce) {
        return store.consume(nonce);
    }

    @Override
    public boolean isConsumed(UUID nonce) {
        return store.isConsumed(nonce);
    }
}
