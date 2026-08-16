package com.smile.aceeconomy.infrastructure.integration.discord;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DiscordNotifierTest {

    private final DiscordPayloadFilter filter = new DiscordPayloadFilter();
    private final TransactionDiscordMapper mapper = new TransactionDiscordMapper(filter, List.of("SECRET"));
    private final DiscordNotificationRequest request =
            new DiscordNotificationRequest("pay", "Alice", "Bob", "50.00", false);

    @Test
    void payloadIsFilteredBeforeSend() {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run);
        notifier.notify(request);
        assertEquals(1, transport.sent().size());
        String json = transport.sent().get(0).toJson();
        assertTrue(json.contains("\"Sender\""));
        assertTrue(json.contains("Alice"));
        assertTrue(json.contains("Bob"));
        assertTrue(json.contains("50.00"));
    }

    @Test
    void secretIsRedactedInPayload() {
        DiscordNotificationRequest secretReq =
                new DiscordNotificationRequest("pay", "SECRET", "Bob", "50.00", false);
        FakeDiscordTransport transport = new FakeDiscordTransport();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run);
        notifier.notify(secretReq);
        String json = transport.sent().get(0).toJson();
        assertFalse(json.contains("SECRET"));
        assertTrue(json.contains("***"));
    }

    @Test
    void deliveryFailureDoesNotThrowAndDoesNotVeto() {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        transport.setMode(FakeDiscordTransport.Mode.FAIL);
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run);
        assertDoesNotThrow(() -> notifier.notify(request));
        assertEquals(1, transport.sent().size());
    }

    @Test
    void hangDoesNotBlockNotify() {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        transport.setMode(FakeDiscordTransport.Mode.HANG);
        Executor executor = Executors.newSingleThreadExecutor();
        try {
            DiscordNotifier notifier = new DiscordNotifier(transport, mapper, executor);
            long start = System.nanoTime();
            notifier.notify(request);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs < 1000, "notify blocked for " + elapsedMs + "ms");
        } finally {
            ((ExecutorService) executor).shutdownNow();
        }
    }

    @Test
    void notifyIsFireAndForgetOnDirectExecutor() {
        FakeDiscordTransport transport = new FakeDiscordTransport();
        DiscordNotifier notifier = new DiscordNotifier(transport, mapper, Runnable::run);
        // Must return immediately and never await the (success) future.
        assertDoesNotThrow(() -> notifier.notify(request));
        assertEquals(1, transport.sent().size());
    }
}
