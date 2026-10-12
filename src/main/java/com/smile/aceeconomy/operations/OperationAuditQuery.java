package com.smile.aceeconomy.operations;

import java.util.UUID;

/**
 * Deterministic filter for the internal operation-audit query. Every field is
 * optional; an unset field means "no constraint". Results are ordered by
 * timestamp with a stable id tie-break so the same query always yields the
 * same ordering. Pagination is 0-based.
 */
public final class OperationAuditQuery {

    private final String operation;
    private final String reasonContains;
    private final String sourceBackupId;
    private final String safetyBackupId;
    private final UUID operatorId;
    private final int page;
    private final int limit;
    private final boolean ascending;

    private OperationAuditQuery(Builder builder) {
        this.operation = builder.operation;
        this.reasonContains = builder.reasonContains;
        this.sourceBackupId = builder.sourceBackupId;
        this.safetyBackupId = builder.safetyBackupId;
        this.operatorId = builder.operatorId;
        this.page = builder.page;
        this.limit = builder.limit;
        this.ascending = builder.ascending;
    }

    public String operation() {
        return operation;
    }

    public String reasonContains() {
        return reasonContains;
    }

    public String sourceBackupId() {
        return sourceBackupId;
    }

    public String safetyBackupId() {
        return safetyBackupId;
    }

    public UUID operatorId() {
        return operatorId;
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
        private String operation;
        private String reasonContains;
        private String sourceBackupId;
        private String safetyBackupId;
        private UUID operatorId;
        private int page = 0;
        private int limit = 50;
        private boolean ascending = false;

        public Builder operation(String value) {
            this.operation = value;
            return this;
        }

        public Builder reasonContains(String value) {
            this.reasonContains = value;
            return this;
        }

        public Builder sourceBackupId(String value) {
            this.sourceBackupId = value;
            return this;
        }

        public Builder safetyBackupId(String value) {
            this.safetyBackupId = value;
            return this;
        }

        public Builder operatorId(UUID value) {
            this.operatorId = value;
            return this;
        }

        public Builder page(int value) {
            this.page = value;
            return this;
        }

        public Builder limit(int value) {
            this.limit = value;
            return this;
        }

        public Builder ascending(boolean value) {
            this.ascending = value;
            return this;
        }

        public OperationAuditQuery build() {
            if (page < 0) {
                throw new IllegalArgumentException("page must be >= 0");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be > 0");
            }
            return new OperationAuditQuery(this);
        }
    }
}
