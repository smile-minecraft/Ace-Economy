package com.smile.aceeconomy.ports;

import com.smile.aceeconomy.domain.Transaction;

/**
 * Non-cancellable post-commit observer for committed transactions.
 *
 * <p>Unlike the pre-commit event, a committed {@link Transaction} cannot be cancelled or
 * modified: listeners only observe what has already been durably stored. The publisher
 * calls each listener after the commit succeeds and swallows listener failures so a
 * broken observer can never change the committed result.
 */
@FunctionalInterface
public interface CommittedTransactionListener {

    void onCommitted(Transaction transaction);
}
