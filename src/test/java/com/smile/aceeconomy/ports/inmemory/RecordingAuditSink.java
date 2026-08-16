package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.AuditException;
import com.smile.aceeconomy.ports.AuditSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Recording {@link AuditSink} for tests. Can be configured to fail on the next record. */
public final class RecordingAuditSink implements AuditSink {

    private final List<Transaction> recorded = new CopyOnWriteArrayList<>();
    private volatile boolean failNext = false;

    public List<Transaction> recorded() {
        return recorded;
    }

    public void setFailOnNextRecord(boolean fail) {
        this.failNext = fail;
    }

    @Override
    public void record(Transaction transaction) throws AuditException {
        if (failNext) {
            throw new AuditException("simulated audit failure");
        }
        recorded.add(transaction);
    }
}
