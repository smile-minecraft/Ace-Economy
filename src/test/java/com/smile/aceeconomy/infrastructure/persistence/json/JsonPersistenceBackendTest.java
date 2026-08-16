package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real, offline persistence contract test for {@link JsonPersistenceBackend} using a temp file. */
final class JsonPersistenceBackendTest {

    @TempDir
    Path dir;

    private JsonPersistenceBackend newBackend() {
        return new JsonPersistenceBackend(dir.resolve("data.json"));
    }

    private Transaction sampleTx(UUID id, UUID account, String currency, String amount,
                                TransactionType type, String before, String after) {
        return Fixtures.tx(id, account, null, currency,
                Fixtures.amt(amount), type, Fixtures.amt(before), Fixtures.amt(after));
    }

    @Test
    void initializeCreatesEmptyFile() throws Exception {
        JsonPersistenceBackend backend = newBackend();
        backend.initialize();
        assertTrue(backend.isInitialized());
        assertTrue(dir.resolve("data.json").toFile().exists());
        assertTrue(backend.loadAll().isEmpty());
    }

    @Test
    void appendPersistsAndSurvivesReload() throws Exception {
        JsonPersistenceBackend backend = newBackend();
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "10.00", TransactionType.DEPOSIT, "0.00", "10.00"));

        // Reload from a fresh instance backed by the same file.
        JsonPersistenceBackend reloaded = newBackend();
        reloaded.initialize();
        List<Transaction> all = reloaded.loadAll();
        assertEquals(1, all.size());
        assertEquals(id, all.get(0).id());
        assertEquals(account, all.get(0).accountId());
        assertEquals(TransactionType.DEPOSIT, all.get(0).type());
        assertEquals(0, Fixtures.amt("10.00").compareTo(all.get(0).amount()));
    }

    @Test
    void appendBatchIsAtomicAndVisibleAfterReload() throws Exception {
        JsonPersistenceBackend backend = newBackend();
        backend.initialize();
        UUID account = UUID.randomUUID();
        backend.appendBatch(List.of(
                sampleTx(UUID.randomUUID(), account, "dollar", "5.00", TransactionType.DEPOSIT, "0.00", "5.00"),
                sampleTx(UUID.randomUUID(), account, "dollar", "3.00", TransactionType.WITHDRAW, "5.00", "2.00")
        ));

        JsonPersistenceBackend reloaded = newBackend();
        reloaded.initialize();
        assertEquals(2, reloaded.loadAll().size());
        assertEquals(2, reloaded.loadByAccount(account).size());
    }

    @Test
    void duplicateAppendRejected() throws Exception {
        JsonPersistenceBackend backend = newBackend();
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));
        assertThrows(PersistenceException.class,
                () -> backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00")));
    }

    @Test
    void markRevertedAndIsReverted() throws Exception {
        JsonPersistenceBackend backend = newBackend();
        backend.initialize();
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        backend.append(sampleTx(id, account, "dollar", "1.00", TransactionType.DEPOSIT, "0.00", "1.00"));
        assertFalse(backend.isReverted(id));
        backend.markReverted(id);
        assertTrue(backend.isReverted(id));

        JsonPersistenceBackend reloaded = newBackend();
        reloaded.initialize();
        assertTrue(reloaded.isReverted(id));
    }

    @Test
    void markRevertedUnknownThrows() throws Exception {
        JsonPersistenceBackend backend = newBackend();
        backend.initialize();
        assertThrows(PersistenceException.class, () -> backend.markReverted(UUID.randomUUID()));
    }

    @Test
    void accountRoundTrip() throws Exception {
        JsonPersistenceBackend backend = newBackend();
        backend.initialize();
        UUID owner = UUID.randomUUID();
        Account created = backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
        assertTrue(backend.exists(owner));
        Account loaded = backend.load(owner).orElseThrow();
        assertEquals("alice", loaded.ownerName());
        assertEquals(0, Fixtures.amt("100.00").compareTo(loaded.balances().get("dollar")));
        assertEquals(created.owner(), loaded.owner());
    }
}
