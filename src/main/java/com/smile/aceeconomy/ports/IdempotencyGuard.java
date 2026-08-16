package com.smile.aceeconomy.ports;

import java.util.UUID;

/**
 * Replay / idempotency guard for banknote redemption. A banknote's nonce must be consumed exactly
 * once; a second attempt with the same nonce is a replay and must be rejected. The production
 * binding persists consumed nonces (transaction/idempotency port); tests use an in-memory fake.
 */
public interface IdempotencyGuard {

    /**
     * Record consumption of a nonce.
     *
     * @param nonce the banknote nonce
     * @return {@code true} when this is the first time the nonce is seen (accept), {@code false}
     *         when it was already consumed (replay — reject)
     */
    boolean consume(UUID nonce);

    /**
     * @param nonce the banknote nonce
     * @return {@code true} when the nonce was already consumed (i.e. a replay would be rejected)
     */
    boolean isConsumed(UUID nonce);
}
