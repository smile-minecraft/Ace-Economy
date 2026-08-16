package com.smile.aceeconomy.ports;

import com.smile.aceeconomy.domain.TransactionEvent;

/** Publishes pre-commit transaction events. Implementations must not import vendor code. */
public interface TransactionEventPublisher {

    /**
     * Publish a pre-commit event. Registered listeners may cancel it.
     *
     * @return the same event instance, possibly cancelled
     */
    TransactionEvent publishPreCommit(TransactionEvent event);
}
