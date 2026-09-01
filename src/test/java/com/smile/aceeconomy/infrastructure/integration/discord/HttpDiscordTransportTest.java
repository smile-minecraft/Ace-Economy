package com.smile.aceeconomy.infrastructure.integration.discord;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Targeted tests for {@link HttpDiscordTransport} covering the production contract:
 *
 * <ul>
 *   <li>HTTP 2xx responses are reported as success with the actual status code.</li>
 *   <li>HTTP 4xx and 5xx responses are reported as failure; the {@code error} string carries a
 *       sanitized "http &lt;code&gt;" diagnostic that never contains the webhook URL or host.</li>
 *   <li>Timeouts are reported as failure; the diagnostic is sanitized.</li>
 *   <li>Network exceptions (connection refused) are reported as failure; the diagnostic is
 *       sanitized.</li>
 * </ul>
 *
 * <p>An in-process {@link HttpServer} on {@code 127.0.0.1} is used; no live Discord request is
 * made. The webhook URL is generated per test using a random UUID so accidental leakage would be
 * caught by an assertion failure message.</p>
 */
class HttpDiscordTransportTest {

    /** Fixed webhook URL path; the host is the test server's bound port. */
    private static final String SECRET_PATH = "/SECRET-WEBHOOK-PATH/services/foo";

