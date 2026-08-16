package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.operations.RollbackError;
import com.smile.aceeconomy.ports.operations.ReversalExecutor;
import com.smile.aceeconomy.ports.operations.ReversalOutcome;
import com.smile.aceeconomy.ports.operations.ReversalPlan;

/** [TEST:P3] 測試替身：永遠失敗，用於演練 rollback 失敗路徑。 */
public final class FailingReversalExecutor implements ReversalExecutor {

    private final RollbackError error;
    private final String message;

    public FailingReversalExecutor(RollbackError error, String message) {
        this.error = error;
        this.message = message;
    }

    @Override
    public ReversalOutcome execute(ReversalPlan plan) {
        return ReversalOutcome.failure(error, message);
    }
}
