package com.smile.aceeconomy.ports.persistence;

import java.util.UUID;

/**
 * Durable first-writer-wins store for single-use keys (banknote nonces).
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>{@link #consume} returns {@code true} exactly once per key for the lifetime of the
 *       stored records — across process restarts and backend rebuilds on the same storage.</li>
 *   <li>Concurrent consumers of the same key observe exactly one {@code true}
 *       (first-writer-wins).</li>
 *   <li>Consumed keys are never deleted implicitly; malformed stored records must fail fast
 *       instead of being silently dropped or ignored.</li>
 * </ul>
 *
 * <p>No vendor imports; implemented in {@code infrastructure.persistence} per backend.</p>
 */
public interface NonceStore {

    /**
     * Record consumption of a key.
     *
     * @return {@code true} when this call is the first consumption of {@code nonce};
     *         {@code false} when it was already consumed
     */
    boolean consume(UUID nonce) throws PersistenceException;

    /** @return {@code true} when {@code nonce} was already consumed. */
    boolean isConsumed(UUID nonce) throws PersistenceException;
}
