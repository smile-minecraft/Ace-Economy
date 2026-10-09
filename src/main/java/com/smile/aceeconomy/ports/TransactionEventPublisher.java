package com.smile.aceeconomy.ports;

import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionEvent;

/** Publishes pre-commit transaction events. Implementations must not import vendor code. */
public interface TransactionEventPublisher {

    /**
     * Publish a pre-commit event. Registered listeners may cancel it.
     *
     * <p>Implementations must isolate listener failures: a throwing listener cancels the
     * event and leaves a diagnostic instead of propagating, so the caller always gets a
     * typed rejection rather than an escaped exception.
     *
     * @return the same event instance, possibly cancelled
     */
    TransactionEvent publishPreCommit(TransactionEvent event);

    /**
     * Publish an already-committed transaction to post-commit observers. Only called after
     * the commit (balances and audit records) succeeded; never called for cancelled or
     * uncommitted operations. Listener failures are swallowed with a diagnostic and never
     * change the committed result.
     */
    void publishPostCommit(Transaction transaction);
}
