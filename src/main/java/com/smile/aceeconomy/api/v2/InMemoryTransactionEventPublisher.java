package com.smile.aceeconomy.api.v2;

import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionEvent;
import com.smile.aceeconomy.ports.CommittedTransactionListener;
import com.smile.aceeconomy.ports.TransactionEventPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * In-memory transaction event publisher with separated pre-commit and post-commit paths.
 *
 * <ul>
 *   <li><b>Pre-commit</b> ({@link #publishPreCommit}) is cancellable. A throwing listener
 *       cancels the event and leaves a diagnostic; the exception never escapes, so the
 *       caller always maps it to a typed rejection instead of letting a broken observer
 *       approve a transaction by accident.</li>
 *   <li><b>Post-commit</b> ({@link #publishPostCommit}) is a non-cancellable observation
 *       fired only after the commit succeeded. Throwing observers are swallowed with a
 *       diagnostic and can never change the committed result.</li>
 * </ul>
 */
public final class InMemoryTransactionEventPublisher implements TransactionEventPublisher {

    private static final Logger LOG = Logger.getLogger(InMemoryTransactionEventPublisher.class.getName());

    private final List<TransactionListener> preCommitListeners = new CopyOnWriteArrayList<>();
    private final List<CommittedTransactionListener> postCommitListeners = new CopyOnWriteArrayList<>();
    private final Consumer<String> diagnostics;

    public InMemoryTransactionEventPublisher() {
        this(LOG::warning);
    }

    public InMemoryTransactionEventPublisher(Consumer<String> diagnostics) {
        this.diagnostics = diagnostics != null ? diagnostics : LOG::warning;
    }

    public void register(TransactionListener listener) {
        preCommitListeners.add(listener);
    }

    public void unregister(TransactionListener listener) {
        preCommitListeners.remove(listener);
    }

    public void registerPostCommit(CommittedTransactionListener listener) {
        postCommitListeners.add(listener);
    }

    public void unregisterPostCommit(CommittedTransactionListener listener) {
        postCommitListeners.remove(listener);
    }

    @Override
    public TransactionEvent publishPreCommit(TransactionEvent event) {
        for (TransactionListener l : preCommitListeners) {
            try {
                l.onPreCommit(event);
            } catch (Throwable t) {
                try {
                    event.cancel();
                } catch (Throwable ignored) {
                    // cancel() never throws; guard against a hostile TransactionEvent only.
                }
                emit("pre-commit listener failed: " + t);
            }
        }
        return event;
    }

    @Override
    public void publishPostCommit(Transaction transaction) {
        if (transaction == null) {
            return;
        }
        for (CommittedTransactionListener l : postCommitListeners) {
            try {
                l.onCommitted(transaction);
            } catch (Throwable t) {
                emit("post-commit listener failed: " + t);
            }
        }
    }

    private void emit(String message) {
        try {
            diagnostics.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics delivery is best-effort and must never break the publish path.
        }
    }
}
