package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed audit/history query boundary over the v2 {@link TransactionRepository}.
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>Filters are applied deterministically and combined with AND.</li>
 *   <li>Ordering is by timestamp, with a stable {@link UUID} tie-break, so results are reproducible.</li>
 *   <li>Pagination is 0-based; invalid {@code page}/{@code limit} is rejected rather than clamped.</li>
 *   <li>Read-only: this service never mutates the repository.</li>
 * </ul>
 */
public final class HistoryService {

    private final TransactionRepository transactions;

    public HistoryService(TransactionRepository transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public AuditPage query(AuditQuery q) {
        Objects.requireNonNull(q, "query");
        List<Transaction> all;
        try {
            all = transactions.loadAll();
        } catch (RuntimeException e) {
            throw e;
        }

        List<Transaction> filtered = new ArrayList<>();
        for (Transaction t : all) {
            if (q.accountId() != null && !Objects.equals(t.accountId(), q.accountId())) {
                continue;
            }
            if (q.counterparty() != null && !Objects.equals(t.counterparty(), q.counterparty())) {
                continue;
            }
            if (q.currencyId() != null
                    && !Currency.normalizeId(q.currencyId()).equals(Currency.normalizeId(t.currencyId()))) {
                continue;
            }
            if (!q.types().isEmpty() && !q.types().contains(t.type())) {
                continue;
            }
            if (q.reasonContains() != null && !q.reasonContains().isBlank()
                    && (t.reason() == null || !t.reason().toLowerCase().contains(q.reasonContains().toLowerCase()))) {
                continue;
            }
            if (q.from() != null && t.timestamp().isBefore(q.from())) {
                continue;
            }
            if (q.to() != null && t.timestamp().isAfter(q.to())) {
                continue;
            }
            filtered.add(t);
        }

        Comparator<Transaction> byTime = Comparator.comparing(Transaction::timestamp);
        Comparator<Transaction> byId = Comparator.comparing(Transaction::id);
        Comparator<Transaction> order = byTime.thenComparing(byId);
        filtered.sort(q.ascending() ? order : order.reversed());

        long total = filtered.size();
        int start = q.page() * q.limit();
        if (start < 0 || start > filtered.size()) {
            return new AuditPage(List.of(), total, q.page(), q.limit());
        }
        int end = Math.min(start + q.limit(), filtered.size());
        return new AuditPage(filtered.subList(start, end), total, q.page(), q.limit());
    }
}
