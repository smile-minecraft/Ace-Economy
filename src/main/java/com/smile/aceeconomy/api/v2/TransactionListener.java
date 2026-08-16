package com.smile.aceeconomy.api.v2;

import com.smile.aceeconomy.domain.TransactionEvent;

/** Consumer-facing listener for v2 pre-commit transaction events. */
@FunctionalInterface
public interface TransactionListener {

    void onPreCommit(TransactionEvent event);
}
