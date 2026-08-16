package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.TransactionType;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic filter for the audit/history query. Every field is optional; an unset field means
 * "no constraint". Results are ordered by timestamp with a stable {@code id} tie-break so the same
 * query always yields the same ordering.
 *
 * <p>Pagination uses a 0-based {@link #page()} and a strictly positive {@link #limit()}; the
 * {@link HistoryService} rejects invalid values rather than silently clamping.</p>
 */
public final class AuditQuery {

    private final UUID accountId;
    private final UUID counterparty;
    private final String currencyId;
    private final Set<TransactionType> types;
    private final String reasonContains;
    private final Instant from;
    private final Instant to;
    private final int page;
    private final int limit;
    private final boolean ascending;

    private AuditQuery(Builder b) {
        this.accountId = b.accountId;
        this.counterparty = b.counterparty;
        this.currencyId = b.currencyId;
        this.types = b.types == null ? Set.of() : Set.copyOf(b.types);
        this.reasonContains = b.reasonContains;
        this.from = b.from;
        this.to = b.to;
        this.page = b.page;
        this.limit = b.limit;
        this.ascending = b.ascending;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID counterparty() {
        return counterparty;
    }

    public String currencyId() {
        return currencyId;
    }

    public Set<TransactionType> types() {
        return types;
    }

    public String reasonContains() {
        return reasonContains;
    }

    public Instant from() {
        return from;
    }

    public Instant to() {
        return to;
    }

    public int page() {
        return page;
    }

    public int limit() {
        return limit;
    }

    public boolean ascending() {
        return ascending;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID accountId;
        private UUID counterparty;
        private String currencyId;
        private Set<TransactionType> types;
        private String reasonContains;
        private Instant from;
        private Instant to;
        private int page = 0;
        private int limit = 50;
        private boolean ascending = false;

        public Builder accountId(UUID v) {
            this.accountId = v;
            return this;
        }

        public Builder counterparty(UUID v) {
            this.counterparty = v;
            return this;
        }

        public Builder currencyId(String v) {
            this.currencyId = v;
            return this;
        }

        public Builder types(Set<TransactionType> v) {
            this.types = v;
            return this;
        }

        public Builder reasonContains(String v) {
            this.reasonContains = v;
            return this;
        }

        public Builder from(Instant v) {
            this.from = v;
            return this;
        }

        public Builder to(Instant v) {
            this.to = v;
            return this;
        }

        public Builder page(int v) {
            this.page = v;
            return this;
        }

        public Builder limit(int v) {
            this.limit = v;
            return this;
        }

        public Builder ascending(boolean v) {
            this.ascending = v;
            return this;
        }

        public AuditQuery build() {
            if (page < 0) {
                throw new IllegalArgumentException("page must be >= 0");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be > 0");
            }
            return new AuditQuery(this);
        }
    }
}
