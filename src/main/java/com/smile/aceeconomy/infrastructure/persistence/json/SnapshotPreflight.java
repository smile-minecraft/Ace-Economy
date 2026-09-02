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
        // Nonces must be canonical lowercase UUIDs; otherwise a non-canonical key such as
        // "1-1-1-1-1" would be stored verbatim and later isConsumed(canonical) would miss it.
        for (String nonceKey : model.nonces.keySet()) {
            try {
                UUID parsed = UUID.fromString(nonceKey);
                if (!parsed.toString().equals(nonceKey)) {
                    throw new PersistenceException(
                            "Invalid nonce record in snapshot: non-canonical UUID " + nonceKey);
                }
            } catch (IllegalArgumentException e) {
                throw new PersistenceException("Invalid nonce record in snapshot: " + nonceKey, e);
            }
        }
        Set<UUID> seenOwners = new HashSet<>();
        for (Map.Entry<String, JsonModel.JsonAccount> entry : model.accounts.entrySet()) {
            try {
                String rawKey = entry.getKey();
                String rawOwner = entry.getValue().owner;
                UUID keyUuid = requireCanonicalUUID(rawKey, "account map key");
                UUID ownerUuid = requireCanonicalUUID(rawOwner, "account owner");
                if (!keyUuid.equals(ownerUuid)) {
                    throw new PersistenceException(
                            "Account map key " + entry.getKey()
                                    + " does not match owner field " + entry.getValue().owner);
                }
                if (!seenOwners.add(ownerUuid)) {
                    throw new PersistenceException(
                            "Duplicate owner identity in snapshot: " + ownerUuid);
                }
                Map<String, com.smile.aceeconomy.domain.Amount> balances = new LinkedHashMap<>();
                for (Map.Entry<String, String> b : entry.getValue().balances.entrySet()) {
                    balances.put(b.getKey(), JsonModel.stringToAmount(b.getValue()));
                }
                // Account.create enforces the same invariants the JSON backend applies on load.
                Account.create(ownerUuid, entry.getValue().ownerName, balances);
            } catch (PersistenceException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid account record in snapshot: " + entry.getKey()
                                + " (" + e.getMessage() + ")", e);
            }
        }
        Set<String> seenTransactionIds = new HashSet<>();
        for (JsonModel.JsonTransaction t : model.transactions) {
            // Enforce canonical lowercase UUID form for all transaction UUID fields; a non-canonical
            // representation would otherwise be accepted by UUID.fromString but stored in a different
            // textual form, splitting the identity and breaking replay detection or lookups.
            try {
                requireCanonicalUUID(t.id, "transaction id");
                requireCanonicalUUID(t.accountId, "transaction accountId");
                if (t.counterparty != null) {
                    requireCanonicalUUID(t.counterparty, "transaction counterparty");
                }
            } catch (PersistenceException e) {
                throw new PersistenceException(
                        "Invalid transaction record in snapshot: " + t.id + " (" + e.getMessage() + ")", e);
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid transaction record in snapshot: " + t.id + " (" + e.getMessage() + ")", e);
            }
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

    private static UUID requireCanonicalUUID(String raw, String field) {
        if (raw == null) {
            throw new PersistenceException("Invalid " + field + ": expected canonical UUID string, got null");
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new PersistenceException("Invalid " + field + ": malformed UUID " + raw, e);
        }
        if (!parsed.toString().equals(raw)) {
            throw new PersistenceException(
                    "Invalid " + field + ": non-canonical UUID " + raw + " (expected " + parsed + ")");
        }
        return parsed;
    }
}
