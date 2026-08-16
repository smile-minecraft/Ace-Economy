package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Transaction;

import java.util.List;

/**
 * A page of audit/history records plus the total match count. The {@code entries} list is an
 * immutable copy so callers cannot mutate the query layer's internal state.
 */
public final class AuditPage {

    private final List<Transaction> entries;
    private final long total;
    private final int page;
    private final int limit;

    public AuditPage(List<Transaction> entries, long total, int page, int limit) {
        this.entries = List.copyOf(entries);
        this.total = total;
        this.page = page;
        this.limit = limit;
    }

    public List<Transaction> entries() {
        return entries;
    }

    public long total() {
        return total;
    }

    public int page() {
        return page;
    }

    public int limit() {
        return limit;
    }
}
