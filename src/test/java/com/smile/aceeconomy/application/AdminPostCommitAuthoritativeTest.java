package com.smile.aceeconomy.application;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.persistence.AtomicAdminMutationStore;
import com.smile.aceeconomy.ports.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Post-commit notification must carry the authoritative committed record:
 * when the live balance moves between the service preview and the atomic
 * commit and the backend reports a committed cleanup failure, observers must
 * see the live-derived before/after with the real id, never the stale
 * preview, and the mutation must not execute twice.
 */
class AdminPostCommitAuthoritativeTest {

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
    }

    @Test
    void committedCleanupFailureNotifiesAuthoritativeBalancesNotThePreview(@TempDir Path dir)
            throws Exception {
        JsonPersistenceBackend backend =
                new JsonPersistenceBackend(dir.resolve("admin-postcommit.json"));
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            AtomicAdminMutationStore faulting = (preparedTx, debtPolicy) -> {
                Account live = backend.load(target).orElseThrow();
                Account pushed = live.deposit("dollar", Fixtures.amt("5.00"));
                backend.save(live, pushed);
                var committed = backend.commitAdminMutation(preparedTx, debtPolicy);
                throw new PersistenceException(
                        "injected post-commit cleanup failure after admin commit",
                        null, true);
            };
            InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            publisher.registerPostCommit(notified::add);
            EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                    Amount.zero(2), backend, new PersistentAuditSink(backend),
                    new FixedClock(), publisher, null, faulting);

            EconomyResult<Amount> result = service.deposit(target, "dollar",
                    Fixtures.amt("10.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-check"));

            assertTrue(result.isSuccess(),
                    "a committed cleanup failure must still report success, got " + result);
            assertEquals(0, Fixtures.amt("15.00").compareTo(result.value()),
                    "the success value must be the authoritative after, not the preview");
            assertEquals(1, notified.size(),
                    "a committed mutation must notify exactly once");
            Transaction seen = notified.get(0);
            assertEquals(0, Fixtures.amt("5.00").compareTo(seen.balanceBefore()),
                    "notification must carry the live-derived before, not the stale preview");
            assertEquals(0, Fixtures.amt("15.00").compareTo(seen.balanceAfter()),
                    "notification must carry the live-derived after, not the stale preview");
            List<Transaction> rows = backend.loadAll();
            assertEquals(1, rows.size(), "the mutation must not execute twice");
            assertEquals(rows.get(0).id(), seen.id(),
                    "notification must carry the real committed id");
            assertEquals(0, Fixtures.amt("5.00").compareTo(rows.get(0).balanceBefore()));
            assertEquals(0, Fixtures.amt("15.00").compareTo(rows.get(0).balanceAfter()));
            assertEquals(0, Fixtures.amt("15.00").compareTo(
                    backend.load(target).orElseThrow().balanceOf("dollar")));
        } finally {
            backend.close();
        }
    }
}
