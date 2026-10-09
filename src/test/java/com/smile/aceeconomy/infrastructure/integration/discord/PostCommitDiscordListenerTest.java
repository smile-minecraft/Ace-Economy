package com.smile.aceeconomy.infrastructure.integration.discord;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link PostCommitDiscordListener}: one committed transaction announces
 * exactly once with sanitized content, and mapping or delivery failures never escape.
 */
class PostCommitDiscordListenerTest {

    private static Transaction transferOut() {
        return new Transaction(
                UUID.randomUUID(),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "dollar",
                Amount.of(100, 2),
                TransactionType.TRANSFER_OUT,
                Amount.of(1000, 2),
                Amount.of(900, 2),
                Instant.EPOCH,
                "transfer-out");
    }

    private static DiscordNotifier notifier(DiscordTransport transport) {
        return new DiscordNotifier(transport,
                new TransactionDiscordMapper(new DiscordPayloadFilter(), List.of()),
                (Executor) Runnable::run);
    }

    @Test
    void committedTransactionNotifiesExactlyOnce() {
        CapturingTransport transport = new CapturingTransport();
        PostCommitDiscordListener listener = new PostCommitDiscordListener(notifier(transport));

        listener.onCommitted(transferOut());

        assertEquals(1, transport.sent.size(), "one committed transaction notifies exactly once");
        assertTrue(transport.sent.get(0).toJson().contains("TRANSFER_OUT"));
    }

    @Test
    void nullTransactionIsIgnored() {
        CapturingTransport transport = new CapturingTransport();
        PostCommitDiscordListener listener = new PostCommitDiscordListener(notifier(transport));

        assertDoesNotThrow(() -> listener.onCommitted(null));
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void transportFailureNeverEscapes() {
        DiscordTransport failing = payload ->
                CompletableFuture.failedFuture(new RuntimeException("simulated delivery failure"));
        PostCommitDiscordListener listener = new PostCommitDiscordListener(notifier(failing));

        assertDoesNotThrow(() -> listener.onCommitted(transferOut()),
                "delivery failure must not escape the post-commit observer");
    }

    @Test
    void synchronouslyThrowingTransportNeverEscapes() {
        DiscordTransport throwing = payload -> {
            throw new RuntimeException("simulated synchronous failure");
        };
        PostCommitDiscordListener listener = new PostCommitDiscordListener(notifier(throwing));

        assertDoesNotThrow(() -> listener.onCommitted(transferOut()),
                "synchronous transport failure must not escape the post-commit observer");
    }

    private static final class CapturingTransport implements DiscordTransport {
        final List<DiscordPayload> sent = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
            sent.add(payload);
            return CompletableFuture.completedFuture(DiscordSendResult.ok(204));
        }
    }
}
