package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.ports.IdempotencyGuard;
import com.smile.aceeconomy.ports.persistence.NonceStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract for the production {@link PersistentIdempotencyGuard} adapter. */
final class PersistentIdempotencyGuardTest {

    /** Recording fake standing in for a real backend NonceStore. */
    private static final class RecordingNonceStore implements NonceStore {
        final Set<UUID> consumed = new HashSet<>();
        boolean fail = false;

        @Override
        public boolean consume(UUID nonce) {
            if (fail) {
                throw new PersistenceException("injected store failure");
            }
            return consumed.add(nonce);
        }

        @Override
        public boolean isConsumed(UUID nonce) {
            if (fail) {
                throw new PersistenceException("injected store failure");
            }
            return consumed.contains(nonce);
        }
    }

    @Test
    void delegatesConsumeAndIsConsumedToTheStore() {
        RecordingNonceStore store = new RecordingNonceStore();
        IdempotencyGuard guard = new PersistentIdempotencyGuard(store);
        UUID nonce = UUID.randomUUID();

        assertFalse(guard.isConsumed(nonce));
        assertTrue(guard.consume(nonce), "first consume must win");
        assertFalse(guard.consume(nonce), "second consume is a replay");
        assertTrue(guard.isConsumed(nonce));
        assertEquals(Set.of(nonce), store.consumed);
    }

    @Test
    void storeFailuresPropagateInsteadOfDeciding() {
        RecordingNonceStore store = new RecordingNonceStore();
        store.fail = true;
        IdempotencyGuard guard = new PersistentIdempotencyGuard(store);

        // An undecided consume must never silently look like a fresh nonce.
        assertThrows(PersistenceException.class, () -> guard.consume(UUID.randomUUID()));
        assertThrows(PersistenceException.class, () -> guard.isConsumed(UUID.randomUUID()));
    }

    @Test
    void rejectsNullStore() {
        assertThrows(NullPointerException.class, () -> new PersistentIdempotencyGuard(null));
    }
}
