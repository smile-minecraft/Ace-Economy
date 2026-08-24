package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link SnapshotPreflight}: every corruption class that would only
 * surface after a restore must be rejected up front, while legitimate data — including a
 * currency literally named {@code token} — must never be misjudged by a denylist.
 */
class SnapshotPreflightTest {

    private static final String OWNER = "11111111-2222-3333-4444-555555555555";

    private static String tx(String overrides) {
        return "{\"id\":\"" + UUID.randomUUID() + "\",\"accountId\":\"" + OWNER + "\","
                + "\"counterparty\":null,\"currencyId\":\"dollar\",\"amount\":\"1.00\","
                + "\"type\":\"DEPOSIT\",\"balanceBefore\":\"0\",\"balanceAfter\":\"1.00\","
                + "\"timestamp\":\"" + Instant.parse("2026-01-01T00:00:00Z") + "\","
                + "\"reason\":\"t\",\"reverted\":false" + overrides + "}";
    }

    private static String snapshot(String accountsJson, String transactionsJson) {
        return "{\"schemaVersion\":1,\"accounts\":" + accountsJson
                + ",\"transactions\":" + transactionsJson + ",\"nonces\":{}}";
    }

    private static final String VALID_ACCOUNTS = "{\"" + OWNER + "\":{\"owner\":\"" + OWNER
            + "\",\"ownerName\":\"Alice\",\"balances\":{\"dollar\":\"50.00\",\"token\":\"7\"}}}";

    @Test
    void validSnapshotWithTokenCurrencyPassesAllChecks() {
        JsonModel model = SnapshotPreflight.parse(snapshot(VALID_ACCOUNTS, "[]"));
        SnapshotPreflight.checkSchemaVersion(model);
        SnapshotPreflight.validateRecords(model);
        SnapshotPreflight.checkCurrencies(model, Set.of("dollar", "token"));
        assertDoesNotThrow(() -> SnapshotPreflight.validate(
                snapshot(VALID_ACCOUNTS, "[" + tx("") + "]"), Set.of("dollar", "token")));
    }

    @Test
    void malformedJsonIsRejectedByParse() {
        List<String> broken = List.of(
                "this is not json{",
                "[1,2,3]",
                "\"just a string\"");
        for (String text : broken) {
            assertThrows(PersistenceException.class, () -> SnapshotPreflight.parse(text),
                    "malformed snapshot must be rejected: " + text);
        }
    }

    @Test
    void schemaVersionMismatchIsRejected() {
        JsonModel model = SnapshotPreflight.parse(
                "{\"schemaVersion\":99,\"accounts\":{},\"transactions\":[],\"nonces\":{}}");
        PersistenceException e = assertThrows(PersistenceException.class,
                () -> SnapshotPreflight.checkSchemaVersion(model));
        assertTrue(e.getMessage().contains("99"),
                "the mismatch detail must stay visible: " + e.getMessage());
    }

    @Test
    void invalidRecordsAreRejectedBeforeAnyLiveStateIsTouched() {
        String now = Instant.now().toString();
        List<String> brokenTransactions = List.of(
                // unknown transaction type
                tx("").replace("\"type\":\"DEPOSIT\"", "\"type\":\"NOT_A_TYPE\""),
                // non-decimal amount
                tx("").replace("\"amount\":\"1.00\"", "\"amount\":\"12abc\""),
                // unparseable timestamp
                tx("").replace("\"timestamp\":\"" + Instant.parse("2026-01-01T00:00:00Z") + "\"",
                        "\"timestamp\":\"yesterday\""),
                // garbage counterparty uuid
                tx("").replace("\"counterparty\":null", "\"counterparty\":\"not-a-uuid\""),
                // garbage transaction id
                tx("").replaceFirst("\\{\"id\":\"[0-9a-f-]{36}\"", "{\"id\":\"not-a-uuid\""));
        for (String broken : brokenTransactions) {
            JsonModel model = SnapshotPreflight.parse(snapshot("{}", "[" + broken + "]"));
            assertThrows(PersistenceException.class, () -> SnapshotPreflight.validateRecords(model),
                    "invalid record must be rejected: " + broken);
        }

        // Garbage account owner uuid and non-decimal balance.
        JsonModel badOwner = SnapshotPreflight.parse(snapshot(
                "{\"zz\":{\"owner\":\"zz\",\"ownerName\":\"Bob\",\"balances\":{\"dollar\":\"1\"}}}",
                "[]"));
        assertThrows(PersistenceException.class, () -> SnapshotPreflight.validateRecords(badOwner));

        JsonModel badBalance = SnapshotPreflight.parse(snapshot(
                "{\"" + OWNER + "\":{\"owner\":\"" + OWNER
                        + "\",\"ownerName\":\"Alice\",\"balances\":{\"dollar\":\"lots\"}}}",
                "[]"));
        assertThrows(PersistenceException.class,
                () -> SnapshotPreflight.validateRecords(badBalance));
    }

    @Test
    void duplicateTransactionIdsAreRejectedUpFront() {
        String duplicated = tx("");
        JsonModel model = SnapshotPreflight.parse(snapshot("{}",
                "[" + duplicated + "," + duplicated.replace("\"reverted\":false", "\"reverted\":true") + "]"));
        PersistenceException e = assertThrows(PersistenceException.class,
                () -> SnapshotPreflight.validateRecords(model));
        assertTrue(e.getMessage().toLowerCase().contains("duplicate"),
                "the rejection must name the duplication: " + e.getMessage());
    }

    @Test
    void unknownCurrencyIsRejectedButTokenNeverMisjudged() {
        JsonModel unknownBalance = SnapshotPreflight.parse(snapshot(
                "{\"" + OWNER + "\":{\"owner\":\"" + OWNER
                        + "\",\"ownerName\":\"Alice\",\"balances\":{\"mysterycoin\":\"5\"}}}", "[]"));
        assertThrows(PersistenceException.class,
                () -> SnapshotPreflight.checkCurrencies(unknownBalance, Set.of("dollar")));

        JsonModel unknownTx = SnapshotPreflight.parse(snapshot("{}", "["
                + tx("").replace("\"currencyId\":\"dollar\"", "\"currencyId\":\"gems\"") + "]"));
        assertThrows(PersistenceException.class,
                () -> SnapshotPreflight.checkCurrencies(unknownTx, Set.of("dollar")));

        // The literal currency id "token" is a normal allowlist member, never a denylist hit.
        JsonModel token = SnapshotPreflight.parse(snapshot(VALID_ACCOUNTS, "[]"));
        assertDoesNotThrow(() -> SnapshotPreflight.checkCurrencies(token, Set.of("dollar", "token")));
        assertEquals(Set.of("schemaVersion", "accounts", "transactions", "nonces"),
                token.toJsonObject().keySet());
    }
}
