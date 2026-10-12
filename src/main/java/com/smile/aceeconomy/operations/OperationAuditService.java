package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.OperationAudit;
import com.smile.aceeconomy.ports.operations.OperationAuditStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Internal query boundary over the {@link OperationAuditStore}.
 *
 * <p>There is deliberately no public command or api.v2 surface for this: the
 * restore audit is traced through this internal service only (by reason text,
 * by snapshot id, by operator). Filters combine with AND, ordering is by
 * timestamp with a stable id tie-break, pagination is 0-based and invalid
 * values are rejected rather than clamped. The service never mutates the
 * store.
 */
public final class OperationAuditService {

    private final OperationAuditStore store;

    public OperationAuditService(OperationAuditStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Internal trace query. Never mutates the store.
     */
    public List<OperationAudit> query(OperationAuditQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.page() < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (query.limit() <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        List<OperationAudit> filtered = new ArrayList<>();
        for (OperationAudit record : store.listOperationAudits()) {
            if (query.operation() != null && !query.operation().equals(record.operation())) {
                continue;
            }
            if (query.reasonContains() != null && !query.reasonContains().isBlank()
                    && (record.reason() == null
                    || !record.reason().toLowerCase(java.util.Locale.ROOT)
                    .contains(query.reasonContains().toLowerCase(java.util.Locale.ROOT)))) {
                continue;
            }
            if (query.sourceBackupId() != null
                    && !query.sourceBackupId().equals(record.sourceBackupId())) {
                continue;
            }
            if (query.safetyBackupId() != null
                    && !query.safetyBackupId().equals(record.safetyBackupId())) {
                continue;
            }
            if (query.operatorId() != null && !query.operatorId().equals(record.operatorId())) {
                continue;
            }
            filtered.add(record);
        }

        Comparator<OperationAudit> order = Comparator.comparing(OperationAudit::timestamp)
                .thenComparing(OperationAudit::id);
        filtered.sort(query.ascending() ? order : order.reversed());

        // The offset is computed in long: page * limit can exceed int range, and an int
        // product would wrap back onto an earlier page instead of landing past the end.
        long start = (long) query.page() * query.limit();
        if (start >= filtered.size()) {
            return List.of();
        }
        int end = (int) Math.min(start + query.limit(), filtered.size());
        return List.copyOf(filtered.subList((int) start, end));
    }

    /** Every record in the live state, in stored order. */
    public List<OperationAudit> listAll() {
        return store.listOperationAudits();
    }
}
