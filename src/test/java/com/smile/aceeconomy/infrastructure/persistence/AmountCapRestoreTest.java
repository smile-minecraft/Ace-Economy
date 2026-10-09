package com.smile.aceeconomy.infrastructure.persistence;

import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Restore-time cap gate: a snapshot whose <em>balances</em> exceed the public
 * cap (or would not fit the amount column) is refused <em>before</em> any live
 * row is touched — original balances, history and consumed nonces stay exactly
 * as they were.
 *
 * <p>Audit rows in the snapshot are still restored as evidence (the reviewed
 * and accepted rule); only owed balances are capped. Plain reads stay lenient:
 * loading an over-cap row is not an error, the service gate diagnoses it on
 * the next transaction instead.</p>
 */
class AmountCapRestoreTest {

    private static final String CUR = "dollar";
    private static final String CAP = "1000000000000000";
    private static final String OVER_CAP = "1000000000000001.00";
    private static final String AT_CAP = "1000000000000000.00";

    @TempDir
    Path dir;

    private JsonPersistenceBackend jsonBackend(String file) {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve(file));
        backend.initialize();
        return backend;
    }

    private SqlBackend sqliteBackend(Path db) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        return backend;
    }

    /**
     * Rewrites one {@code "key": "from"} pair in a snapshot and fails unless exactly one
     * such pair exists. Anchoring on the key keeps the edit off other fields that hold the
     * same text, such as a transaction amount equal to the balance being patched.
     */
    private static byte[] patchValue(byte[] snapshot, String key, String from, String to) {
        String json = new String(snapshot, StandardCharsets.UTF_8);
        Pattern pair = Pattern.compile("(?<pre>\"" + Pattern.quote(key) + "\"\\s*:\\s*\")"
                + Pattern.quote(from) + "(?<post>\")");
        Matcher counter = pair.matcher(json);
        int hits = 0;
        while (counter.find()) {
            hits++;
        }
        assertEquals(1, hits,
                "seed shape changed: expected exactly one \"" + key + "\": \"" + from + "\"");
        return pair.matcher(json)
                .replaceFirst("${pre}" + Matcher.quoteReplacement(to) + "${post}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The full serialized live state. A refused restore must leave it byte-identical,
     * which also covers history and consumed nonces, not only the values asserted above.
     */
    private static byte[] snapshotBytes(JsonPersistenceBackend backend) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        backend.backup(out);
        return out.toByteArray();
    }

    private static byte[] snapshotBytes(SqlBackend backend) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        backend.backup(out);
        return out.toByteArray();
    }

    // ---- JSON ----

    @Test
    @DisplayName("JSON restore with an over-cap balance is refused; live data untouched")
    void jsonRestoreRefusesOverCapBalance() throws Exception {
        JsonPersistenceBackend backend = jsonBackend("restore-cap.json");
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID txId = UUID.randomUUID();
        backend.append(Fixtures.tx(txId, owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.DEPOSIT, Fixtures.amt("0.00"), Fixtures.amt("100.00")));
        UUID nonce = UUID.randomUUID();
        assertTrue(backend.consume(nonce));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        backend.backup(buf);
        byte[] tampered = patchValue(buf.toByteArray(), CUR, "100.00", OVER_CAP);
        byte[] before = snapshotBytes(backend);

        PersistenceException e = assertThrows(PersistenceException.class,
                () -> backend.restore(new ByteArrayInputStream(tampered)));
        assertTrue(e.getMessage().contains("exceeds maximum " + CAP),
                "refusal must name the cap, got: " + e.getMessage());

        assertEquals(0, Fixtures.amt("100.00").compareTo(
                backend.load(owner).orElseThrow().balanceOf(CUR)),
                "original balance must be untouched");
        assertEquals(1, backend.loadAll().size(), "history must be untouched");
        assertEquals(0, new BigDecimal("100.00").compareTo(
                backend.loadAll().get(0).amount().value()),
                "history amount must be untouched");
        assertTrue(backend.isConsumed(nonce), "consumed nonce must stay consumed");
        assertArrayEquals(before, snapshotBytes(backend),
                "refused restore must leave the live state byte-identical");

        // The backend is still healthy: the original snapshot restores fine.
        backend.restore(new ByteArrayInputStream(buf.toByteArray()));
        assertEquals(0, Fixtures.amt("100.00").compareTo(
                backend.load(owner).orElseThrow().balanceOf(CUR)));
    }

    @Test
    @DisplayName("JSON restore with an over-wide balance string is refused")
    void jsonRestoreRefusesOverWideBalance() throws Exception {
        JsonPersistenceBackend backend = jsonBackend("restore-wide.json");
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("5.00")));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        backend.backup(buf);
        String wide = "1." + "0".repeat(80); // in range numerically, wider than VARCHAR(64)
        byte[] tampered = patchValue(buf.toByteArray(), CUR, "5.00", wide);
        byte[] before = snapshotBytes(backend);

        PersistenceException e = assertThrows(PersistenceException.class,
                () -> backend.restore(new ByteArrayInputStream(tampered)));
        assertTrue(e.getMessage().contains("exceeds column width 64"),
                "refusal must name the column width, got: " + e.getMessage());
        assertEquals(0, Fixtures.amt("5.00").compareTo(
                backend.load(owner).orElseThrow().balanceOf(CUR)),
                "original balance must be untouched");
        assertArrayEquals(before, snapshotBytes(backend),
                "refused restore must leave the live state byte-identical");
    }

    // ---- SQL ----

    @Test
    @DisplayName("SQL restore with an over-cap balance is refused; live rows untouched")
    void sqlRestoreRefusesOverCapBalance() throws Exception {
        Path db = dir.resolve("restore-cap.db");
        SqlBackend backend = sqliteBackend(db);
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID txId = UUID.randomUUID();
        backend.append(Fixtures.tx(txId, owner, null, CUR, Fixtures.amt("100.00"),
                TransactionType.DEPOSIT, Fixtures.amt("0.00"), Fixtures.amt("100.00")));
        UUID nonce = UUID.randomUUID();
        assertTrue(backend.consume(nonce));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        backend.backup(buf);
        byte[] tampered = patchValue(buf.toByteArray(), CUR, "100.00", OVER_CAP);
        byte[] before = snapshotBytes(backend);

        try {
            PersistenceException e = assertThrows(PersistenceException.class,
                    () -> backend.restore(new ByteArrayInputStream(tampered)));
            assertTrue(e.getMessage().contains("exceeds maximum " + CAP),
                    "refusal must name the cap, got: " + e.getMessage());

            assertEquals(0, Fixtures.amt("100.00").compareTo(
                    backend.load(owner).orElseThrow().balanceOf(CUR)),
                    "original balance must be untouched");
            assertEquals(1, backend.loadAll().size(), "history must be untouched");
            assertEquals(0, new BigDecimal("100.00").compareTo(
                    backend.loadAll().get(0).amount().value()),
                    "history amount must be untouched");
            assertTrue(backend.isConsumed(nonce), "consumed nonce must stay consumed");
            assertArrayEquals(before, snapshotBytes(backend),
                    "refused restore must leave the live state byte-identical");

            backend.restore(new ByteArrayInputStream(buf.toByteArray()));
            assertEquals(0, Fixtures.amt("100.00").compareTo(
                    backend.load(owner).orElseThrow().balanceOf(CUR)));
        } finally {
            backend.close();
        }
    }

    @Test
    @DisplayName("SQL in-range restore still succeeds after a refused one")
    void sqlInRangeRestoreSucceeds() throws Exception {
        Path db = dir.resolve("restore-ok.db");
        SqlBackend backend = sqliteBackend(db);
        try {
            UUID owner = UUID.randomUUID();
            backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("250.00")));
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            backend.backup(buf);

            backend.create(UUID.randomUUID(), "bob", Map.of(CUR, Fixtures.amt("10.00")));
            backend.restore(new ByteArrayInputStream(buf.toByteArray()));

            assertEquals(0, Fixtures.amt("250.00").compareTo(
                    backend.load(owner).orElseThrow().balanceOf(CUR)));
            assertEquals(1, backend.listAll().size(), "restore replaces live state on success");
        } finally {
            backend.close();
        }
    }

    @Test
    @DisplayName("restore keeps over-cap audit evidence while balances stay capped")
    void restoreRestoresOverCapEvidence() throws Exception {
        // A snapshot whose audit rows reference over-cap values but whose balances
        // are all in range must restore: audit is evidence, balances are capped.
        JsonPersistenceBackend backend = jsonBackend("restore-evidence.json");
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("100.00")));
        UUID txId = UUID.randomUUID();
        backend.append(Fixtures.tx(txId, owner, null, CUR, Fixtures.amt("50.00"),
                TransactionType.DEPOSIT, Fixtures.amt("0.00"), Fixtures.amt("50.00")));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        backend.backup(buf);
        // Patch only the audit amount: the live balance stays "100.00", and the
        // balance-after "50.00" in the same row is left as it was.
        byte[] tampered = patchValue(buf.toByteArray(), "amount", "50.00", OVER_CAP);

        backend.restore(new ByteArrayInputStream(tampered));

        assertEquals(0, Fixtures.amt("100.00").compareTo(
                backend.load(owner).orElseThrow().balanceOf(CUR)),
                "in-range balance restores unchanged");
        assertEquals(1, backend.loadAll().size());
        assertEquals(0, new BigDecimal(OVER_CAP).compareTo(
                backend.loadAll().get(0).amount().value()),
                "over-cap audit evidence restores readably");
    }

    @Test
    @DisplayName("cap gate sees magnitude: negative over-cap balances are refused too")
    void restoreRefusesNegativeOverCap() throws Exception {
        JsonPersistenceBackend backend = jsonBackend("restore-neg.json");
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("7.00")));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        backend.backup(buf);
        byte[] tampered = patchValue(buf.toByteArray(), CUR, "7.00", "-1000000000000001.00");
        byte[] before = snapshotBytes(backend);

        PersistenceException e = assertThrows(PersistenceException.class,
                () -> backend.restore(new ByteArrayInputStream(tampered)));
        assertTrue(e.getMessage().contains("exceeds maximum " + CAP), "got: " + e.getMessage());
        assertEquals(0, Fixtures.amt("7.00").compareTo(
                backend.load(owner).orElseThrow().balanceOf(CUR)));
        assertArrayEquals(before, snapshotBytes(backend),
                "refused restore must leave the live state byte-identical");
    }

    @Test
    @DisplayName("restore accepts a balance exactly at the cap (boundary inclusive)")
    void restoreAcceptsBalanceExactlyAtCap() throws Exception {
        JsonPersistenceBackend backend = jsonBackend("restore-boundary.json");
        UUID owner = UUID.randomUUID();
        backend.create(owner, "alice", Map.of(CUR, Fixtures.amt("5.00")));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        backend.backup(buf);
        byte[] atCap = patchValue(buf.toByteArray(), CUR, "5.00", AT_CAP);

        backend.restore(new ByteArrayInputStream(atCap));

        assertEquals(0, Fixtures.amt(AT_CAP).compareTo(
                backend.load(owner).orElseThrow().balanceOf(CUR)),
                "a balance exactly at the cap must restore");
    }
}
