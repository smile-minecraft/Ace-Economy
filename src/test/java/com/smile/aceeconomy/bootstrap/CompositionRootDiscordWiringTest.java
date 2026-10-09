package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordPayload;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordSendResult;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordTransport;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.ports.AuditException;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for the package-private {@link CompositionRoot#wireDiscord} seam.
 *
 * <p>These tests exercise the exact same static method that {@link CompositionRoot}'s production
 * {@code startApplication} calls. The seam takes the dependencies as explicit parameters so we
 * can substitute fakes (transaction repository, IO executor, transport factory, diagnostics
 * consumer) without booting Bukkit, AceLib, or any storage backend. Because the seam is a real
 * code path on the production graph, a green test here proves that the production wiring works
 * end-to-end — only the dependencies are stubbed.</p>
 */
class CompositionRootDiscordWiringTest {

    private ExecutorService ioExecutor;
    private InMemoryTransactionEventPublisher publisher;
    private final TransactionRepository transactions = new InMemoryTransactionRepository();
    private final ResourceOwner resources = new ResourceOwner();
    private final AtomicInteger transportCreated = new AtomicInteger();
    private CapturingLogHandler logHandler;
    private Logger logger;

    @BeforeEach
    void setUp() {
        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "composition-root-wiring-test");
            t.setDaemon(true);
            return t;
        });
        publisher = new InMemoryTransactionEventPublisher();
        logHandler = new CapturingLogHandler();
        logger = Logger.getLogger("composition-root-wiring-test-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        resources.close();
        ioExecutor.shutdownNow();
    }

    // ---- enabled + valid URL (production happy path) ----------------------

    @Test
    void enabledWithValidUrlConstructsTransportAndRegistersShutdownHook() {
        FakeTransport transport = new FakeTransport();
        DiscordWiring.TransportFactory factory = url -> {
            transportCreated.incrementAndGet();
            assertEquals("https://hooks.discord.example/services/foo", url,
                    "seam must pass the validated URL straight to the transport factory");
            return transport;
        };

        DiscordWiring.Outcome outcome = CompositionRoot.wireDiscord(
                transactions, ioExecutor, publisher, resources,
                true, "https://hooks.discord.example/services/foo",
                logger, factory, msg -> { /* capture diagnostics via logHandler below */ });

        assertTrue(outcome.active(),
                "production seam must mark wiring active when enabled + URL is valid");
        assertEquals(DiscordWiring.REASON_OK, outcome.rejectReason());
        assertNotNull(outcome.auditSink(), "outcome must expose the plain persistence sink");
        assertTrue(outcome.auditSink() instanceof PersistentAuditSink,
                "seam must keep the plain sink: notification travels only through post-commit");
        assertNotNull(outcome.postCommitListener(),
                "seam must expose the registered post-commit listener for shutdown cleanup");
        assertNotNull(outcome.shutdownHook(),
                "production seam must register a shutdown hook");
        assertEquals(1, transportCreated.get(),
                "transport factory must be invoked exactly once on the happy path");
        assertEquals(0, logHandler.records().size(),
                "no diagnostic should be logged when wiring succeeds");
    }

    @Test
    void enabledWiringRoutesPostCommitThroughTransport() throws Exception {
        FakeTransport transport = new FakeTransport();
        DiscordWiring.TransportFactory factory = url -> transport;

        DiscordWiring.Outcome outcome = CompositionRoot.wireDiscord(
                transactions, ioExecutor, publisher, resources,
                true, "https://hooks.discord.example/services/foo",
                logger, factory, msg -> { });

        // Driving the post-commit channel must reach the transport.
        Transaction tx = sampleTransaction();
        publisher.publishPostCommit(tx);

        // Wait for the async delivery to complete (transport future is completed synchronously
        // in FakeTransport, so the whenComplete fires before deliver returns).
        waitForTransportCall(transport, 2_000);

        assertEquals(1, transport.sent.size(),
                "one committed announcement must reach the transport exactly once");
        assertFalse(transport.sent.get(0).toJson().isEmpty());
    }

    @Test
    void enabledWiringAuditFailureStillReportedAndSkipsTransport() throws Exception {
        FakeTransport transport = new FakeTransport();
        // A throwing repository so the plain sink fails on record().
        ThrowingRepository throwingTransactions = new ThrowingRepository();
        DiscordWiring.TransportFactory factory = url -> transport;

        DiscordWiring.Outcome outcome = CompositionRoot.wireDiscord(
                throwingTransactions, ioExecutor, publisher, resources,
                true, "https://hooks.discord.example/services/foo",
                logger, factory, msg -> { });

        // The plain sink must propagate the persistence failure so the application's
        // contract (audit failure is reported, never swallowed) is preserved.
        // Executable throws Throwable so the lambda can re-throw the checked AuditException
        // directly without wrapping.
        AuditException thrown = assertThrows(AuditException.class, () -> {
            try {
                outcome.auditSink().record(sampleTransaction());
            } catch (AuditException propagated) {
                throw propagated;
            }
        });
        assertNotNull(thrown);

        // No post-commit announcement was published, so the transport stays silent.
        waitForNoTransportCall(transport, 200);
        assertEquals(0, transport.sent.size(),
                "transport must not be called without a committed announcement");
    }

    // ---- disabled / empty URL / invalid URL --------------------------------

    @Test
    void disabledDoesNotConstructTransport() {
        DiscordWiring.TransportFactory factory = url -> {
            transportCreated.incrementAndGet();
            fail("transport must not be constructed when disabled");
            return new FakeTransport();
        };

        DiscordWiring.Outcome outcome = CompositionRoot.wireDiscord(
                transactions, ioExecutor, publisher, resources,
                false, "https://hooks.discord.example/services/foo",
                logger, factory, msg -> fail("no diagnostic expected"));

        assertFalse(outcome.active());
        assertEquals(DiscordWiring.REASON_DISABLED, outcome.rejectReason());
        assertNull(outcome.shutdownHook());
        assertNull(outcome.postCommitListener(),
                "no post-commit listener may be registered when wiring is inactive");
        assertEquals(0, transportCreated.get());
        assertEquals(0, logHandler.records().size());
    }

    @Test
    void disabledWithEmptyUrlDoesNotConstructTransport() {
        DiscordWiring.Outcome outcome = CompositionRoot.wireDiscord(
                transactions, ioExecutor, publisher, resources,
                false, "",
                logger, url -> fail("not constructed"), msg -> fail("no diagnostic"));

        assertFalse(outcome.active());
        assertEquals(0, logHandler.records().size());
    }

    @Test
    void enabledWithEmptyUrlDoesNotConstructTransport() {
        DiscordWiring.Outcome outcome = CompositionRoot.wireDiscord(
                transactions, ioExecutor, publisher, resources,
                true, "",
                logger, url -> fail("not constructed"), msg -> fail("no diagnostic expected"));

        assertFalse(outcome.active());
        assertEquals(DiscordWiring.REASON_MISSING_URL, outcome.rejectReason());
        assertNull(outcome.shutdownHook());
        assertNull(outcome.postCommitListener(),
                "no post-commit listener may be registered when wiring is inactive");
    }

    @Test
    void enabledWithRelativeUrlDoesNotConstructTransport() {
        assertInvalidUrlNoTransport("/relative/path");
    }

    @Test
    void enabledWithFtpUrlDoesNotConstructTransport() {
        assertInvalidUrlNoTransport("ftp://hooks.discord.example/services/foo");
    }

    @Test
    void enabledWithMailtoUrlDoesNotConstructTransport() {
        assertInvalidUrlNoTransport("mailto:noreply@example.com");
    }

    @Test
    void enabledWithUserInfoUrlDoesNotConstructTransport() {
        assertInvalidUrlNoTransport("https://user:pass@hooks.discord.example/services/foo");
    }

    @Test
    void enabledWithMalformedUrlDoesNotConstructTransport() {
        assertInvalidUrlNoTransport("http://[invalid");
    }

    private void assertInvalidUrlNoTransport(String url) {
        DiscordWiring.TransportFactory factory = u -> {
            transportCreated.incrementAndGet();
            fail("transport must not be constructed for invalid url: " + u);
            return new FakeTransport();
        };

        DiscordWiring.Outcome outcome;
        try {
            outcome = CompositionRoot.wireDiscord(
                    transactions, ioExecutor, publisher, resources,
                    true, url, logger, factory, msg -> { });
        } catch (Exception e) {
            fail("invalid URL must not throw out of the seam: " + e.getMessage());
            return;
        }

        assertFalse(outcome.active(), "invalid url must not produce active wiring");
        assertEquals(DiscordWiring.REASON_INVALID_URL, outcome.rejectReason());
        assertNull(outcome.shutdownHook());
        assertNull(outcome.postCommitListener(),
                "no post-commit listener may be registered when wiring is inactive");
        assertEquals(0, transportCreated.get(), "no transport may be constructed");
    }

    // ---- URL / secret leakage ----------------------------------------------

    @Test
    void invalidUrlDiagnosticNeverContainsUrl() {
        String secretUrl = "ftp://SECRET-DISCORD-WEBHOOK.example/services/foo";
        // Use the production-default 6-arg overload so the diagnostic flows to the logger
        // exactly as it does in production.
        CompositionRoot.wireDiscord(
                transactions, ioExecutor, publisher, resources,
                true, secretUrl, logger);

        assertEquals(1, logHandler.records().size(),
                "exactly one diagnostic must be logged for invalid URL");
        String message = logHandler.records().get(0).getMessage();
        assertFalse(message.contains("SECRET-DISCORD-WEBHOOK"),
                "diagnostic must not echo url: " + message);
        assertFalse(message.contains(".example"),
                "diagnostic must not echo url host suffix: " + message);
        assertFalse(message.contains("services"),
                "diagnostic must not echo url path: " + message);
        assertFalse(message.contains(secretUrl),
                "diagnostic must not echo full url: " + message);
    }

    // ---- shutdown hook lifecycle ------------------------------------------

    @Test
    void registeredShutdownHookIsTheNotifierShutdown() {
        TrackingTransport transport = new TrackingTransport();
        DiscordWiring.TransportFactory factory = url -> transport;

        DiscordWiring.Outcome outcome = CompositionRoot.wireDiscord(
                transactions, ioExecutor, publisher, resources,
                true, "https://hooks.discord.example/services/foo",
                logger, factory, msg -> { });

        Runnable hook = outcome.shutdownHook();
        assertNotNull(hook);

        // Before hook invocation, transport must not be closed.
        assertFalse(transport.wasClosed(),
                "transport must not be closed before shutdown hook is invoked");

        // First invocation: must not throw and must close the transport.
        hook.run();
        assertTrue(transport.wasClosed(),
                "shutdown hook must invoke transport.close()");

        // Second invocation on the same notifier: must also be safe (notifier is already inactive
        // after the first call; this proves the wiring did not install a one-shot cleanup that
        // would throw on repeat).
        assertDoesNotThrow(() -> hook.run(),
                "calling shutdown hook twice must be idempotent and safe");
    }

    // ---- helpers -----------------------------------------------------------

    private Transaction sampleTransaction() {
        return new Transaction(
                UUID.randomUUID(),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "USD",
                Amount.of(100.0, 2),
                TransactionType.TRANSFER_OUT,
                Amount.of(500.0, 2),
                Amount.of(400.0, 2),
                Instant.now(),
                "transfer"
        );
    }

    private void waitForTransportCall(FakeTransport transport, long timeoutMs) throws InterruptedException {
        assertTrue(transport.awaitSent(timeoutMs),
                "transport must be called within timeout");
    }

    private void waitForNoTransportCall(FakeTransport transport, long timeoutMs) throws InterruptedException {
        assertFalse(transport.awaitSent(timeoutMs),
                "transport must not be called within timeout");
    }

    /** In-process fake transport; supports SUCCESS/FAIL modes without I/O. */
    private static final class FakeTransport implements DiscordTransport {
        enum Mode { SUCCESS, FAIL }
        volatile Mode mode = Mode.SUCCESS;
        final List<DiscordPayload> sent = new CopyOnWriteArrayList<>();
        private final CountDownLatch sentLatch = new CountDownLatch(1);

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            sent.add(payload);
            sentLatch.countDown();
            return switch (mode) {
                case SUCCESS -> CompletableFuture.completedFuture(DiscordSendResult.ok(204));
                case FAIL -> CompletableFuture.failedFuture(
                        new RuntimeException("simulated failure"));
            };
        }

        boolean awaitSent(long timeoutMs) {
            try {
                return sentLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Fake transport that tracks whether close() was called. */
    private static final class TrackingTransport implements DiscordTransport, AutoCloseable {
        private volatile boolean closed = false;

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            return CompletableFuture.completedFuture(DiscordSendResult.ok(204));
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean wasClosed() {
            return closed;
        }
    }

    /** Repository whose {@code append} throws a {@link com.smile.aceeconomy.ports.persistence.PersistenceException}
     * — exactly the type {@code PersistentAuditSink} catches and re-wraps as {@link AuditException}. */
    private static final class ThrowingRepository implements TransactionRepository {
        @Override
        public void append(Transaction transaction) throws com.smile.aceeconomy.ports.persistence.PersistenceException {
            throw new com.smile.aceeconomy.ports.persistence.PersistenceException("simulated audit failure");
        }

        @Override
        public void appendBatch(List<Transaction> transactions) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public void markReverted(UUID transactionId) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public boolean isReverted(UUID transactionId) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public List<Transaction> loadByAccount(UUID accountId) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public List<Transaction> loadAll() {
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    private static final class CapturingLogHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws SecurityException {
        }

        List<LogRecord> records() {
            return records;
        }
    }
}
