package com.smile.aceeconomy.infrastructure.persistence.json;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.ports.inmemory.FixedClock;
import com.smile.aceeconomy.ports.persistence.CommittedAdminMutationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real JSON backend's committed-receipt branch, driven through its
 * production publish seam: when the live file was already replaced but the
 * post-publish directory sync fails, {@code commitAdminMutation} must report
 * a {@link CommittedAdminMutationException} carrying the true live-derived
 * balances and the real transaction id, and the service must notify that
 * authoritative receipt exactly once without a second write.
 */
class AdminPostCommitJsonReceiptTest {

    /** Delegates to the real filesystem but fails the post-publish sync while armed. */
    private static final class ArmedPublisher extends DefaultJsonFilePublisher {
        private final AtomicBoolean armed = new AtomicBoolean(false);

        @Override
        public void syncDirectory(Path directory) throws IOException {
            if (armed.get()) {
                throw new JsonPostPublishException(
                        "injected post-publish directory-sync failure",
                        new IOException("injected directory sync failure"));
            }
            super.syncDirectory(directory);
        }
    }

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
    }

    @Test
    void backendReportsCommittedReceiptWithTrueBalances(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("json-receipt.json");
        ArmedPublisher publisher = new ArmedPublisher();
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file, publisher);
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            UUID txId = UUID.randomUUID();
            Transaction prepared = new Transaction(txId, target, null, "dollar",
                    Fixtures.amt("10.00"), TransactionType.DEPOSIT,
                    Fixtures.amt("0.00"), Fixtures.amt("10.00"), Instant.now(),
                    "harbor-check", UUID.randomUUID(), "Morgan");
            publisher.armed.set(true);
            CommittedAdminMutationException committed = assertThrows(
                    CommittedAdminMutationException.class,
                    () -> backend.commitAdminMutation(prepared, DebtPolicy.disabled()),
                    "a post-publish sync failure must travel as a committed receipt");
            assertEquals(txId, committed.transactionId(),
                    "the receipt must carry the real committed id");
            assertEquals(0, Fixtures.amt("0.00").compareTo(committed.balanceBefore()));
            assertEquals(0, Fixtures.amt("10.00").compareTo(committed.balanceAfter()));
            assertTrue(committed.isCommitted());
        } finally {
            backend.close();
        }

        JsonPersistenceBackend verify = new JsonPersistenceBackend(file);
        try {
            verify.initialize();
            List<Transaction> rows = verify.loadAll();
            assertEquals(1, rows.size(), "the mutation must have committed exactly once");
            assertEquals(0, Fixtures.amt("0.00").compareTo(rows.get(0).balanceBefore()));
            assertEquals(0, Fixtures.amt("10.00").compareTo(rows.get(0).balanceAfter()));
        } finally {
            verify.close();
        }
    }

    @Test
    void serviceNotifiesAuthoritativeReceiptOnceWithoutRewrite(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("json-service.json");
        ArmedPublisher publisher = new ArmedPublisher();
        JsonPersistenceBackend backend = new JsonPersistenceBackend(file, publisher);
        backend.initialize();
        try {
            UUID target = UUID.randomUUID();
            UUID operator = UUID.randomUUID();
            backend.create(target, "Alex", Map.of("dollar", Fixtures.amt("0.00")));

            InMemoryTransactionEventPublisher events = new InMemoryTransactionEventPublisher();
            List<Transaction> notified = new CopyOnWriteArrayList<>();
            events.registerPostCommit(notified::add);
            EconomyService service = new EconomyService(currencies(), DebtPolicy.disabled(),
                    Amount.zero(2), backend, new PersistentAuditSink(backend),
                    new FixedClock(), events, null, null);

            publisher.armed.set(true);
            EconomyResult<Amount> result = service.deposit(target, "dollar",
                    Fixtures.amt("10.00"),
                    AdminAudit.player(operator, "Morgan", "harbor-check"));

            assertTrue(result.isSuccess(),
                    "a committed cleanup failure must still report success, got " + result);
            assertEquals(0, Fixtures.amt("10.00").compareTo(result.value()));
            assertEquals(1, notified.size(),
                    "a committed mutation must notify exactly once");
            Transaction seen = notified.get(0);
            assertEquals(0, Fixtures.amt("0.00").compareTo(seen.balanceBefore()));
            assertEquals(0, Fixtures.amt("10.00").compareTo(seen.balanceAfter()));
            List<Transaction> rows = backend.loadAll();
            assertEquals(1, rows.size(), "the mutation must not execute twice");
            assertEquals(rows.get(0).id(), seen.id(),
                    "notification must carry the real committed id");
        } finally {
            backend.close();
        }

        JsonPersistenceBackend verify = new JsonPersistenceBackend(file);
        try {
            verify.initialize();
            List<Transaction> rows = verify.loadAll();
            assertEquals(1, rows.size(), "the committed mutation must be durable exactly once");
            assertEquals(0, Fixtures.amt("10.00")
                    .compareTo(verify.load(
                            rows.get(0).accountId()).orElseThrow().balanceOf("dollar")));
        } finally {
            verify.close();
        }
    }
}
