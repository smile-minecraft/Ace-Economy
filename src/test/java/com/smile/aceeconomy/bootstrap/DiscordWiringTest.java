package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordPayload;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordSendResult;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordTransport;
import com.smile.aceeconomy.ports.AuditException;
import com.smile.aceeconomy.ports.AuditSink;
import com.smile.aceeconomy.ports.inmemory.InMemoryTransactionRepository;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import javax.net.ssl.SSLSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real wiring tests for the Discord notification seam in {@link DiscordWiring}.
 *
 * <p>Each test boots {@link DiscordWiring#wire} with an injected {@link DiscordWiring.TransportFactory}
 * so it can count transport constructions without any network I/O. The wiring tests prove the
 * three bootstrap paths (enabled + valid URL, disabled, invalid URL) and that no webhook URL or
 * userinfo ever leaks into a log message.</p>
 */
class DiscordWiringTest {

    private ExecutorService ioExecutor;
    private final TransactionRepository repository = new InMemoryTransactionRepository();
    private final ResourceOwner resources = new ResourceOwner();
    private final AtomicInteger transportCreated = new AtomicInteger();
    private CapturingLogHandler logHandler;
    private Logger logger;

    @BeforeEach
    void setUp() {
        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "discord-wiring-test");
            t.setDaemon(true);
            return t;
        });
        logHandler = new CapturingLogHandler();
        logger = Logger.getLogger("discord-wiring-test-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        resources.close();
        ioExecutor.shutdownNow();
    }

    // ---- enabled + valid URL ----------------------------------------------

    @Test
    void enabledWithValidUrlDecoratesAuditSinkAndRegistersShutdown() {
        DiscordWiring.TransportFactory factory = url -> {
            transportCreated.incrementAndGet();
            return new FakeTransport();
        };

        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                true, "https://hooks.discord.example/services/foo",
                logger, resources, factory);

        assertTrue(outcome.active());
        assertEquals(DiscordWiring.REASON_OK, outcome.rejectReason());
        assertNotNull(outcome.shutdownHook());
        assertEquals(1, transportCreated.get(), "transport must be created exactly once");
        assertNotNull(outcome.auditSink());
        assertFalse(outcome.auditSink() instanceof AuditSinkSpy,
                "enabled wiring must decorate the original audit sink");
        // No diagnostic should have been logged on a successful wiring.
        assertEquals(0, logHandler.records().size(),
                "no diagnostic should be logged when wiring succeeds");
    }

    @Test
    void enabledWiringRoutesNotificationsThroughTransport() throws Exception {
        FakeTransport transport = new FakeTransport();
        DiscordWiring.TransportFactory factory = url -> transport;

        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                true, "https://hooks.discord.example/services/foo",
                logger, resources, factory);

        Transaction tx = sampleTransaction();
        outcome.auditSink().record(tx);

        assertTrue(transport.awaitSent(2_000),
                "transport must receive the payload within timeout");

        assertEquals(1, transport.sent.size(), "decorated audit sink must call transport");
        assertFalse(transport.sent.get(0).toJson().isEmpty(), "payload must be JSON");
    }

    @Test
    void enabledWiringPersistsAuditEvenWhenTransportFails() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.mode = FakeTransport.Mode.FAIL;
        DiscordWiring.TransportFactory factory = url -> transport;

        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                true, "https://hooks.discord.example/services/foo",
                logger, resources, factory);

        Transaction tx = sampleTransaction();
        // Transport failure must not turn into AuditException.
        outcome.auditSink().record(tx);
        transport.awaitSent(2_000);

        assertEquals(1, ((InMemoryTransactionRepository) repository).all().size(),
                "inner audit must persist even on transport failure");
    }

    // ---- disabled ---------------------------------------------------------

    @Test
    void disabledDoesNotConstructTransport() {
        DiscordWiring.TransportFactory factory = url -> {
            transportCreated.incrementAndGet();
            fail("transport must not be constructed when disabled");
            return new FakeTransport();
        };

        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                false, "https://hooks.discord.example/services/foo",
                logger, resources, factory);

        assertFalse(outcome.active());
        assertEquals(DiscordWiring.REASON_DISABLED, outcome.rejectReason());
        assertNull(outcome.shutdownHook());
        assertEquals(0, transportCreated.get());
    }

    @Test
    void disabledWithoutUrlDoesNotLog() {
        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                false, "",
                logger, resources, url -> fail("not constructed"));

        assertFalse(outcome.active());
        assertEquals(0, logHandler.records().size());
    }

    // ---- enabled + empty URL ----------------------------------------------

    @Test
    void enabledWithEmptyUrlDoesNotConstructTransport() {
        DiscordWiring.TransportFactory factory = url -> {
            transportCreated.incrementAndGet();
            fail("transport must not be constructed when url is empty");
            return new FakeTransport();
        };

        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                true, "",
                logger, resources, factory);

        assertFalse(outcome.active());
        assertEquals(DiscordWiring.REASON_MISSING_URL, outcome.rejectReason());
        assertNull(outcome.shutdownHook());
        assertEquals(0, transportCreated.get());
    }

    @Test
    void enabledWithNullUrlDoesNotConstructTransport() {
        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                true, null,
                logger, resources, url -> fail("not constructed"));

        assertFalse(outcome.active());
        assertEquals(DiscordWiring.REASON_MISSING_URL, outcome.rejectReason());
    }

    // ---- enabled + invalid URL --------------------------------------------

    @Test
    void enabledWithRelativeUrlDoesNotConstructTransportAndDoesNotThrow() {
        assertInvalidUrlNoTransport("/relative/path");
    }

    @Test
    void enabledWithFtpUrlDoesNotConstructTransportAndDoesNotThrow() {
        assertInvalidUrlNoTransport("ftp://hooks.discord.example/services/foo");
    }

    @Test
    void enabledWithMailtoUrlDoesNotConstructTransportAndDoesNotThrow() {
        assertInvalidUrlNoTransport("mailto:noreply@example.com");
    }

    @Test
    void enabledWithFileUrlDoesNotConstructTransportAndDoesNotThrow() {
        assertInvalidUrlNoTransport("file:///etc/passwd");
    }

    @Test
    void enabledWithJavaScriptUrlDoesNotConstructTransportAndDoesNotThrow() {
        assertInvalidUrlNoTransport("javascript:alert(1)");
    }

    @Test
    void enabledWithUserInfoUrlDoesNotConstructTransportAndDoesNotThrow() {
        assertInvalidUrlNoTransport("https://user:pass@hooks.discord.example/services/foo");
    }

    @Test
    void enabledWithMalformedUrlDoesNotConstructTransportAndDoesNotThrow() {
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
            outcome = DiscordWiring.wire(
                    repository, ioExecutor,
                    true, url, logger, resources, factory);
        } catch (Exception e) {
            fail("invalid URL must not throw out of wire(): " + e.getMessage());
            return;
        }

        assertFalse(outcome.active(), "invalid url must not produce active wiring");
        assertEquals(DiscordWiring.REASON_INVALID_URL, outcome.rejectReason());
        assertNull(outcome.shutdownHook());
        assertEquals(0, transportCreated.get(), "no transport may be constructed");
    }

    // ---- URL / secret leakage ----------------------------------------------

    @Test
    void invalidUrlDiagnosticNeverContainsUrl() {
        // Use a URL that fails the scheme check so the secret content never reaches the diagnostic.
        String secretUrl = "ftp://SECRET-DISCORD-WEBHOOK.example/services/foo";
        DiscordWiring.wire(
                repository, ioExecutor,
                true, secretUrl, logger, resources, url -> fail("not constructed"));

        assertEquals(1, logHandler.records().size(),
                "exactly one diagnostic must be logged for invalid URL");
        String message = logHandler.records().get(0).getMessage();
        assertFalse(message.contains("SECRET-DISCORD-WEBHOOK"),
                "diagnostic must not echo url: " + message);
        assertFalse(message.contains("example"),
                "diagnostic must not echo url host: " + message);
        // The literal substring "discord" is allowed only when it appears as the
        // documented config-key reference "discord.webhook-url", not as part of an
        // actual webhook host. The diagnostic contains the config-key reference by
        // design; this assertion is a guard against accidentally widening it.
        assertFalse(message.contains(".example"),
                "diagnostic must not echo url host suffix: " + message);
        assertFalse(message.contains("services"),
                "diagnostic must not echo url path: " + message);
        assertFalse(message.contains(secretUrl),
                "diagnostic must not echo full url: " + message);
    }

    @Test
    void throwingDiagnosticsConsumerOnInvalidUrlDoesNotFailStartup() {
        // A diagnostics consumer (or logger handler) that throws must not propagate out of
        // wire(). CompositionRoot.startApplication calls wire(); an exception here would abort
        // the entire plugin startup.
        //
        // Simulates a broken I/O handler, a logger that wraps an unchecked exception,
        // or any user-installed Consumer that throws on accept().
        AtomicInteger consumerCalls = new AtomicInteger();
        java.util.function.Consumer<String> throwingConsumer = msg -> {
            consumerCalls.incrementAndGet();
            throw new RuntimeException("simulated handler explosion: " + msg);
        };
        DiscordWiring.TransportFactory factory = url -> {
            transportCreated.incrementAndGet();
            fail("transport must not be constructed for invalid URL");
            return new FakeTransport();
        };

        DiscordWiring.Outcome outcome;
        try {
            outcome = DiscordWiring.wire(
                    repository, ioExecutor,
                    true, "ftp://SECRET-DISCORD-WEBHOOK.example/x",
                    logger, resources, factory, throwingConsumer);
        } catch (Exception e) {
            fail("wire() must swallow diagnostics consumer exceptions, not propagate to startup: "
                    + e.getMessage());
            return;
        }

        assertFalse(outcome.active(),
                "invalid URL with throwing consumer must still return inactive outcome");
        assertEquals(DiscordWiring.REASON_INVALID_URL, outcome.rejectReason());
        assertNull(outcome.shutdownHook(),
                "no shutdown hook may be registered when wiring failed");
        assertEquals(1, consumerCalls.get(),
                "diagnostics consumer must have been invoked exactly once");
        assertEquals(0, transportCreated.get(),
                "transport must NOT have been constructed when wiring failed");
    }

    @Test
    void throwingDiagnosticsConsumerThrowsErrorOnInvalidUrlDoesNotFailStartup() {
        // Error path: user/test supplied diagnostics callback may throw Error (e.g. AssertionError,
        // NoClassDefFoundError from a broken handler). It must be isolated at the wiring boundary
        // exactly like RuntimeException — startup must not fail.
        AtomicInteger consumerCalls = new AtomicInteger();
        java.util.function.Consumer<String> throwingConsumer = msg -> {
            consumerCalls.incrementAndGet();
            throw new AssertionError("simulated Error from diagnostics: " + msg);
        };
        DiscordWiring.TransportFactory factory = url -> {
            transportCreated.incrementAndGet();
            fail("transport must not be constructed for invalid URL");
            return new FakeTransport();
        };

        DiscordWiring.Outcome outcome;
        try {
            outcome = DiscordWiring.wire(
                    repository, ioExecutor,
                    true, "ftp://SECRET-DISCORD-WEBHOOK.example/x",
                    logger, resources, factory, throwingConsumer);
        } catch (Throwable e) {
            fail("wire() must swallow diagnostics consumer Errors, not propagate to startup: "
                    + e.getMessage());
            return;
        }

        assertFalse(outcome.active(),
                "invalid URL with throwing Error consumer must still return inactive outcome");
        assertEquals(DiscordWiring.REASON_INVALID_URL, outcome.rejectReason());
        assertNull(outcome.shutdownHook(),
                "no shutdown hook may be registered when wiring failed");
        assertEquals(1, consumerCalls.get(),
                "diagnostics consumer must have been invoked exactly once");
        assertEquals(0, transportCreated.get(),
                "transport must NOT have been constructed when wiring failed");
    }

    @Test
    void invalidUrlDiagnosticIsFixed() {
        DiscordWiring.wire(
                repository, ioExecutor,
                true, "ftp://a.example/x", logger, resources, url -> fail("not constructed"));
        DiscordWiring.wire(
                repository, ioExecutor,
                true, "mailto:b@example.com", logger, resources, url -> fail("not constructed"));
        DiscordWiring.wire(
                repository, ioExecutor,
                true, "javascript:alert(1)", logger, resources, url -> fail("not constructed"));

        assertEquals(3, logHandler.records().size());
        String first = logHandler.records().get(0).getMessage();
        String second = logHandler.records().get(1).getMessage();
        String third = logHandler.records().get(2).getMessage();
        assertEquals(first, second, "diagnostic must be the same string for every invalid url");
        assertEquals(second, third, "diagnostic must be the same string for every invalid url");
    }

    // ---- shutdown hook -----------------------------------------------------

    @Test
    void shutdownHookIsRegisteredAndRunsWithoutThrowing() {
        DiscordWiring.TransportFactory factory = url -> new FakeTransport();
        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                true, "https://hooks.discord.example/services/foo",
                logger, resources, factory, msg -> { /* no-op */ });
        Runnable hook = outcome.shutdownHook();
        assertNotNull(hook);
        hook.run(); // direct invocation should not throw
    }

    @Test
    void shutdownHookClosesAutoCloseableTransport() {
        CloseTrackingTransport transport = new CloseTrackingTransport();
        DiscordWiring.TransportFactory factory = url -> transport;
        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                true, "https://hooks.discord.example/services/foo",
                logger, resources, factory, msg -> { /* no-op */ });

        assertFalse(transport.isClosed(),
                "transport must not be closed immediately after wiring");
        Runnable hook = outcome.shutdownHook();
        assertNotNull(hook);
        hook.run();
        assertTrue(transport.isClosed(),
                "shutdown hook must close the transport");
    }

    @Test
    void shutdownHookToleratesCloseException() {
        ThrowingCloseTransport transport = new ThrowingCloseTransport();
        DiscordWiring.TransportFactory factory = url -> transport;
        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                true, "https://hooks.discord.example/services/foo",
                logger, resources, factory, msg -> { /* no-op */ });

        Runnable hook = outcome.shutdownHook();
        assertNotNull(hook);
        hook.run(); // must not throw even though close() throws
        assertTrue(transport.closeCalled.get() > 0,
                "close must have been attempted");
    }

    @Test
    void shutdownDrainsNotifierBeforeClosingTransport() throws Exception {
        // Confirms teardown ordering: notifier.shutdown() completes its pending deliveries
        // before the transport is closed. This ensures no in-flight requests are lost.
        PendingFutureTransport transport = new PendingFutureTransport();
        DiscordWiring.TransportFactory factory = url -> transport;
        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                true, "https://hooks.discord.example/services/foo",
                logger, resources, factory, msg -> { /* no-op */ });

        // Send a payload so there's an in-flight future in the notifier.
        Transaction tx = new Transaction(
                UUID.randomUUID(),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "USD",
                com.smile.aceeconomy.domain.Amount.of(100.0, 2),
                com.smile.aceeconomy.domain.TransactionType.TRANSFER_OUT,
                com.smile.aceeconomy.domain.Amount.of(500.0, 2),
                com.smile.aceeconomy.domain.Amount.of(400.0, 2),
                Instant.now(),
                "transfer");
        outcome.auditSink().record(tx);

        // At this point, the transport has a pending future, and the notifier
        // will wait for it to complete before shutdown finishes.
        Runnable hook = outcome.shutdownHook();
        assertNotNull(hook);

        // Transport should not be closed yet while the future is pending.
        assertFalse(transport.closeCalled(),
                "transport must not be closed before drain completes");

        // Now allow the pending future to complete.
        transport.completeAllPending();

        // Shutdown should drain first, then close.
        hook.run();
        assertTrue(transport.closeCalled(),
                "transport must be closed after notifier drain completes");
    }

    @Test
    void shutdownWithActualHttpDiscordTransportDrainsBeforeClosingClient() throws Exception {
        // Concrete proof: use actual HttpDiscordTransport with a tracking client and
        // pending HTTP response future. Verify that client close is not called until the notifier
        // drains and the hook completes. Uses a control future (status code) that naturally
        // transforms into properly typed CompletableFuture<HttpResponse<T>> via thenApply.
        CompletableFuture<Integer> pendingStatus = new CompletableFuture<>();
        CountDownLatch sendAsyncCalled = new CountDownLatch(1);
        TrackingHttpClientWithPendingResponse trackingClient =
                new TrackingHttpClientWithPendingResponse(pendingStatus, sendAsyncCalled);

        DiscordWiring.TransportFactory factory = url -> {
            HttpClient httpClient = trackingClient;
            return new com.smile.aceeconomy.infrastructure.integration.discord.HttpDiscordTransport(
                    httpClient, url, Duration.ofSeconds(10));
        };

        DiscordWiring.Outcome outcome = DiscordWiring.wire(
                repository, ioExecutor,
                true, "https://hooks.discord.example/services/foo",
                logger, resources, factory, msg -> { /* no-op */ });

        // Send a transaction to create a pending HTTP response future in the notifier.
        Transaction tx = sampleTransaction();
        outcome.auditSink().record(tx);

        // Wait for sendAsync to be invoked (notifier has queued the HTTP request).
        assertTrue(sendAsyncCalled.await(2, TimeUnit.SECONDS),
                "client.sendAsync() must be called to queue the HTTP request");

        // Verify client is not closed while HTTP response is pending.
        assertEquals(0, trackingClient.closeCallCount(),
                "client must not be closed before notifier drains and hook completes");

        // Execute the hook from another thread so we can observe ordering.
        CountDownLatch hookStarted = new CountDownLatch(1);
        CountDownLatch hookCompleted = new CountDownLatch(1);
        Thread hookThread = new Thread(() -> {
            hookStarted.countDown();
            outcome.shutdownHook().run();
            hookCompleted.countDown();
        }, "shutdown-hook-test");
        hookThread.start();

        try {
            // Wait for hook to start, then verify client is still not closed
            // (notifier is waiting for pending HTTP response).
            assertTrue(hookStarted.await(2, TimeUnit.SECONDS),
                    "hook thread must start");

            // At this point, hook is blocked on notifier.shutdown() waiting for the pending response.
            assertEquals(0, trackingClient.closeCallCount(),
                    "client must still not be closed while notifier waits for pending HTTP response");

            // Complete the pending status code so notifier can drain.
            pendingStatus.complete(204);

            // Wait for hook thread to complete (notifier drains, then close is called).
            assertTrue(hookCompleted.await(2, TimeUnit.SECONDS),
                    "hook thread must complete after HTTP response resolves");

            // Verify client was closed exactly once after notifier drained.
            assertEquals(1, trackingClient.closeCallCount(),
                    "client must be closed exactly once after notifier drains");
        } finally {
            // Ensure pending future is resolved even if assertions fail.
            if (!pendingStatus.isDone()) {
                pendingStatus.complete(204);
            }
            // Ensure hook thread does not hang. Wait with timeout then interrupt if still alive.
            try {
                hookThread.join(2_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (hookThread.isAlive()) {
                hookThread.interrupt();
                try {
                    hookThread.join(2_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
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

    // ---- fakes -------------------------------------------------------------

    private static final class AuditSinkSpy implements AuditSink {
        final List<Transaction> recorded = new ArrayList<>();

        @Override
        public void record(Transaction transaction) throws AuditException {
            recorded.add(transaction);
        }
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
                case FAIL -> CompletableFuture.completedFuture(
                        DiscordSendResult.failed("simulated failure"));
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

    private static final class CloseTrackingTransport implements DiscordTransport, AutoCloseable {
        private volatile boolean closed = false;

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            return CompletableFuture.completedFuture(DiscordSendResult.ok(204));
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }
    }

    private static final class ThrowingCloseTransport implements DiscordTransport, AutoCloseable {
        final AtomicInteger closeCalled = new AtomicInteger();

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            return CompletableFuture.completedFuture(DiscordSendResult.ok(204));
        }

        @Override
        public void close() throws Exception {
            closeCalled.incrementAndGet();
            throw new RuntimeException("simulated close failure");
        }
    }

    private static final class PendingFutureTransport implements DiscordTransport, AutoCloseable {
        private final CompletableFuture<DiscordSendResult> pending = new CompletableFuture<>();
        private volatile boolean closeCalled = false;

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            return pending;
        }

        void completeAllPending() {
            pending.complete(DiscordSendResult.ok(204));
        }

        boolean closeCalled() {
            return closeCalled;
        }

        @Override
        public void close() {
            closeCalled = true;
        }
    }

    private static final class MockHttpResponse<T> implements HttpResponse<T> {
        private final int statusCode;

        MockHttpResponse(int statusCode) {
            this.statusCode = statusCode;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return null;
        }

        @Override
        public T body() {
            return null;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return null;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }

    private static final class TrackingHttpClientWithPendingResponse extends HttpClient {
        private final CompletableFuture<Integer> pendingStatus;
        private final CountDownLatch sendAsyncCalled;
        private final AtomicInteger closeCount = new AtomicInteger();

        TrackingHttpClientWithPendingResponse(
                CompletableFuture<Integer> pendingStatus,
                CountDownLatch sendAsyncCalled) {
            this.pendingStatus = pendingStatus;
            this.sendAsyncCalled = sendAsyncCalled;
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            sendAsyncCalled.countDown();
            return pendingStatus.thenApply(status -> new MockHttpResponse(status));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        int closeCallCount() {
            return closeCount.get();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Redirect followRedirects() {
            return HttpClient.Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
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
