package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.AmountCap;
import com.smile.aceeconomy.domain.OperationAudit;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Restore preflight for v2 logical snapshots.
 *
 * <p>Materializes a snapshot through the SAME parsing paths the backends use at load time
 * ({@link JsonModel} parsing, account construction, transaction domain conversion) so every
 * corruption class that would only surface after a restore is rejected BEFORE any live state
 * is touched: malformed JSON, incompatible schema versions, invalid records (bad UUIDs,
 * amounts, types, timestamps), duplicate transaction ids and currencies outside the configured
 * allowlist. Currency ids are matched against an explicit allowlist instead of a denylist so
 * legitimate currency ids (including names like {@code token}) are never misjudged.
 * Balances above the public amount cap are <em>not</em> rejected here: plain loads and the
 * backup-sanity check stay lenient by design, and only {@code restore()} adds the
 * restore-only gate ({@link #checkRestoreCaps}) before touching live state.</p>
 *
 * <p>This validator adds no new backend semantics; it only runs the existing ones early.</p>
 */
public final class SnapshotPreflight {

    private static final Pattern SAFE_BACKUP_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

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
                if (t.operatorId != null) {
                    requireCanonicalUUID(t.operatorId, "transaction operatorId");
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
        validateIssuances(model, seenTransactionIds);
        validateOperationAudits(model);
    }

    /**
     * Joint preflight over the durable issuance collection. Every record is
     * materialized through the same strict conversion the backends use at load
     * time, so a snapshot carrying a corrupt issuance is refused before any
     * live row moves. An absent collection (files written before issuance
     * tracking) reads as empty and passes. Presence rules beyond the field
     * shapes: the map key must be the record's canonical nonce, an
     * evidence-carrying record must reference a transaction id present in the
     * same snapshot (a committed deduction without its durable debit row is
     * corruption, never resolved by inventing a receipt), and one debit
     * transaction id must back at most one nonce.
     */
    private static void validateIssuances(JsonModel model, Set<String> seenTransactionIds)
            throws PersistenceException {
        Set<String> seenDebitIds = new HashSet<>();
        for (Map.Entry<String, JsonModel.JsonIssuance> entry : model.issuances.entrySet()) {
            String rawKey = entry.getKey();
            UUID keyUuid = requireCanonicalUUID(rawKey, "issuance map key");
            com.smile.aceeconomy.domain.BanknoteIssuance issuance;
            try {
                issuance = entry.getValue().toDomain();
            } catch (PersistenceException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid issuance record in snapshot: " + rawKey
                                + " (" + e.getMessage() + ")", e);
            }
            if (!keyUuid.equals(issuance.nonce())) {
                throw new PersistenceException(
                        "Issuance map key " + rawKey + " does not match nonce field "
                                + issuance.nonce());
            }
            String debitId = issuance.debitTransactionId();
            if (debitId != null) {
                UUID parsedDebit;
                try {
                    parsedDebit = UUID.fromString(debitId);
                } catch (IllegalArgumentException e) {
                    throw new PersistenceException(
                            "Invalid issuance debit reference in snapshot for nonce "
                                    + rawKey, e);
                }
                if (!parsedDebit.toString().equals(debitId)) {
                    throw new PersistenceException(
                            "Invalid issuance debit reference in snapshot for nonce "
                                    + rawKey + ": non-canonical UUID");
                }
                if (!seenTransactionIds.contains(debitId)) {
                    throw new PersistenceException(
                            "Issuance record in snapshot references a missing debit transaction"
                                    + " for nonce " + rawKey
                                    + "; a committed deduction without its durable debit row"
                                    + " is corruption and is never resolved by inventing a receipt");
                }
                if (!seenDebitIds.add(debitId)) {
                    throw new PersistenceException(
                            "One debit transaction backs two issuance nonces in snapshot;"
                                    + " the second binding is corruption");
                }
            }
        }
    }

    /**
     * Joint preflight over the independent restore-audit collection. Every
     * record is materialized through the same conversion the backends use at
     * load time, so a snapshot carrying a corrupt operation record is refused
     * before any live row moves. An absent collection (files written before
     * operation tracking) reads as empty and passes; a present record must
     * carry a canonical id, the known operation, a consistent operator
     * identity, a policy-legal reason, an ISO instant and legal backup ids,
     * with no duplicate ids. Transaction reasons are deliberately not
     * re-validated here: legacy rows keep loading verbatim.
     *
     * @throws PersistenceException when any operation record must not be published
     */
    /**
     * Joint preflight over the independent restore-audit collection. Every
     * record is materialized through the same conversion the backends use at
     * load time, so a snapshot carrying a corrupt operation record is refused
     * before any live row moves. An absent collection (files written before
     * operation tracking) reads as empty and passes; a present record must
     * carry a canonical id, the known operation, a consistent operator
     * identity, a policy-legal reason, an ISO instant and legal backup ids,
     * with no duplicate ids. Transaction reasons are deliberately not
     * re-validated here: legacy rows keep loading verbatim.
     *
     * <p>Diagnostics never echo a field value: every refusal names only the
     * record index and the field, with a fixed detail. A corrupt field may
     * itself carry a secret, and the parsing failures underneath (UUID,
     * instant) quote the offending text, so their exceptions are never
     * attached as causes and never interpolated into a message. The only
     * attached cause is the reason-policy rejection, whose message is a fixed
     * sentence that carries no input.
     *
     * @throws PersistenceException when any operation record must not be published
     */
    static void validateOperationAudits(JsonModel model) throws PersistenceException {
        Set<String> seenOperationIds = new HashSet<>();
        List<JsonModel.JsonOperationAudit> audits = model.operationAudits;
        for (int index = 0; index < audits.size(); index++) {
            JsonModel.JsonOperationAudit audit = audits.get(index);
            try {
                requireOperationUuid(audit.id, "id", index);
                if (!OperationAudit.OPERATION_RESTORE.equals(audit.operation)) {
                    throw operationAuditError(index, "unsupported operation");
                }
                if (audit.operatorId != null) {
                    requireOperationUuid(audit.operatorId, "operatorId", index);
                }
                if (audit.operatorName == null || audit.operatorName.isBlank()) {
                    throw operationAuditError(index, "operator name must not be blank");
                }
                if (audit.operatorId == null) {
                    if (!AdminAudit.CONSOLE_OPERATOR_NAME.equals(audit.operatorName)) {
                        throw operationAuditError(index,
                                "null operator id requires the console marker");
                    }
                } else if (AdminAudit.CONSOLE_OPERATOR_NAME.equals(audit.operatorName)) {
                    throw operationAuditError(index,
                            "a player record must not carry the console marker");
                }
                if (audit.reason == null) {
                    throw operationAuditError(index, "reason must not be null");
                }
                String normalized;
                try {
                    normalized = AdminAudit.validateReason(audit.reason);
                } catch (IllegalArgumentException e) {
                    throw new PersistenceException(
                            "Invalid operation audit record at index " + index
                                    + ": rejected reason (" + e.getMessage() + ")", e);
                }
                if (!normalized.equals(audit.reason)) {
                    throw operationAuditError(index, "reason must already be normalized");
                }
                try {
                    Instant.parse(audit.timestamp);
                } catch (RuntimeException e) {
                    throw operationAuditError(index, "malformed timestamp");
                }
                requireOperationBackupId(audit.sourceBackupId, "sourceBackupId", index);
                requireOperationBackupId(audit.safetyBackupId, "safetyBackupId", index);
            } catch (PersistenceException e) {
                if (e.getMessage() != null
                        && e.getMessage().startsWith("Invalid operation audit record at index")) {
                    throw e;
                }
                throw operationAuditError(index, "unreadable record");
            } catch (RuntimeException e) {
                throw operationAuditError(index, "unreadable record");
            }
            if (!seenOperationIds.add(audit.id)) {
                throw operationAuditError(index, "duplicate operation audit id");
            }
            try {
                audit.toDomain();
            } catch (RuntimeException e) {
                throw operationAuditError(index, "unreadable record");
            }
        }
    }

    private static PersistenceException operationAuditError(int index, String detail) {
        return new PersistenceException(
                "Invalid operation audit record at index " + index + ": " + detail);
    }

    private static void requireOperationUuid(String raw, String field, int index) {
        if (raw == null) {
            throw operationAuditError(index, field + " must not be null");
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw operationAuditError(index, "malformed " + field);
        }
        if (!parsed.toString().equals(raw)) {
            throw operationAuditError(index, "non-canonical " + field);
        }
    }

    private static void requireOperationBackupId(String raw, String field, int index) {
        if (raw == null || raw.isBlank()) {
            throw operationAuditError(index, field + " must not be blank");
        }
        if (".".equals(raw) || "..".equals(raw) || !SAFE_BACKUP_ID.matcher(raw).matches()) {
            throw operationAuditError(index, "illegal " + field);
        }
    }

    /**
     * Restore guard over durable managed issuance progress: a restore must never
     * leave an already-delivered or possibly-delivered managed note redeemable
     * after the balance is rolled back, and must never invent a refund.
     *
     * <p>Scope is managed issuance records only. Plain consumed nonces are
     * deliberately not covered: a restore rolls balances and plain nonces back
     * together, so the two stay consistent. Physical notes live in player
     * inventories outside the economy snapshot, so restoring an older snapshot
     * can re-expose a nonce for presentation again; this guard never promises
     * that a nonce cannot be presented twice. What it promises instead: a
     * restore never leaves an already-delivered, possibly-delivered or
     * refunded-voided managed note redeemable after the balance is rolled
     * back, and no refund is ever invented by a restore. A refunded or voided
     * note is disabled in the live state because its nonce is consumed, but
     * the physical note may still be kept in a player inventory; restoring a
     * snapshot without that consumption would make the kept note redeemable
     * again next to the already-credited refund, so such records refuse the
     * empty candidate just like delivered ones.</p>
     *
     * <p>Rules, checked before any live row moves (both backends call this
     * inside their exclusive window after the safety backup):</p>
     * <ul>
     *   <li>A candidate without issuance progress (a snapshot written before
     *       issuance tracking, or an explicitly empty section) may only replace
     *       live progress when no live record could leave a redeemable note
     *       behind. Records in {@code PREPARED}, {@code DEBIT_COMMITTED},
     *       {@code ABORTED} or {@code REDEEMED} pass: the first never deducted,
     *       the second never reached the handover (the delivery mark is stored
     *       before the inventory handover runs, so a pre-handover record never
     *       exposed a note), and the last two are terminal with no redeemable
     *       note left. Records in {@code DELIVERY_STARTED}, {@code DELIVERED},
     *       {@code FINAL_VOID_REFUNDED} or {@code QUARANTINED} refuse: the first
     *       two may already have handed the note over (the delivery mark is
     *       stored before the handover runs), the refunded-voided record was
     *       already refunded on the ledger but its physical note may still be
     *       kept (restoring a snapshot without the nonce consumption would make
     *       the kept note redeemable again next to the refund), and the last
     *       cannot be judged safe. This keeps the supported migration rollback
     *       path for deduction-free or settled progress while refusing the
     *       balance-revert-plus-redeem double value.</li>
     *   <li>Otherwise every live issuance record must exist <em>identically</em> in the
     *       candidate (domain equality: nonce, account, currency, amount,
     *       status and debit evidence). A missing or differing record means
     *       the candidate would drop or rewind live progress.</li>
     *   <li>A candidate record unknown to the live state that carries debit
     *       evidence must reference an authoritative durable debit inside the
     *       same candidate (type, account, currency, amount, reason binding
     *       and before/after proof). Evidence-free newcomers (bare
     *       {@code PREPARED}) are harmless and pass.</li>
     *   <li>Empty against empty passes, so ordinary restores without issuance
     *       progress are unaffected.</li>
     * </ul>
     *
     * <p>Diagnostics name only the rule; no amount, reason or
     * balance text is echoed.</p>
     *
     * @throws PersistenceException when the candidate must not replace the live state
     */
    public static void checkIssuanceRestoreGuard(JsonModel live, JsonModel candidate)
            throws PersistenceException {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(candidate, "candidate");
        // Empty candidate: only live states that cannot leave a redeemable
        // note behind may roll back wholesale. Delivered, possibly-delivered,
        // refunded-voided and undecidable live records refuse, so the balance
        // cannot revert while a physical note stays or becomes redeemable
        // again. A candidate that carries any issuance record takes the strict
        // path below.
        if (candidate.issuances.isEmpty()) {
            for (Map.Entry<String, JsonModel.JsonIssuance> entry : live.issuances.entrySet()) {
                com.smile.aceeconomy.domain.BanknoteIssuance liveIssuance;
                try {
                    liveIssuance = entry.getValue().toDomain();
                } catch (PersistenceException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new PersistenceException(
                            "Restore refused: a live issuance record cannot be compared"
                                    + " (" + e.getMessage() + ")", e);
                }
                switch (liveIssuance.status()) {
                    case PREPARED, DEBIT_COMMITTED, ABORTED, REDEEMED -> {
                        // No redeemable note can be left behind.
                    }
                    case DELIVERY_STARTED, DELIVERED, FINAL_VOID_REFUNDED, QUARANTINED -> {
                        throw new PersistenceException(
                                "Restore refused: live managed issuance includes a delivered,"
                                        + " possibly-delivered, refunded-voided or undecidable note;"
                                        + " restoring a snapshot without issuance progress would return"
                                        + " the balance while the physical note in a player inventory"
                                        + " stays or becomes redeemable again (a refunded-voided note"
                                        + " may still be kept by a player). Ask players to redeem or"
                                        + " otherwise settle their managed notes before rolling back"
                                        + " to a pre-upgrade backup.");
                    }
                }
            }
            return;
        }
        for (Map.Entry<String, JsonModel.JsonIssuance> entry : live.issuances.entrySet()) {
            String key = entry.getKey();
            JsonModel.JsonIssuance candRaw = candidate.issuances.get(key);
            if (candRaw == null) {
                throw new PersistenceException(
                        "Restore refused: live issuance progress for a nonce is missing"
                                + " from the candidate snapshot; restoring it would drop"
                                + " durable progress");
            }
            com.smile.aceeconomy.domain.BanknoteIssuance liveIssuance;
            com.smile.aceeconomy.domain.BanknoteIssuance candidateIssuance;
            try {
                liveIssuance = entry.getValue().toDomain();
                candidateIssuance = candRaw.toDomain();
            } catch (PersistenceException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Restore refused: an issuance record cannot be compared"
                                + " (" + e.getMessage() + ")", e);
            }
            if (!liveIssuance.equals(candidateIssuance)) {
                throw new PersistenceException(
                        "Restore refused: the candidate snapshot rewinds live issuance"
                                + " progress; a settled or consumed nonce must never go back"
                                + " to an unfinished state");
            }
        }
        Map<String, Transaction> candidateTransactions = new LinkedHashMap<>();
        for (JsonModel.JsonTransaction t : candidate.transactions) {
            try {
                Transaction domain = t.toDomain();
                candidateTransactions.putIfAbsent(domain.id().toString(), domain);
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Restore refused: a candidate transaction cannot be read"
                                + " (" + e.getMessage() + ")", e);
            }
        }
        for (Map.Entry<String, JsonModel.JsonIssuance> entry : candidate.issuances.entrySet()) {
            String key = entry.getKey();
            if (live.issuances.containsKey(key)) {
                continue;
            }
            JsonModel.JsonIssuance raw = entry.getValue();
            if (raw.debitTransactionId == null) {
                continue;
            }
            com.smile.aceeconomy.domain.BanknoteIssuance newcomer;
            try {
                newcomer = raw.toDomain();
            } catch (PersistenceException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Restore refused: a new candidate issuance record cannot be read"
                                + " (" + e.getMessage() + ")", e);
            }
            Transaction debit = candidateTransactions.get(newcomer.debitTransactionId());
            String authority =
                    com.smile.aceeconomy.infrastructure.persistence.IssuanceProofChecks
                            .checkDebitAuthority(newcomer, debit, newcomer.nonce());
            if (authority != null) {
                throw new PersistenceException(
                        "Restore refused: a new candidate issuance record carries debit"
                                + " evidence without its authoritative durable debit in the"
                                + " same snapshot; deductions are never invented by restore");
            }
        }
    }

    /**
     * Restore-only cap gate over a parsed snapshot's <em>balance</em> strings.
     * A snapshot that would store an over-cap (or over-wide) balance is refused
     * here, while the caller still holds no live state.
     *
     * <p>Deliberately separate from {@link #validateRecords}: plain loads and the
     * backup-sanity check stay lenient, so a legacy over-cap row still loads and
     * is diagnosed by the service gate on the next transaction instead. Audit
     * rows in the same snapshot stay restorable as evidence. Both backends call
     * this from {@code restore()} right after {@link #validateRecords} and
     * before touching any live row.</p>
     *
     * @throws PersistenceException naming the account, currency and cap on refusal
     */
    public static void checkRestoreCaps(JsonModel model) throws PersistenceException {
        Objects.requireNonNull(model, "model");
        for (Map.Entry<String, JsonModel.JsonAccount> entry : model.accounts.entrySet()) {
            var refusal = AmountCap.restoreRefusal(entry.getKey(), entry.getValue().balances);
            if (refusal.isPresent()) {
                throw new PersistenceException(refusal.get());
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
