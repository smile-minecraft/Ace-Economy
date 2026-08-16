package com.smile.aceeconomy.ports;

import com.smile.aceeconomy.domain.Transaction;

/** Audit sink for committed transactions. Implementations may throw {@link AuditException}. */
public interface AuditSink {

    void record(Transaction transaction) throws AuditException;
}
