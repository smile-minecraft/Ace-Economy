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

    // ---------------- (de)serialization ----------------

    @SuppressWarnings("unchecked")
    public static JsonModel fromJson(String text) {
        Object root = JsonCodec.parse(text);
        if (!(root instanceof Map)) {
            throw new PersistenceException("JSON root must be an object");
        }
        Map<String, Object> obj = (Map<String, Object>) root;
        JsonModel model = new JsonModel();
        model.schemaVersion = (int) asDouble(obj.get("schemaVersion"), SCHEMA_VERSION);
        Object accObj = obj.get("accounts");
        if (accObj instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) accObj).entrySet()) {
                model.accounts.put(e.getKey(), JsonAccount.fromJson(e.getValue()));
            }
        }
        Object txObj = obj.get("transactions");
        if (txObj instanceof List) {
            for (Object o : (List<Object>) txObj) {
                model.transactions.add(JsonTransaction.fromJson(o));
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
        if (o == null) {
            throw new PersistenceException("Expected string, got null");
        }
        return o.toString();
    }

    static String asStringOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    static boolean asBool(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(o.toString());
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
            if (b instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) b).entrySet()) {
                    a.balances.put(e.getKey(), e.getValue() == null ? "0" : e.getValue().toString());
                }
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
            t.reverted = asBool(m.get("reverted"));
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
