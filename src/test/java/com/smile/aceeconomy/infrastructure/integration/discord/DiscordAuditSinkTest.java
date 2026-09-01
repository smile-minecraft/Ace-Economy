package com.smile.aceeconomy.infrastructure.integration.discord;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.ports.inmemory.RecordingAuditSink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DiscordAuditSinkTest {

    private final DiscordPayloadFilter filter = new DiscordPayloadFilter();
    private final TransactionDiscordMapper mapper = new TransactionDiscordMapper(filter, java.util.List.of());

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

    @Test
    void enabledValidUrlSendsNotificationAfterAuditRecord() throws Exception {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        RecordingAuditSink inner = new RecordingAuditSink();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, r -> r.run());
        DiscordAuditSink sink = new DiscordAuditSink(inner, notifier);

        Transaction tx = sampleTransaction();
        sink.record(tx);

        assertEquals(1, inner.recorded().size());
        assertEquals(1, transport.sent().size());
        String json = transport.sent().get(0).toJson();
        assertTrue(json.contains("TRANSFER_OUT"));
        assertTrue(json.contains("11111111"));
        assertTrue(json.contains("22222222"));
        assertTrue(json.contains("100"));
    }

    @Test
    void transportFailureDoesNotThrowAndDoesNotVetoAudit() throws Exception {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        transport.setMode(FakeDiscordTransport.Mode.FAIL);
        RecordingAuditSink inner = new RecordingAuditSink();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, r -> r.run());
        DiscordAuditSink sink = new DiscordAuditSink(inner, notifier);

        Transaction tx = sampleTransaction();
        assertDoesNotThrow(() -> sink.record(tx));
        assertEquals(1, inner.recorded().size());
        // Transport still records the attempt even on failure
        assertEquals(1, transport.sent().size());
    }

    @Test
    void shutdownPreventsLateCallbackFromSending() throws Exception {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        RecordingAuditSink inner = new RecordingAuditSink();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, r -> r.run());
        DiscordAuditSink sink = new DiscordAuditSink(inner, notifier);

        Transaction tx = sampleTransaction();
        sink.record(tx);
        assertEquals(1, transport.sent().size());

        notifier.shutdown();
        assertFalse(notifier.isActive());

        // After shutdown, a new record should not trigger any new transport call
        sink.record(tx);
        assertEquals(1, transport.sent().size());
    }

    @Test
    void auditExceptionFromInnerIsNotSwallowedByNotification() throws Exception {
        RecordingAuditSink inner = new RecordingAuditSink();
        inner.setFailOnNextRecord(true);
        FakeDiscordTransport transport = new FakeDiscordTransport();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, r -> r.run());
        DiscordAuditSink sink = new DiscordAuditSink(inner, notifier);

        Transaction tx = sampleTransaction();
        assertThrows(com.smile.aceeconomy.ports.AuditException.class, () -> sink.record(tx));
        // Even when inner throws, notification should not have been attempted
        // (because inner.record throws before notification is triggered)
        assertEquals(0, transport.sent().size());
    }

    @Test
    void webhookUrlNeverAppearsInPayloadOrTransportOutput() throws Exception {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        RecordingAuditSink inner = new RecordingAuditSink();
        // Pass webhook URL as a secret to the filter to verify redaction defense-in-depth
        TransactionDiscordMapper secretMapper = new TransactionDiscordMapper(
                filter, java.util.List.of("https://hooks.discord.com/test-secret"));
        DiscordNotifier notifier = new DiscordNotifier(transport, secretMapper, r -> r.run());
        DiscordAuditSink sink = new DiscordAuditSink(inner, notifier);

        Transaction tx = sampleTransaction();
        sink.record(tx);

        assertEquals(1, transport.sent().size());
        String json = transport.sent().get(0).toJson();
        assertFalse(json.contains("hooks.discord.com"));
        assertFalse(json.contains("test-secret"));
    }
}
