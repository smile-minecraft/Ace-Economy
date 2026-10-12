package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.AmountCap;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory v2 persistence model plus its JSON (de)serialization.
 *
 * <p>Amounts are stored as exact decimal strings (no float drift). The balance
 * write entry ({@link #amountToString}) refuses any string above the public
 * {@code AmountCap} or wider than the 64-char column with a diagnosis; audit
 * rows ({@link #evidenceToString}) stay writable as append-only evidence while
 * reads stay lenient, so a legacy over-cap row still loads and the service cap
 * gate diagnoses it. The schema version lets
 * {@link JsonPersistenceBackend} detect an incompatible file and offer recreation instead of
 * silently loading garbage.</p>
 */
public final class JsonModel {

    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    public final Map<String, JsonAccount> accounts = new LinkedHashMap<>();
    public final List<JsonTransaction> transactions = new ArrayList<>();
    /** Consumed single-use keys (nonce -> ISO-8601 consumption timestamp). Optional when reading. */
    public final Map<String, String> nonces = new LinkedHashMap<>();
    /**
     * Independent restore audits. Optional additive section: files written
     * before operation tracking load with an empty list, and it is written only
     * when non-empty so snapshots without restore audits keep their exact shape.
     * The stored backup file itself is never rewritten by a restore; only the
     * in-memory bytes handed to the backend carry the merged collection.
     */
    public final List<JsonOperationAudit> operationAudits = new ArrayList<>();
    /**
     * Durable banknote issuance progress, keyed by canonical nonce string.
     * Optional additive section: files written before issuance tracking load
     * with an empty map, and it is written only when non-empty so snapshots
     * without issuance progress keep their exact shape. Old readers ignore
     * the key; the schema version is unchanged.
     */
    public final Map<String, JsonIssuance> issuances = new LinkedHashMap<>();

    // ---------------- (de)serialization ----------------

    @SuppressWarnings("unchecked")
    public static JsonModel fromJson(String text) {
        Object root = JsonCodec.parse(text);
        if (!(root instanceof Map)) {
            throw new PersistenceException("JSON root must be an object");
        }
        Map<String, Object> obj = (Map<String, Object>) root;
        JsonModel model = new JsonModel();
        if (!obj.containsKey("schemaVersion")) {
            throw new PersistenceException("Missing 'schemaVersion' in JSON model: expected integer " + SCHEMA_VERSION);
        }
        Object schemaRaw = obj.get("schemaVersion");
        if (schemaRaw == null) {
            throw new PersistenceException("Invalid 'schemaVersion' in JSON model: expected integer JSON number, got null");
        }
        if (!(schemaRaw instanceof Number)) {
            throw new PersistenceException("Invalid 'schemaVersion' in JSON model: expected integer JSON number, got " + schemaRaw);
        }
        double d = ((Number) schemaRaw).doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d)) {
            throw new PersistenceException("Invalid 'schemaVersion' in JSON model: expected integer JSON number, got " + schemaRaw);
        }
        if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            throw new PersistenceException("Invalid 'schemaVersion' in JSON model: out of range " + schemaRaw);
        }
        int sv = (int) d;
        if ((double) sv != d) {
            throw new PersistenceException("Invalid 'schemaVersion' in JSON model: expected integer JSON number, got " + schemaRaw);
        }
        model.schemaVersion = sv;
        // accounts and transactions are required v2 sections. Missing or mistyped sections must
        // fail fast instead of being treated as empty, otherwise a truncated snapshot
        // {"schemaVersion":1} would be restored as empty and wipe live data with DELETEs.
        Object accObj = obj.get("accounts");
        if (!(accObj instanceof Map)) {
            throw new PersistenceException("Missing or invalid 'accounts' section in JSON model");
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) accObj).entrySet()) {
            model.accounts.put(e.getKey(), JsonAccount.fromJson(e.getValue()));
        }
        Object txObj = obj.get("transactions");
        if (!(txObj instanceof List)) {
            throw new PersistenceException("Missing or invalid 'transactions' section in JSON model");
        }
        for (Object o : (List<Object>) txObj) {
            model.transactions.add(JsonTransaction.fromJson(o));
        }
        // Nonces are an optional additive section: files written before durable replay
        // protection simply load with an empty set. The section must be absent or a Map;
        // an explicit null, array or string would otherwise be mistaken for "missing"
        // and silently clear the replay guard, allowing a banknote to be replayed.
        // Keys must be well-formed UUIDs — a malformed record means the file was
        // corrupted or hand-edited, so fail fast instead of silently dropping the guard.
        if (!obj.containsKey("nonces")) {
            // absent -> compatible with pre-nonce files
        } else {
            Object nonceObj = obj.get("nonces");
            if (!(nonceObj instanceof Map)) {
                throw new PersistenceException("Invalid 'nonces' section in JSON model: expected object");
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) nonceObj).entrySet()) {
                String rawKey = e.getKey();
                UUID parsed;
                try {
                    parsed = UUID.fromString(rawKey);
                } catch (IllegalArgumentException ex) {
                    throw new PersistenceException("Invalid nonce record in JSON model: " + rawKey);
                }
                if (!parsed.toString().equals(rawKey)) {
                    throw new PersistenceException(
                            "Invalid nonce record in JSON model: non-canonical UUID " + rawKey);
                }
                Object rawVal = e.getValue();
                if (!(rawVal instanceof String s)) {
                    throw new PersistenceException(
                            "Invalid nonce value for key " + rawKey + ": expected string, got "
                                    + (rawVal == null ? "null" : rawVal + " (" + rawVal.getClass().getSimpleName() + ")"));
                }
                model.nonces.put(rawKey, s);
            }
        }
        // Restore audits are an optional additive section. Absent means the file was written
        // before operation tracking and loads empty. A present value must be an array: an explicit
        // null or a mistyped value would otherwise drop the audit trail without any signal.
        if (obj.containsKey("operationAudits")) {
            Object auditObj = obj.get("operationAudits");
            if (!(auditObj instanceof List)) {
                throw new PersistenceException("Invalid 'operationAudits' section in JSON model: expected array");
            }
            for (Object raw : (List<Object>) auditObj) {
                model.operationAudits.add(JsonOperationAudit.fromJson(raw));
            }
        }
        // Issuance progress is an optional additive section. Absent means the file was
        // written before issuance tracking and loads empty. A present value must be an
        // object of strictly valid records: a malformed record means the file was
        // corrupted or hand-edited, so fail fast instead of silently dropping the
        // recovery queue (an empty-looking queue would strand a deducted charge
        // or resurrect a voided note).
        if (obj.containsKey("issuances")) {
            Object issuancesObj = obj.get("issuances");
            if (!(issuancesObj instanceof Map)) {
                throw new PersistenceException("Invalid 'issuances' section in JSON model: expected object");
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) issuancesObj).entrySet()) {
                String rawKey = e.getKey();
                JsonIssuance record = JsonIssuance.fromJson(e.getValue());
                UUID parsedKey;
                try {
                    parsedKey = UUID.fromString(rawKey);
                } catch (IllegalArgumentException ex) {
                    throw new PersistenceException("Invalid issuance record in JSON model: " + rawKey);
                }
                if (!parsedKey.toString().equals(rawKey)) {
                    throw new PersistenceException(
                            "Invalid issuance record in JSON model: non-canonical UUID " + rawKey);
                }
                if (!record.nonce.equals(rawKey)) {
                    throw new PersistenceException(
                            "Invalid issuance record in JSON model: map key does not match nonce field");
                }
                model.issuances.put(rawKey, record);
            }
        }
        return model;
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", schemaVersion);
        Map<String, Object> acc = new LinkedHashMap<>();
        for (Map.Entry<String, JsonAccount> e : accounts.entrySet()) {
            acc.put(e.getKey(), e.getValue().toJsonObject());
        }
        root.put("accounts", acc);
        List<Object> tx = new ArrayList<>(transactions.size());
        for (JsonTransaction t : transactions) {
            tx.add(t.toJsonObject());
        }
        root.put("transactions", tx);
        root.put("nonces", new LinkedHashMap<>(nonces));
        // Written only when present, so a snapshot without restore audits keeps the exact key set
        // that the backup tests compare against.
        if (!operationAudits.isEmpty()) {
            List<Object> audits = new ArrayList<>(operationAudits.size());
            for (JsonOperationAudit a : operationAudits) {
                audits.add(a.toJsonObject());
            }
            root.put("operationAudits", audits);
        }
        // Written only when present, so snapshots without issuance progress keep the exact
        // key set that older builds wrote, and old readers keep ignoring the section.
        if (!issuances.isEmpty()) {
            Map<String, Object> progress = new LinkedHashMap<>();
            for (Map.Entry<String, JsonIssuance> e : issuances.entrySet()) {
                progress.put(e.getKey(), e.getValue().toJsonObject());
            }
            root.put("issuances", progress);
        }
        return root;
    }

    public String toJson() {
        return JsonCodec.write(toJsonObject());
    }

    // ---------------- helpers ----------------

    static double asDouble(Object o, double fallback) {
        if (o == null) {
            return fallback;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            return Double.parseDouble(s);
        }
        throw new PersistenceException("Expected number, got " + o);
    }

    static String asString(Object o) {
        if (o instanceof String s) {
            return s;
        }
        throw new PersistenceException("Expected string, got " + (o == null ? "null" : o + " (" + o.getClass().getSimpleName() + ")"));
    }

    static String asStringOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String s) {
            return s;
        }
        throw new PersistenceException("Expected string or null, got " + o + " (" + o.getClass().getSimpleName() + ")");
    }

    static boolean asBool(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        throw new PersistenceException("Invalid boolean value: expected JSON boolean, got " + o);
    }

    /**
     * Balance write entry. Refuses an over-cap or over-wide plain string with a
     * cap diagnosis instead of persisting a balance no reader can explain. Only
     * owed balances go through here; audit rows use {@link #evidenceToString}.
     */
    static String amountToString(Amount a) {
        String plain = a.value().toPlainString();
        var refusal = AmountCap.storableRefusal(plain);
        if (refusal.isPresent()) {
            throw new PersistenceException(refusal.get());
        }
        return plain;
    }

    /**
     * Audit write entry. Audit rows are append-only evidence — including evidence
     * of pre-cap history and of balances the cap gate refused — so they are never
     * cap-refused: refusing them would make legacy history unrestorable and
     * reversals of legacy rows unrecordable. The economic invariant (no balance
     * above the cap) is enforced at the balance entry above and the service
     * gates, which every production money path passes through.
     */
    static String evidenceToString(Amount a) {
        return a.value().toPlainString();
    }

    static Amount stringToAmount(String s) {
        BigDecimal bd = new BigDecimal(s);
        return Amount.of(bd, bd.scale());
    }

    // ---------------- account ----------------

    public static final class JsonAccount {
        public String owner;
        public String ownerName;
        public final Map<String, String> balances = new LinkedHashMap<>();

        static JsonAccount fromJson(Object o) {
            if (!(o instanceof Map)) {
                throw new PersistenceException("Account must be an object");
            }
            Map<String, Object> m = (Map<String, Object>) o;
            JsonAccount a = new JsonAccount();
            a.owner = asString(m.get("owner"));
            a.ownerName = asString(m.get("ownerName"));
            Object b = m.get("balances");
            if (!(b instanceof Map)) {
                throw new PersistenceException("Missing or invalid 'balances' section in account record");
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) b).entrySet()) {
                Object raw = e.getValue();
                if (!(raw instanceof String s)) {
                    throw new PersistenceException(
                            "Invalid balance value for currency '" + e.getKey() + "': expected decimal string, got "
                                    + (raw == null ? "null" : raw + " (" + raw.getClass().getSimpleName() + ")"));
                }
                a.balances.put(e.getKey(), s);
            }
            return a;
        }

        Map<String, Object> toJsonObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("owner", owner);
            m.put("ownerName", ownerName);
            m.put("balances", new LinkedHashMap<>(balances));
            return m;
        }
    }

    // ---------------- transaction ----------------

    public static final class JsonTransaction {
        public String id;
        public String accountId;
        public String counterparty; // nullable
        public String currencyId;
        public String amount;
        public String type;
        public String balanceBefore; // nullable
        public String balanceAfter;  // nullable
        public String timestamp;
        public String reason;
        public boolean reverted;
        // Administrative identity. Both are nullable: legacy and player records omit them, and a
        // console record has a name marker with no id. Written only when non-null.
        public String operatorId;
        public String operatorName;

        static JsonTransaction fromDomain(Transaction t) {
            JsonTransaction j = new JsonTransaction();
            j.id = t.id().toString();
            j.accountId = t.accountId().toString();
            j.counterparty = t.counterparty() == null ? null : t.counterparty().toString();
            j.currencyId = t.currencyId();
            j.amount = evidenceToString(t.amount());
            j.type = t.type().name();
            j.balanceBefore = t.balanceBefore() == null ? null : evidenceToString(t.balanceBefore());
            j.balanceAfter = t.balanceAfter() == null ? null : evidenceToString(t.balanceAfter());
            j.timestamp = t.timestamp().toString();
            j.reason = t.reason();
            j.reverted = false;
            j.operatorId = t.operatorId() == null ? null : t.operatorId().toString();
            j.operatorName = t.operatorName();
            return j;
        }

        static JsonTransaction fromJson(Object o) {
            if (!(o instanceof Map)) {
                throw new PersistenceException("Transaction must be an object");
            }
            Map<String, Object> m = (Map<String, Object>) o;
            JsonTransaction t = new JsonTransaction();
            t.id = asString(m.get("id"));
            t.accountId = asString(m.get("accountId"));
            t.counterparty = asStringOrNull(m.get("counterparty"));
            t.currencyId = asString(m.get("currencyId"));
            t.amount = asString(m.get("amount"));
            t.type = asString(m.get("type"));
            t.balanceBefore = asStringOrNull(m.get("balanceBefore"));
            t.balanceAfter = asStringOrNull(m.get("balanceAfter"));
            t.timestamp = asString(m.get("timestamp"));
            t.reason = asStringOrNull(m.get("reason"));
            t.operatorId = asStringOrNull(m.get("operatorId"));
            t.operatorName = asStringOrNull(m.get("operatorName"));
            if (!m.containsKey("reverted")) {
                t.reverted = false;
            } else {
                Object raw = m.get("reverted");
                if (!(raw instanceof Boolean)) {
                    throw new PersistenceException(
                            "Invalid 'reverted' field in transaction " + t.id + ": expected JSON boolean, got " + raw);
                }
                t.reverted = (Boolean) raw;
            }
            return t;
        }

        Map<String, Object> toJsonObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("accountId", accountId);
            m.put("counterparty", counterparty);
            m.put("currencyId", currencyId);
            m.put("amount", amount);
            m.put("type", type);
            m.put("balanceBefore", balanceBefore);
            m.put("balanceAfter", balanceAfter);
            m.put("timestamp", timestamp);
            m.put("reason", reason);
            m.put("reverted", reverted);
            if (operatorId != null) {
                m.put("operatorId", operatorId);
            }
            if (operatorName != null) {
                m.put("operatorName", operatorName);
            }
            return m;
        }

        Transaction toDomain() {
            return new Transaction(
                    UUID.fromString(id),
                    UUID.fromString(accountId),
                    counterparty == null ? null : UUID.fromString(counterparty),
                    currencyId,
                    stringToAmount(amount),
                    TransactionType.valueOf(type),
                    balanceBefore == null ? null : stringToAmount(balanceBefore),
                    balanceAfter == null ? null : stringToAmount(balanceAfter),
                    Instant.parse(timestamp),
                    reason,
                    operatorId == null ? null : UUID.fromString(operatorId),
                    operatorName);
        }
    }

    // ---------------- issuance progress ----------------

    /**
     * Durable progress of one banknote issuance, keyed by nonce.
     *
     * <p>Strict on read: the nonce, account and debit-transaction ids must be
     * canonical UUIDs, the status must be a known
     * {@link com.smile.aceeconomy.domain.BanknoteIssuanceStatus}, the amount
     * must parse, the currency must be non-blank, and debit evidence must be
     * present exactly for the states that carry a deduction. Anything else
     * fails fast instead of loading as empty progress.</p>
     */
    public static final class JsonIssuance {
        public String nonce;
        public String accountId;
        public String currencyId;
        public String amount;
        public String status;
        public String debitTransactionId; // nullable: only states past PREPARED carry it
        public String debitProof; // nullable: the nonce-bound debit reason, same rule
        public String createdAt;
        public String updatedAt;

        public static JsonIssuance fromDomain(com.smile.aceeconomy.domain.BanknoteIssuance issuance) {
            JsonIssuance json = new JsonIssuance();
            json.nonce = issuance.nonce().toString();
            json.accountId = issuance.accountId().toString();
            json.currencyId = issuance.currencyId();
            json.amount = evidenceToString(issuance.amount());
            json.status = issuance.status().name();
            json.debitTransactionId = issuance.debitTransactionId();
            json.debitProof = issuance.debitProof();
            json.createdAt = issuance.createdAt().toString();
            json.updatedAt = issuance.updatedAt().toString();
            return json;
        }

        @SuppressWarnings("unchecked")
        public static JsonIssuance fromJson(Object raw) {
            if (!(raw instanceof Map)) {
                throw new PersistenceException("Invalid issuance record in JSON model: expected an object");
            }
            Map<String, Object> map = (Map<String, Object>) raw;
            JsonIssuance record = new JsonIssuance();
            record.nonce = issuanceString(map.get("nonce"), "nonce");
            record.accountId = issuanceString(map.get("accountId"), "accountId");
            record.currencyId = issuanceString(map.get("currencyId"), "currencyId");
            record.amount = issuanceString(map.get("amount"), "amount");
            record.status = issuanceString(map.get("status"), "status");
            record.debitTransactionId = issuanceStringOrNull(
                    map.get("debitTransactionId"), "debitTransactionId");
            record.debitProof = issuanceStringOrNull(map.get("debitProof"), "debitProof");
            record.createdAt = issuanceString(map.get("createdAt"), "createdAt");
            record.updatedAt = issuanceString(map.get("updatedAt"), "updatedAt");
            // The stored value is never interpolated: a corrupt field may itself
            // carry hostile text, so refusals name only the problem.
            UUID parsedNonce = canonicalUuid(record.nonce, "nonce");
            canonicalUuid(record.accountId, "accountId");
            if (record.currencyId.isBlank()) {
                throw new PersistenceException(
                        "Invalid issuance record in JSON model: blank currencyId");
            }
            try {
                stringToAmount(record.amount);
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid issuance record in JSON model: malformed amount");
            }
            com.smile.aceeconomy.domain.BanknoteIssuanceStatus parsedStatus;
            try {
                parsedStatus = com.smile.aceeconomy.domain.BanknoteIssuanceStatus
                        .valueOf(record.status);
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid issuance record in JSON model: unknown status");
            }
            if (!parsedNonce.toString().equals(record.nonce)) {
                throw new PersistenceException(
                        "Invalid issuance record in JSON model: non-canonical nonce");
            }
            boolean carriesDebit = record.debitTransactionId != null || record.debitProof != null;
            boolean requiresDebit = switch (parsedStatus) {
                case DEBIT_COMMITTED, DELIVERY_STARTED, DELIVERED,
                        FINAL_VOID_REFUNDED, REDEEMED -> true;
                case PREPARED, ABORTED, QUARANTINED -> false;
            };
            // PREPARED and ABORTED must carry no invented deduction; the
            // deduction-carrying states must carry both the id and the proof.
            // QUARANTINED keeps whatever evidence was present at diagnosis time.
            if (parsedStatus == com.smile.aceeconomy.domain.BanknoteIssuanceStatus.QUARANTINED) {
                if (record.debitTransactionId != null) {
                    canonicalUuid(record.debitTransactionId, "debitTransactionId");
                }
                if (record.debitProof != null && record.debitProof.isBlank()) {
                    throw new PersistenceException(
                            "Invalid issuance record in JSON model: blank debitProof");
                }
            } else if (requiresDebit) {
                if (record.debitTransactionId == null || record.debitProof == null
                        || record.debitProof.isBlank()) {
                    throw new PersistenceException(
                            "Invalid issuance record in JSON model: state carries no debit evidence");
                }
                canonicalUuid(record.debitTransactionId, "debitTransactionId");
            } else if (carriesDebit) {
                throw new PersistenceException(
                        "Invalid issuance record in JSON model: deduction-free state carries debit evidence");
            }
            try {
                Instant.parse(record.createdAt);
                Instant.parse(record.updatedAt);
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid issuance record in JSON model: malformed timestamp");
            }
            return record;
        }

        private static String issuanceString(Object value, String field) {
            if (value instanceof String s) {
                return s;
            }
            throw new PersistenceException(
                    "Invalid issuance record in JSON model: field " + field + " must be a string");
        }

        private static String issuanceStringOrNull(Object value, String field) {
            if (value == null) {
                return null;
            }
            if (value instanceof String s) {
                return s;
            }
            throw new PersistenceException(
                    "Invalid issuance record in JSON model: field " + field + " must be a string or null");
        }

        private static UUID canonicalUuid(String raw, String field) {
            UUID parsed;
            try {
                parsed = UUID.fromString(raw);
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid issuance record in JSON model: malformed " + field);
            }
            if (!parsed.toString().equals(raw)) {
                throw new PersistenceException(
                        "Invalid issuance record in JSON model: non-canonical " + field);
            }
            return parsed;
        }

        Map<String, Object> toJsonObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("nonce", nonce);
            map.put("accountId", accountId);
            map.put("currencyId", currencyId);
            map.put("amount", amount);
            map.put("status", status);
            map.put("debitTransactionId", debitTransactionId);
            map.put("debitProof", debitProof);
            map.put("createdAt", createdAt);
            map.put("updatedAt", updatedAt);
            return map;
        }

        public com.smile.aceeconomy.domain.BanknoteIssuance toDomain() {
            // fromJson already validated every field; a direct map read (for
            // example from a hand-built model) re-validates through the same
            // strict path instead of trusting raw strings.
            return fromJson(toJsonObject()).toDomainValidated();
        }

        private com.smile.aceeconomy.domain.BanknoteIssuance toDomainValidated() {
            return new com.smile.aceeconomy.domain.BanknoteIssuance(
                    UUID.fromString(nonce),
                    UUID.fromString(accountId),
                    currencyId,
                    stringToAmount(amount),
                    com.smile.aceeconomy.domain.BanknoteIssuanceStatus.valueOf(status),
                    debitTransactionId,
                    debitProof,
                    Instant.parse(createdAt),
                    Instant.parse(updatedAt));
        }
    }

    // ---------------- operation audit ----------------

    /**
     * Independent restore record. Read and written by {@link #fromJson} and
     * {@link #toJsonObject}; the operator fields are nullable so a record can
     * name a console operator with no id.
     */
    public static final class JsonOperationAudit {
        public String id;
        public String operation;
        public String operatorId;
        public String operatorName;
        public String reason;
        public String timestamp;
        public String sourceBackupId;
        public String safetyBackupId;

        public static JsonOperationAudit fromDomain(
                com.smile.aceeconomy.domain.OperationAudit record) {
            JsonOperationAudit json = new JsonOperationAudit();
            json.id = record.id().toString();
            json.operation = record.operation();
            json.operatorId = record.operatorId() == null ? null : record.operatorId().toString();
            json.operatorName = record.operatorName();
            json.reason = record.reason();
            json.timestamp = record.timestamp().toString();
            json.sourceBackupId = record.sourceBackupId();
            json.safetyBackupId = record.safetyBackupId();
            return json;
        }

        @SuppressWarnings("unchecked")
        static JsonOperationAudit fromJson(Object raw) {
            if (!(raw instanceof Map)) {
                throw new PersistenceException(
                        "Invalid operation audit record: expected an object");
            }
            Map<String, Object> map = (Map<String, Object>) raw;
            JsonOperationAudit record = new JsonOperationAudit();
            record.id = operationString(map.get("id"), "id");
            record.operation = operationString(map.get("operation"), "operation");
            record.operatorId = operationStringOrNull(map.get("operatorId"), "operatorId");
            record.operatorName = operationString(map.get("operatorName"), "operatorName");
            record.reason = operationString(map.get("reason"), "reason");
            record.timestamp = operationString(map.get("timestamp"), "timestamp");
            record.sourceBackupId = operationString(map.get("sourceBackupId"), "sourceBackupId");
            record.safetyBackupId = operationString(map.get("safetyBackupId"), "safetyBackupId");
            return record;
        }

        /**
         * String coercion that names only the field. The stored value is never
         * interpolated: a mistyped field may itself carry a secret.
         */
        private static String operationString(Object value, String field) {
            if (value instanceof String s) {
                return s;
            }
            throw new PersistenceException(
                    "Invalid operation audit record: field " + field + " must be a string");
        }

        private static String operationStringOrNull(Object value, String field) {
            if (value == null) {
                return null;
            }
            if (value instanceof String s) {
                return s;
            }
            throw new PersistenceException(
                    "Invalid operation audit record: field " + field + " must be a string or null");
        }

        Map<String, Object> toJsonObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("operation", operation);
            map.put("operatorId", operatorId);
            map.put("operatorName", operatorName);
            map.put("reason", reason);
            map.put("timestamp", timestamp);
            map.put("sourceBackupId", sourceBackupId);
            map.put("safetyBackupId", safetyBackupId);
            return map;
        }

        /**
         * Materializes the stored form without echoing any field value. A
         * corrupt field may itself carry a secret, and the parsing failures
         * underneath quote the offending text, so refusals name only the
         * problem with a fixed detail and never attach the parsing failure
         * as a cause.
         */
        com.smile.aceeconomy.domain.OperationAudit toDomain() {
            UUID parsedId;
            UUID parsedOperator;
            java.time.Instant parsedTime;
            try {
                parsedId = UUID.fromString(id);
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid operation audit record: malformed id");
            }
            if (!parsedId.toString().equals(id)) {
                throw new PersistenceException(
                        "Invalid operation audit record: non-canonical id");
            }
            try {
                if (operatorId != null) {
                    parsedOperator = UUID.fromString(operatorId);
                    if (!parsedOperator.toString().equals(operatorId)) {
                        throw new PersistenceException(
                                "Invalid operation audit record: non-canonical operator id");
                    }
                } else {
                    parsedOperator = null;
                }
            } catch (PersistenceException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid operation audit record: malformed operator id");
            }
            try {
                parsedTime = java.time.Instant.parse(timestamp);
            } catch (RuntimeException e) {
                throw new PersistenceException(
                        "Invalid operation audit record: malformed timestamp");
            }
            return com.smile.aceeconomy.domain.OperationAudit.rehydrate(
                    parsedId,
                    operation,
                    parsedOperator,
                    operatorName,
                    reason,
                    parsedTime,
                    sourceBackupId,
                    safetyBackupId);
        }
    }
}
