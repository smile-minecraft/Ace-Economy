package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.infrastructure.integration.discord.DiscordAuditSink;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordNotifier;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordPayloadFilter;
import com.smile.aceeconomy.infrastructure.integration.discord.DiscordTransport;
import com.smile.aceeconomy.infrastructure.integration.discord.HttpDiscordTransport;
import com.smile.aceeconomy.infrastructure.integration.discord.TransactionDiscordMapper;
import com.smile.aceeconomy.infrastructure.persistence.PersistentAuditSink;
import com.smile.aceeconomy.ports.AuditSink;
import com.smile.aceeconomy.ports.persistence.TransactionRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Package-private seam that wires the Discord notification stack into the application audit sink.
 *
 * <p>The wiring is split out of {@link CompositionRoot} so it can be exercised by real, fast unit
 * tests without booting Bukkit or any external server. Behavior:</p>
 * <ul>
 *   <li><b>enabled=false</b> — no transport is constructed; no shutdown hook is registered; the
 *       audit sink stays as the plain {@link PersistentAuditSink}.</li>
 *   <li><b>enabled=true, empty URL</b> — same as disabled; treated as a no-op wiring.</li>
 *   <li><b>enabled=true, invalid URL</b> — URL validation rejects it; a fixed diagnostic is logged
 *       (never echoing the URL); the audit sink stays as {@link PersistentAuditSink}; startup
 *       does not fail.</li>
 *   <li><b>enabled=true, valid URL</b> — an {@link HttpDiscordTransport} is built, a
 *       {@link DiscordNotifier} is wrapped around it, and the original audit sink is decorated
 *       with a {@link DiscordAuditSink}. A shutdown hook is registered with the supplied
 *       {@link ResourceOwner} so plugin teardown drains the notifier.</li>
 * </ul>
 *
 * <p>The transport construction is injected as a {@link TransportFactory} so tests can verify
 * wiring without any real network I/O.</p>
 */
final class DiscordWiring {

    /** Fixed diagnostic used when a webhook URL fails validation. Never echoes the URL. */
    static final String DIAGNOSTIC_REJECTED =
            "Discord webhook rejected: invalid url (see discord.webhook-url)";

    /** Reason codes returned in {@link Outcome#rejectReason()} for observability. */
    static final String REASON_DISABLED = "disabled";
    static final String REASON_MISSING_URL = "missing-url";
    static final String REASON_INVALID_URL = "invalid-url";
    static final String REASON_OK = "ok";

    @FunctionalInterface
    interface TransportFactory {
        DiscordTransport create(String validatedUrl);
    }

    /** Wire outcome: the audit sink the application should use plus optional shutdown hook. */
    record Outcome(
            AuditSink auditSink,
            Runnable shutdownHook,
            boolean active,
            String rejectReason) {

        Outcome {
            Objects.requireNonNull(auditSink, "auditSink");
            Objects.requireNonNull(rejectReason, "rejectReason");
        }
    }

    private DiscordWiring() {
    }

    /** Production overload using the default {@link HttpClient}-backed factory. */
    static Outcome wire(
            TransactionRepository transactions,
            ExecutorService ioExecutor,
            boolean enabled,
            String webhookUrl,
            Logger logger,
            ResourceOwner resources) {
        return wire(transactions, ioExecutor, enabled, webhookUrl, logger, resources,
                DiscordWiring::defaultTransport);
    }

    // [TEST:P3] 注入自訂 transport 工廠與 diagnostics 消費者的長期測試入口，生產程式碼不會呼叫。
    static Outcome wire(
            TransactionRepository transactions,
            ExecutorService ioExecutor,
            boolean enabled,
            String webhookUrl,
            Logger logger,
            ResourceOwner resources,
            TransportFactory transportFactory) {
        return wire(transactions, ioExecutor, enabled, webhookUrl, logger, resources,
                transportFactory, null);
    }

    // [TEST:P3] 注入 transport 工廠與 diagnostics 消費者的長期測試入口，生產程式碼不會呼叫。
    static Outcome wire(
            TransactionRepository transactions,
            ExecutorService ioExecutor,
            boolean enabled,
            String webhookUrl,
            Logger logger,
            ResourceOwner resources,
            TransportFactory transportFactory,
            Consumer<String> diagnosticsSink) {
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(ioExecutor, "ioExecutor");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(transportFactory, "transportFactory");

        AuditSink base = new PersistentAuditSink(transactions);
        Consumer<String> sink = diagnosticsSink != null ? diagnosticsSink : logger::warning;

        if (!enabled) {
            return new Outcome(base, null, false, REASON_DISABLED);
        }
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return new Outcome(base, null, false, REASON_MISSING_URL);
        }
        URI uri;
        try {
            uri = DiscordWebhookUrl.validate(webhookUrl);
        } catch (IllegalArgumentException e) {
            // Fixed diagnostic; the URL is never echoed into the message.
            // Diagnostics delivery is best-effort: a throwing consumer or logger handler must
            // not abort CompositionRoot startup — the wiring still degrades to inactive/no-op.
            safeAcceptDiagnostic(sink, DIAGNOSTIC_REJECTED);
            return new Outcome(base, null, false, REASON_INVALID_URL);
        }
        DiscordTransport transport = transportFactory.create(uri.toString());
        DiscordPayloadFilter filter = new DiscordPayloadFilter();
        TransactionDiscordMapper mapper = new TransactionDiscordMapper(filter, List.of());
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, ioExecutor, sink);
        AuditSink decorated = new DiscordAuditSink(base, notifier);
        Runnable shutdownHook = () -> shutdownTransportAndNotifier(notifier, transport);
        resources.register(shutdownHook);
        return new Outcome(decorated, shutdownHook, true, REASON_OK);
    }

    /**
     * Shutdown hook that drains the notifier first, then closes the transport if it is
     * {@link AutoCloseable}. This ensures all pending requests are flushed before the
     * underlying HTTP client is closed.
     *
     * <p>If transport close throws, the exception is swallowed so that the teardown
     * does not fail due to a close error — {@link ResourceOwner} will aggregate and
     * report all exceptions after every cleanup is attempted.</p>
     */
    private static void shutdownTransportAndNotifier(
            DiscordNotifier notifier, DiscordTransport transport) {
        try {
            notifier.shutdown();
        } finally {
            if (transport instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Throwable ignored) {
                    // Swallow close failures so teardown never fails due to HTTP client close error.
                }
            }
        }
    }

    /**
     * Run the diagnostics consumer inside a defensive try/catch. A throwing diagnostics
     * consumer must not affect the wire outcome, the in-flight counter, or the active flag.
     */
    private static void safeAcceptDiagnostic(Consumer<String> sink, String message) {
        try {
            sink.accept(message);
        } catch (Throwable ignored) {
            // best-effort: diagnostics failures never leak into wire() or startup.
        }
    }

    /**
     * Default {@link TransportFactory} used by the production wiring path: builds an
     * {@link HttpDiscordTransport} backed by a fresh {@link HttpClient} with a 10s connect
     * timeout. Exposed package-private so {@link CompositionRoot#wireDiscord} can pass it
     * through to {@link #wire} without exposing the factory type publicly.
     */
    static TransportFactory defaultTransportFactory() {
        return DiscordWiring::defaultTransport;
    }

    private static DiscordTransport defaultTransport(String validatedUrl) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return new HttpDiscordTransport(client, validatedUrl, Duration.ofSeconds(10));
    }
}
