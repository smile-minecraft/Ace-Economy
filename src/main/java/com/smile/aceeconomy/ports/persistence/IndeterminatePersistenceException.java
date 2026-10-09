package com.smile.aceeconomy.ports.persistence;

/**
 * Thrown when a storage write was sent but its outcome can no longer be proven either
 * way — the classic case is a {@code commit()} that throws after the server may already
 * have committed, where a later rollback cannot prove the transaction did not commit.
 *
 * <p>{@link #isCommitted()} reports {@code false}: commitment was never confirmed, so
 * old callers keep their previous reading. That reading must not be mistaken for proof
 * of rollback — this type is <em>neither</em> proven-committed nor proven-uncommitted.
 * Boundaries must check for this type <em>before</em> {@code isCommitted()}: it is
 * never safe to retry blindly, and never honest to report as an ordinary failure.</p>
 */
public final class IndeterminatePersistenceException extends PersistenceException {

    public IndeterminatePersistenceException(String message, Throwable cause) {
        super(message, cause, false);
    }
}
