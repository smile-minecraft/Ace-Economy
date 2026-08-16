package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.operations.RollbackCategory;
import com.smile.aceeconomy.ports.operations.ReversalExecutor;
import com.smile.aceeconomy.ports.operations.ReversalOutcome;
import com.smile.aceeconomy.ports.operations.ReversalPlan;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** [TEST:P3] 測試替身：記錄最後一份 {@link ReversalPlan} 並永遠成功。 */
public final class CapturingReversalExecutor implements ReversalExecutor {

    private ReversalPlan lastPlan;
    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public ReversalOutcome execute(ReversalPlan plan) {
        this.lastPlan = plan;
        callCount.incrementAndGet();
        return ReversalOutcome.success(List.of(UUID.randomUUID()));
    }

    public ReversalPlan lastPlan() {
        return lastPlan;
    }

    public int callCount() {
        return callCount.get();
    }

    public RollbackCategory lastCategory() {
        return lastPlan == null ? null : lastPlan.category();
    }
}
