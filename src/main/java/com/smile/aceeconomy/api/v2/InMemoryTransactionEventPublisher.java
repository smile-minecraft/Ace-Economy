package com.smile.aceeconomy.api.v2;

import com.smile.aceeconomy.domain.TransactionEvent;
import com.smile.aceeconomy.ports.TransactionEventPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory pre-commit event publisher for the v2 native API. */
public final class InMemoryTransactionEventPublisher implements TransactionEventPublisher {

    private final List<TransactionListener> listeners = new CopyOnWriteArrayList<>();

    public void register(TransactionListener listener) {
        listeners.add(listener);
    }

    public void unregister(TransactionListener listener) {
        listeners.remove(listener);
    }

    @Override
    public TransactionEvent publishPreCommit(TransactionEvent event) {
        for (TransactionListener l : listeners) {
            l.onPreCommit(event);
        }
        return event;
    }
}
