package com.smile.aceeconomy.ports.operations;

import com.smile.aceeconomy.domain.Amount;

import java.util.UUID;

/**
 * A single normalized import record. The operations layer accepts these instead of importing
 * vendor classes directly: parsing of Essentials/CMI export files and vendor-file discovery live
 * outside this isolated slice and are expected to produce {@code ImportRecord} values.
 */
public final class ImportRecord {

    private final ImportSource source;
    private final String sourceRecordId; // idempotency key within the source
    private final UUID accountUuid;
    private final String ownerName;      // nullable; falls back to the uuid string
    private final String currencyId;
    private final Amount amount;         // target balance to set for the currency

    public ImportRecord(ImportSource source, String sourceRecordId, UUID accountUuid,
                        String ownerName, String currencyId, Amount amount) {
        if (source == null) {
            throw new IllegalArgumentException("ImportRecord.source must not be null");
        }
        if (sourceRecordId == null || sourceRecordId.isBlank()) {
            throw new IllegalArgumentException("ImportRecord.sourceRecordId must not be blank");
        }
        if (accountUuid == null) {
            throw new IllegalArgumentException("ImportRecord.accountUuid must not be null");
        }
        if (currencyId == null || currencyId.isBlank()) {
            throw new IllegalArgumentException("ImportRecord.currencyId must not be blank");
        }
        if (amount == null) {
            throw new IllegalArgumentException("ImportRecord.amount must not be null");
        }
        this.source = source;
        this.sourceRecordId = sourceRecordId;
        this.accountUuid = accountUuid;
        this.ownerName = ownerName;
        this.currencyId = currencyId;
        this.amount = amount;
    }

    public ImportSource source() {
        return source;
    }

    public String sourceRecordId() {
        return sourceRecordId;
    }

    public UUID accountUuid() {
        return accountUuid;
    }

    public String ownerName() {
        return ownerName;
    }

    public String currencyId() {
        return currencyId;
    }

    public Amount amount() {
        return amount;
    }
}
