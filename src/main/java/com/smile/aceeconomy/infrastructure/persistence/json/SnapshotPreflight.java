package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Restore preflight for v2 logical snapshots.
 *
 * <p>Materializes a snapshot through the SAME parsing paths the backends use at load time
 * ({@link JsonModel} parsing, account construction, transaction domain conversion) so every
 * corruption class that would only surface after a restore is rejected BEFORE any live state
 * is touched: malformed JSON, incompatible schema versions, invalid records (bad UUIDs,
 * amounts, types, timestamps), duplicate transaction ids and currencies outside the configured
 * allowlist. Currency ids are matched against an explicit allowlist instead of a denylist so
 * legitimate currency ids (including names like {@code token}) are never misjudged.</p>
 *
 * <p>This validator adds no new backend semantics; it only runs the existing ones early.</p>
 */
public final class SnapshotPreflight {

    private SnapshotPreflight() {
    }

    /**
     * Parse the snapshot JSON. Only well-formedness is checked here.
     *
     * @throws PersistenceException when the text is not a valid v2 JSON object
     */
    public static JsonModel parse(String json) throws PersistenceException {
        return JsonModel.fromJson(json);
    }

    /**
     * @throws PersistenceException when the model schema version differs from the running v2 contract
     */
    public static void checkSchemaVersion(JsonModel model) throws PersistenceException {
        if (model.schemaVersion != JsonModel.SCHEMA_VERSION) {
            throw new PersistenceException("Backup schema version " + model.schemaVersion
                    + " incompatible with expected " + JsonModel.SCHEMA_VERSION);
        }
    }

    /**
     * Materialize every record through the same conversions the backends use at load time and
     * reject duplicates, so a snapshot that would fail mid-restore never reaches the backend.
     *
     * @throws PersistenceException when any account or transaction record is invalid
     */
    public static void validateRecords(JsonModel model) throws PersistenceException {
        for (Map.Entry<String, JsonModel.JsonAccount> entry : model.accounts.entrySet()) {
            try {
                UUID.fromString(entry.getKey());
                Map<String, com.smile.aceeconomy.domain.Amount> balances = new LinkedHashMap<>();
                for (Map.Entry<String, String> b : entry.getValue().balances.entrySet()) {
                    balances.put(b.getKey(), JsonModel.stringToAmount(b.getValue()));
                }
                // Account.create enforces the same invariants the JSON backend applies on load.
                Account.create(UUID.fromString(entry.getValue().owner),
                        entry.getValue().ownerName, balances);
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid account record in snapshot: " + entry.getKey()
                                + " (" + e.getMessage() + ")");
            }
        }
        Set<String> seenTransactionIds = new HashSet<>();
        for (JsonModel.JsonTransaction t : model.transactions) {
            Transaction domain;
            try {
                // Same conversion JsonPersistenceBackend/SqlBackend apply when loading rows.
                domain = t.toDomain();
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid transaction record in snapshot: " + t.id
                                + " (" + e.getMessage() + ")");
            }
            if (!seenTransactionIds.add(domain.id().toString())) {
                throw new PersistenceException(
                        "Duplicate transaction id in snapshot: " + domain.id());
            }
        }
    }

    /**
     * @param allowedCurrencyIds every currency id the running configuration knows;
     *                           {@code null} skips the currency check (backup sanity path)
     * @throws PersistenceException when the snapshot references an unknown currency
     */
    public static void checkCurrencies(JsonModel model, Set<String> allowedCurrencyIds)
            throws PersistenceException {
        if (allowedCurrencyIds == null) {
            return;
        }
        Set<String> unknown = new HashSet<>();
        for (JsonModel.JsonAccount account : model.accounts.values()) {
            for (String currencyId : account.balances.keySet()) {
                if (!allowedCurrencyIds.contains(currencyId)) {
                    unknown.add(currencyId);
                }
            }
        }
        for (JsonModel.JsonTransaction t : model.transactions) {
            if (!allowedCurrencyIds.contains(t.currencyId)) {
                unknown.add(t.currencyId);
            }
        }
        if (!unknown.isEmpty()) {
            throw new PersistenceException(
                    "Snapshot references currencies unknown to this server's configuration: "
                            + unknown);
        }
    }

    /**
     * Convenience composition: parse + schema + records + currencies in one call.
     *
     * @throws PersistenceException when the snapshot must not be restored
     */
    public static void validate(String json, Set<String> allowedCurrencyIds)
            throws PersistenceException {
        JsonModel model = parse(json);
        checkSchemaVersion(model);
        validateRecords(model);
        checkCurrencies(model, allowedCurrencyIds);
    }
}
