package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.application.TransferResult;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordPayload;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordSendResult;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordTransport;
import com.smile.aceeconomy.infrastructure.persistence.Fixtures;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonPersistenceBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlBackend;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqlConnectionProvider;
import com.smile.aceeconomy.infrastructure.persistence.sql.SqliteDialect;
import com.smile.aceeconomy.ports.AccountRepository;
import com.smile.aceeconomy.ports.AuditSink;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-path Discord coverage: a real JSON/SQLite backend behind the real
 * {@link CompositionRoot#wireDiscord} seam with a Discord fake transport. An atomic transfer
 * must notify Discord once per committed leg with the real ids, persist exactly two audit
 * rows, and stay silent for cancelled or failed operations.
 */
class DiscordPostCommitIntegrationTest {

    private static final String WEBHOOK = "https://hooks.discord.example/services/foo";

    private ExecutorService ioExecutor;
    private final ResourceOwner resources = new ResourceOwner();
    private Logger logger;

    @BeforeEach
    void setUp() {
        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "discord-postcommit-test");
            t.setDaemon(true);
            return t;
        });
        logger = Logger.getLogger("discord-postcommit-test-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
    }

    @AfterEach
    void tearDown() {
        resources.close();
        ioExecutor.shutdownNow();
    }

    @Test
    void sqliteAtomicTransferNotifiesDiscordWithRealIdsAndNoDuplicateAudit(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("discord-sqlite.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        SqlBackend backend = new SqlBackend(new SqlConnectionProvider(conn), new SqliteDialect());
        backend.initialize();
        try {
            runAtomicTransferCase(backend, backend);
        } finally {
            try {
                backend.close();
            } catch (Exception ignored) {
                // Durability was verified above; the provider may already be closed.
            }
        }
    }

    @Test
    void jsonAtomicTransferNotifiesDiscordWithRealIdsAndNoDuplicateAudit(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("discord.json"));
        backend.initialize();
        runAtomicTransferCase(backend, backend);
    }

    @Test
    void cancelledTransferNeverNotifiesDiscord(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("discord-cancel.json"));
        backend.initialize();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        backend.create(alice, "Alice", Map.of("dollar", Fixtures.amt("1000.00")));
        backend.create(bob, "Bob", Map.of("dollar", Fixtures.amt("1000.00")));

        LatchTransport transport = new LatchTransport(2);
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        AuditSink audit = wire(publisher, backend, transport);
        EconomyService svc = service(backend, audit, publisher);
        publisher.register(event -> event.cancel());

        EconomyResult<TransferResult> r = svc.transfer(alice, bob, "dollar", Amount.of(100, 2));
        assertTrue(r.isFailure(), "cancelled transfer must fail");

        assertFalse(transport.awaitSends(500),
                "a cancelled transfer must not notify Discord");
        assertEquals(0, transport.sent.size());
        assertTrue(backend.loadAll().isEmpty(), "a cancelled transfer must leave no audit rows");
    }

    @Test
    void failedTransferNeverNotifiesDiscord(@TempDir Path dir) throws Exception {
        JsonPersistenceBackend backend = new JsonPersistenceBackend(dir.resolve("discord-fail.json"));
        backend.initialize();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        backend.create(alice, "Alice", Map.of("dollar", Fixtures.amt("1000.00")));
        backend.create(bob, "Bob", Map.of("dollar", Fixtures.amt("1000.00")));

        LatchTransport transport = new LatchTransport(2);
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        AuditSink audit = wire(publisher, backend, transport);
        EconomyService svc = new EconomyService(currencies(), DebtPolicy.disabled(), Amount.zero(2),
                backend, audit, Instant::now, publisher);

        // Debt is disabled and 5000 exceeds the 1000 balance: rejected before any commit.
        EconomyResult<TransferResult> r = svc.transfer(alice, bob, "dollar", Amount.of(5000, 2));
        assertTrue(r.isFailure(), "overdrawn transfer must fail");

        assertFalse(transport.awaitSends(500),
                "an uncommitted transfer must not notify Discord");
        assertEquals(0, transport.sent.size());
        assertTrue(backend.loadAll().isEmpty(), "an uncommitted transfer must leave no audit rows");
    }

    private void runAtomicTransferCase(AccountRepository accounts, TransactionRepository auditRows) throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        accounts.create(alice, "Alice", Map.of("dollar", Fixtures.amt("1000.00")));
        accounts.create(bob, "Bob", Map.of("dollar", Fixtures.amt("1000.00")));

        LatchTransport transport = new LatchTransport(2);
        InMemoryTransactionEventPublisher publisher = new InMemoryTransactionEventPublisher();
        AuditSink audit = wire(publisher, (TransactionRepository) accounts, transport);
        EconomyService svc = service(accounts, audit, publisher);
        List<Transaction> channelSeen = new CopyOnWriteArrayList<>();
        publisher.registerPostCommit(channelSeen::add);

        EconomyResult<TransferResult> r = svc.transfer(alice, bob, "dollar", Amount.of(100, 2));
        assertTrue(r.isSuccess(), "transfer must succeed, got " + r);

        assertTrue(transport.awaitSends(5_000),
                "the atomic transfer must notify Discord once per committed leg");
        assertEquals(2, transport.sent.size(),
                "Discord must be notified exactly once per committed leg: no silence, no duplication");
        assertTrue(transport.sent.get(0).toJson().contains("TRANSFER_OUT"),
                "first notification must describe the sender leg");
        assertTrue(transport.sent.get(1).toJson().contains("TRANSFER_IN"),
                "second notification must describe the receiver leg");

        List<Transaction> rows = auditRows.loadAll();
        assertEquals(2, rows.size(),
                "the shared store must hold exactly the two atomic audit rows");
        Set<UUID> storedIds = new HashSet<>();
        for (Transaction t : rows) {
            storedIds.add(t.id());
        }
        assertEquals(Set.of(r.value().outTransactionId(), r.value().inTransactionId()), storedIds,
                "stored audit rows must be exactly the notified ones: real ids, no duplicates");

        assertEquals(2, channelSeen.size(), "the post-commit channel must carry both legs");
        assertEquals(r.value().outTransactionId(), channelSeen.get(0).id());
        assertEquals(r.value().inTransactionId(), channelSeen.get(1).id());
    }

    private AuditSink wire(InMemoryTransactionEventPublisher publisher, TransactionRepository transactions,
                           LatchTransport transport) {
        DiscordWiring.Outcome outcome = CompositionRoot.wireDiscord(
                transactions, ioExecutor, publisher, resources,
                true, WEBHOOK, logger, url -> transport, msg -> { });
        assertTrue(outcome.active(), "wiring must be active for the production path");
        return outcome.auditSink();
    }

    private EconomyService service(AccountRepository accounts, AuditSink audit,
                                   InMemoryTransactionEventPublisher publisher) {
        return new EconomyService(currencies(), DebtPolicy.disabled(), Amount.zero(2),
                accounts, audit, Instant::now, publisher);
    }

    private static CurrencyRegistry currencies() {
        return CurrencyRegistry.of(List.of(Currency.define("dollar", "Dollar", "$", 2, true)));
    }

    /** Fake transport that releases once the expected number of payloads has arrived. */
    private static final class LatchTransport implements DiscordTransport {
        final List<DiscordPayload> sent = new CopyOnWriteArrayList<>();
        private final CountDownLatch sends;

        LatchTransport(int expectedSends) {
            this.sends = new CountDownLatch(expectedSends);
        }

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            sent.add(payload);
            sends.countDown();
            return CompletableFuture.completedFuture(DiscordSendResult.ok(204));
        }

        boolean awaitSends(long timeoutMs) {
            try {
                return sends.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
