package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.Amount;
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
 * <p>Amounts are stored as exact decimal strings (no float drift). The schema version lets
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

    static String amountToString(Amount a) {
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

        static JsonTransaction fromDomain(Transaction t) {
            JsonTransaction j = new JsonTransaction();
            j.id = t.id().toString();
            j.accountId = t.accountId().toString();
            j.counterparty = t.counterparty() == null ? null : t.counterparty().toString();
            j.currencyId = t.currencyId();
            j.amount = amountToString(t.amount());
            j.type = t.type().name();
            j.balanceBefore = t.balanceBefore() == null ? null : amountToString(t.balanceBefore());
            j.balanceAfter = t.balanceAfter() == null ? null : amountToString(t.balanceAfter());
            j.timestamp = t.timestamp().toString();
            j.reason = t.reason();
            j.reverted = false;
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
                    reason);
        }
    }
}
