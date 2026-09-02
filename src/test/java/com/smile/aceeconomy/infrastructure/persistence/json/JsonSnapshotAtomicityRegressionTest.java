package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for prepared snapshots crossing JSON persistence operations. */
final class JsonSnapshotAtomicityRegressionTest {

    @TempDir
    Path dir;

    private JsonPersistenceBackend open(String name) {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve(name));
        backend.initialize();
        return backend;
    }

    @Test
    void staleExpectedSnapshotMustNotOverwriteTheLiveAccount() {
        JsonPersistenceBackend backend = open("save.json");
        try {
            UUID owner = UUID.randomUUID();
            backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            Account stale = backend.load(owner).orElseThrow();

            backend.save(stale, stale.deposit("dollar", Fixtures.amt("10.00")));
            assertThrows(PersistenceException.class,
                    () -> backend.save(stale, stale.deposit("dollar", Fixtures.amt("20.00"))));

            assertEquals(0, Fixtures.amt("110.00")
                    .compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
        } finally {
            backend.close();
        }
    }

    @Test
    void preparedRedemptionMustUseTheLiveModel() {
        JsonPersistenceBackend backend = open("prepared.json");
        try {
            UUID owner = UUID.randomUUID();
            backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            Account stale = backend.load(owner).orElseThrow();
            UUID nonce = UUID.randomUUID();
            Transaction transaction = new Transaction(UUID.randomUUID(), owner, null, "dollar",
                    Fixtures.amt("10.00"), TransactionType.DEPOSIT, Fixtures.amt("100.00"),
                    Fixtures.amt("110.00"), Instant.now(), "prepared");

            backend.redeem(UUID.randomUUID(), owner, "dollar", Fixtures.amt("20.00"));
            RedemptionResult result = backend.redeemPrepared(nonce, stale.deposit("dollar", Fixtures.amt("10.00")),
                    transaction);

            assertTrue(result.isCommitted());
            assertEquals(0, Fixtures.amt("130.00")
                    .compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
        } finally {
            backend.close();
        }
    }

    @Test
    void reversalMustApplyItsDeltaToTheLiveModel() {
        JsonPersistenceBackend backend = open("reversal.json");
        try {
            UUID owner = UUID.randomUUID();
            backend.create(owner, "alice", Map.of("dollar", Fixtures.amt("100.00")));
            UUID originalId = UUID.randomUUID();
            backend.append(new Transaction(originalId, owner, null, "dollar", Fixtures.amt("10.00"),
                    TransactionType.DEPOSIT, Fixtures.amt("100.00"), Fixtures.amt("110.00"),
                    Instant.now(), "deposit"));
            Account staleReversed = backend.load(owner).orElseThrow()
                    .withdraw("dollar", Fixtures.amt("10.00"));
            Account current = backend.load(owner).orElseThrow();
            backend.save(current, current.deposit("dollar", Fixtures.amt("20.00")));

            Transaction reversal = new Transaction(UUID.randomUUID(), owner, null, "dollar",
                    Fixtures.amt("10.00"), TransactionType.WITHDRAW, Fixtures.amt("110.00"),
                    Fixtures.amt("100.00"), Instant.now(), "rollback:deposit");
            backend.applyReversal(List.of(staleReversed), List.of(reversal), List.of(originalId));

            assertEquals(0, Fixtures.amt("110.00")
                    .compareTo(backend.load(owner).orElseThrow().balanceOf("dollar")));
        } finally {
            backend.close();
        }
    }
}