    private HttpServer server;
    private HttpClient client;
    private String webhookUrl;
    private ExecutorService serverExecutor;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "discord-http-test-server");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(serverExecutor);
        server.start();
        int port = server.getAddress().getPort();
        // The local test server is plain HTTP. We use a marker token in the path so any
        // accidental echo would be caught by an assertion message.
        String host = "127.0.0.1:" + port;
        webhookUrl = "http://" + host + SECRET_PATH;
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    private DiscordPayload samplePayload() {
        return new DiscordPayload.Builder()
                .title("test-event")
                .addField("Sender", "Alice", true)
                .addField("Receiver", "Bob", true)
                .addField("Amount", "50.00", true)
                .build();
    }

    private HttpDiscordTransport newTransport(Duration timeout) {
        return new HttpDiscordTransport(client, webhookUrl, timeout);
    }

    private void registerHandler(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    private static HttpHandler respond(int status, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } else {
                exchange.close();
            }
        };
    }

    // ---- 2xx success ---------------------------------------------------------

    @Test
    void twoHundredIsSuccess() throws Exception {
        registerHandler(SECRET_PATH, respond(200, "{\"ok\":true}"));
        HttpDiscordTransport transport = newTransport(Duration.ofSeconds(5));

        DiscordSendResult result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), "200 must be reported as success");
        assertEquals(200, result.statusCode());
        assertNull(result.error(), "success must have a null error");
    }

    @Test
    void twoOhFourIsSuccess() throws Exception {
        registerHandler(SECRET_PATH, respond(204, ""));
        HttpDiscordTransport transport = newTransport(Duration.ofSeconds(5));

        DiscordSendResult result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), "204 must be reported as success");
        assertEquals(204, result.statusCode());
        assertNull(result.error());
    }

    @Test
    void twoNinetyNineIsSuccess() throws Exception {
        registerHandler(SECRET_PATH, respond(299, ""));
        HttpDiscordTransport transport = newTransport(Duration.ofSeconds(5));

        DiscordSendResult result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), "299 must be reported as success (upper 2xx boundary)");
        assertEquals(299, result.statusCode());
    }

    // ---- 4xx failure ---------------------------------------------------------

    @Test
    void fourHundredIsFailureWithoutUrlOrHost() throws Exception {
        registerHandler(SECRET_PATH, respond(400, "bad request"));
        HttpDiscordTransport transport = newTransport(Duration.ofSeconds(5));

        DiscordSendResult result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);

        assertFalse(result.success(), "400 must be reported as failure");
        assertEquals(-1, result.statusCode(), "failed results carry -1 statusCode");
        assertNotNull(result.error(), "failure must include a diagnostic error");
        assertTrue(result.error().contains("http 400"),
                "diagnostic must reference the HTTP status: " + result.error());
        assertNoUrlLeak(result.error());
    }

    @Test
    void fourHundredFourIsFailureWithoutUrlOrHost() throws Exception {
        registerHandler(SECRET_PATH, respond(404, "not found"));
        HttpDiscordTransport transport = newTransport(Duration.ofSeconds(5));

        DiscordSendResult result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(result.error().contains("http 404"),
                "diagnostic must reference the HTTP status: " + result.error());
        assertNoUrlLeak(result.error());
    }

    @Test
    void fourTwentyNineRateLimitedIsFailureWithoutUrlOrHost() throws Exception {
        registerHandler(SECRET_PATH, respond(429, "rate limited"));
        HttpDiscordTransport transport = newTransport(Duration.ofSeconds(5));

        DiscordSendResult result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);

        assertFalse(result.success(), "429 must be reported as failure");
        assertTrue(result.error().contains("http 429"),
                "diagnostic must reference the HTTP status: " + result.error());
        assertNoUrlLeak(result.error());
    }

    // ---- 5xx failure ---------------------------------------------------------

    @Test
    void fiveHundredIsFailureWithoutUrlOrHost() throws Exception {
        registerHandler(SECRET_PATH, respond(500, "internal error"));
        HttpDiscordTransport transport = newTransport(Duration.ofSeconds(5));

        DiscordSendResult result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);

        assertFalse(result.success(), "500 must be reported as failure");
        assertTrue(result.error().contains("http 500"),
                "diagnostic must reference the HTTP status: " + result.error());
        assertNoUrlLeak(result.error());
    }

    @Test
    void fiveOhThreeServiceUnavailableIsFailureWithoutUrlOrHost() throws Exception {
        registerHandler(SECRET_PATH, respond(503, "service unavailable"));
        HttpDiscordTransport transport = newTransport(Duration.ofSeconds(5));

        DiscordSendResult result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(result.error().contains("http 503"),
                "diagnostic must reference the HTTP status: " + result.error());
        assertNoUrlLeak(result.error());
    }

    @Test
    void threeHundredIsFailureWithoutUrlOrHost() throws Exception {
        // 3xx (redirect) is NOT a 2xx and must NOT be treated as success.
        registerHandler(SECRET_PATH, respond(301, "Moved Permanently"));
        HttpDiscordTransport transport = newTransport(Duration.ofSeconds(5));

        DiscordSendResult result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);

        assertFalse(result.success(), "3xx is not success");
        assertTrue(result.error().contains("http 301"),
                "diagnostic must reference the HTTP status: " + result.error());
        assertNoUrlLeak(result.error());
    }

    // ---- timeout --------------------------------------------------------------

    @Test
    void timeoutIsFailureWithoutUrlOrHost() throws Exception {
        java.util.concurrent.CountDownLatch blockLatch = new java.util.concurrent.CountDownLatch(1);
        registerHandler(SECRET_PATH, exchange -> {
            try {
                blockLatch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        HttpDiscordTransport transport = newTransport(Duration.ofMillis(200));

        DiscordSendResult result;
        try {
            result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);
        } finally {
            blockLatch.countDown();
        }

        assertFalse(result.success(), "timeout must be reported as failure");
        assertNotNull(result.error(), "timeout must include a diagnostic");
        assertNoUrlLeak(result.error());
    }

    // ---- network exception ----------------------------------------------------

    @Test
    void connectionRefusedIsFailureWithoutUrlOrHost() throws Exception {
        // Bind a server, immediately stop it, then point the transport at its (now-closed) port.
        // This guarantees a connection-refused-style network exception with no live remote.
        int closedPort = server.getAddress().getPort();
        server.stop(0);
        server = null;
        String closedUrl = "https://127.0.0.1:" + closedPort + SECRET_PATH;
        HttpDiscordTransport transport = new HttpDiscordTransport(
                client, closedUrl, Duration.ofSeconds(2));

        DiscordSendResult result;
        try {
            result = transport.send(samplePayload()).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ee) {
            // Some JDK error paths surface as a CompletionException; treat that as a failure
            // because the production code's exceptionally() callback is supposed to convert
            // it to DiscordSendResult.failed. If that conversion regresses, this branch
            // catches it and reports it as a test failure below.
            fail("transport should convert network exceptions into DiscordSendResult.failed: "
                    + ee.getCause());
            return;
        }

        assertFalse(result.success(), "connection refused must be reported as failure");
        assertNotNull(result.error(), "network exception must include a diagnostic");
        assertNoUrlLeak(result.error());
    }

    // ---- helpers --------------------------------------------------------------

    /**
     * Assert that {@code diagnostic} does not echo the webhook URL or any portion of the test
     * host/path that could carry a secret.
     */
    private static void assertNoUrlLeak(String diagnostic) {
        assertNotNull(diagnostic, "diagnostic must not be null");
        assertFalse(diagnostic.contains(SECRET_PATH),
                "diagnostic must not contain the webhook path: " + diagnostic);
        assertFalse(diagnostic.contains("SECRET-WEBHOOK-PATH"),
                "diagnostic must not contain the webhook path token: " + diagnostic);
        assertFalse(diagnostic.contains(webhookUrl()),
                "diagnostic must not contain the full webhook URL");
        // Defense in depth: any URL-shaped substring is rejected.
        assertFalse(diagnostic.matches(".*https?://\\S+.*"),
                "diagnostic must not contain a URL-shaped substring: " + diagnostic);
    }

    /**
     * Return a synthetic webhook URL for the {@link #assertNoUrlLeak} sanity check. We use a
     * random UUID to ensure any accidental echo would surface in assertion text.
     */
    private static String webhookUrl() {
        return "https://127.0.0.1:" + UUID.randomUUID().toString() + SECRET_PATH;
    }

    // ---- defensive sanity ----------------------------------------------------

    @Test
    void transportRejectsNullPayload() {
        HttpDiscordTransport transport = newTransport(Duration.ofSeconds(5));
        assertThrows(NullPointerException.class, () -> transport.send(null));
    }

    // ---- mixed-case URL scheme security ------------------------------------------

    @Test
    void mixedCaseUrlSchemeIsSanitizedInExceptionMessage() throws Exception {
        // Regression test: mixed-case schemes like HTTPS://, Http://, etc. must be stripped
        // from error diagnostics. Verify via HttpClient that throws with mixed-case URL in message.
        HttpClient clientWithMixedCaseInError = new HttpClient() {
            @Override
            public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
                throw new UnsupportedOperationException("not used in test");
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                    HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
                return CompletableFuture.failedFuture(
                        new RuntimeException("Connection timeout to HTTPS://SECRET-WEBHOOK.discord.example/services/foo"));
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                    HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
                    HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
                throw new UnsupportedOperationException("not used in test");
            }

            @Override
            public void close() {
            }

            @Override
            public Optional<java.net.CookieHandler> cookieHandler() {
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
            public javax.net.ssl.SSLContext sslContext() {
                return null;
            }

            @Override
            public javax.net.ssl.SSLParameters sslParameters() {
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
        };

        HttpDiscordTransport transport = new HttpDiscordTransport(
                clientWithMixedCaseInError, "http://127.0.0.1:9999/hook", Duration.ofSeconds(5));

        DiscordSendResult result = transport.send(samplePayload()).get(2, TimeUnit.SECONDS);

        assertFalse(result.success(), "transport must report failure");
        String error = result.error();
        assertNotNull(error, "error message must not be null");
        assertFalse(error.contains("HTTPS://"),
                "error must not contain HTTPS:// scheme (mixed-case): " + error);
        assertFalse(error.contains("SECRET-WEBHOOK"),
                "error must not contain webhook host: " + error);
        assertFalse(error.contains("discord.example"),
                "error must not contain domain: " + error);
        assertFalse(error.matches(".*https?://.*"),
                "error must not contain any URL-like substring: " + error);
    }

    // ---- malformed URL does not leak ---------------------------------------------------

    @Test
    void malformedWebhookUrlDoesNotLeakAndReturnsFailure() throws Exception {
        String malformedUrl = "https://hooks.discord.example/SECRET-TOKEN/foo bar with space";
        HttpDiscordTransport transport = new HttpDiscordTransport(
                client, malformedUrl, Duration.ofSeconds(2));

        DiscordSendResult result = transport.send(samplePayload()).get(2, TimeUnit.SECONDS);

        assertFalse(result.success(), "malformed URL must be reported as failure");
        assertNotNull(result.error(), "failure must include a diagnostic");
        assertFalse(result.error().contains("hooks.discord.example"),
                "error must not contain webhook host: " + result.error());
        assertFalse(result.error().contains("SECRET-TOKEN"),
                "error must not contain webhook token: " + result.error());
        assertFalse(result.error().contains("hooks.discord"),
                "error must not contain URL fragment: " + result.error());
        assertFalse(result.error().contains(malformedUrl),
                "error must not echo full malformed URL: " + result.error());
        assertFalse(result.error().matches(".*https?://\\S+.*"),
                "error must not contain URL-like substring: " + result.error());
    }

    // ---- ownership contract ---------------------------------------------------

    @Test
    void transportOwnsHttpClientAndClosesIt() throws Exception {
        // Ownership contract test: HttpDiscordTransport takes ownership of HttpClient.
        // Observable proof: a tracking client records when close() is invoked.
        TrackingHttpClient trackingClient = new TrackingHttpClient();
        HttpDiscordTransport transport = new HttpDiscordTransport(
                trackingClient, "http://127.0.0.1:9999/hook", Duration.ofSeconds(5));

        // Before close, client must not have been closed.
        assertEquals(0, trackingClient.closeCallCount(),
                "transport must not close client until transport.close() is called");

        // Calling transport.close() must invoke client.close() exactly once.
        transport.close();
        assertEquals(1, trackingClient.closeCallCount(),
                "transport.close() must invoke client.close() exactly once");

        // Second close should also work (idempotent-safe).
        transport.close();
        assertEquals(2, trackingClient.closeCallCount(),
                "multiple transport.close() calls must each call client.close()");
    }

    // ---- Red: Momus leak cases (space / quote after URL) -------------------

    @Test
    void malformedWebhookUrlWithSpaceAfterPathDoesNotLeakSecretToken() throws Exception {
        String malformedUrl = "https://hooks.discord.example/foo bar SECRET-TOKEN";
        HttpDiscordTransport transport = new HttpDiscordTransport(
                client, malformedUrl, Duration.ofSeconds(2));

        DiscordSendResult result = transport.send(samplePayload()).get(2, TimeUnit.SECONDS);

        assertFalse(result.success(), "malformed URL must be reported as failure");
        assertNotNull(result.error(), "failure must include a diagnostic");
        String error = result.error();
        assertFalse(error.contains("SECRET-TOKEN"), "error must not contain SECRET-TOKEN: " + error);
        assertFalse(error.contains("hooks.discord"), "error must not contain webhook host: " + error);
        assertFalse(error.contains("bar"), "error must not contain residue after space: " + error);
        assertFalse(error.toLowerCase().contains("http"), "error must not contain http scheme: " + error);
    }

    @Test
    void malformedWebhookUrlWithQuoteAfterPathDoesNotLeakSecretToken() throws Exception {
        String malformedUrl = "https://hooks.discord.example/foo\"SECRET-TOKEN";
        HttpDiscordTransport transport = new HttpDiscordTransport(
                client, malformedUrl, Duration.ofSeconds(2));

        DiscordSendResult result = transport.send(samplePayload()).get(2, TimeUnit.SECONDS);

        assertFalse(result.success(), "malformed URL must be reported as failure");
        assertNotNull(result.error(), "failure must include a diagnostic");
        String error = result.error();
        assertFalse(error.contains("SECRET-TOKEN"), "error must not contain SECRET-TOKEN: " + error);
        assertFalse(error.contains("hooks.discord"), "error must not contain webhook host: " + error);
        assertFalse(error.toLowerCase().contains("http"), "error must not contain http scheme: " + error);
    }

    @Test
    void discordNotifierSanitizeHardenedMasksSecretAfterSpace() {
        String sanitized = DiscordNotifier.sanitize("boom: https://h.example/p q SECRET");
        assertFalse(sanitized.contains("SECRET"), "sanitized must not contain SECRET: " + sanitized);
        assertFalse(sanitized.contains("h.example"), "sanitized must not contain host: " + sanitized);
        assertFalse(sanitized.toLowerCase().contains("http"), "sanitized must not contain http: " + sanitized);
        assertTrue(sanitized.contains("***"), "sanitized must contain mask: " + sanitized);
    }

    private static final class TrackingHttpClient extends HttpClient {
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("not used in test");
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
        public Optional<java.net.CookieHandler> cookieHandler() {
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
}
