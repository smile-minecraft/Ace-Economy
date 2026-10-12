package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;
import com.smile.aceeconomy.domain.SecurityControlAudit;
import com.smile.aceeconomy.domain.SecurityControlState;
import com.smile.aceeconomy.operations.security.SecurityControlService;
import com.smile.aceeconomy.operations.security.SecurityControlStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Control mutations leave the region thread: the slow file publish and its
 * wait for older permits run only on the injected IO executor, the handler
 * returns immediately, and the final reply fires once after completion
 * through the region dispatch seam. A missing or rejecting executor is a
 * typed refusal with no state change and no double reply.
 */
class ControlCommandAsyncTest {

    private ExecutorService io;

    @AfterEach
    void tearDown() {
        CommandReply.installRegionDispatch(null);
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
    }

    private static final class SlowStore implements SecurityControlStore {
        final CountDownLatch enteredSave = new CountDownLatch(1);
        final CountDownLatch releaseSave = new CountDownLatch(1);
        final AtomicReference<Thread> saveThread = new AtomicReference<>();

        @Override
        public SecurityControlState load() {
            return SecurityControlState.open();
        }

        @Override
        public void save(SecurityControlState state, SecurityControlAudit audit) {
            saveThread.set(Thread.currentThread());
            enteredSave.countDown();
            try {
                assertTrue(releaseSave.await(10, TimeUnit.SECONDS), "test must release the slow save");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean isLoadFailed() {
            return false;
        }

        @Override
        public String loadDiagnosis() {
            return "";
        }

        @Override
        public java.util.List<SecurityControlAudit> audits() {
            return List.of();
        }
    }

    private CommandServices servicesWith(SecurityControlService control, UUID alice,
                                         java.util.concurrent.Executor controlExecutor,
                                         ReplySink sink) {
        var players = mock(com.smile.aceeconomy.commands.v2.ports.PlayerLookupService.class);
        when(players.onlinePlayerNames()).thenReturn(List.of("Alice"));
        when(players.resolve(anyString())).thenAnswer(inv ->
                CompletableFuture.completedFuture(
                        Optional.of(new PlayerIdentity(alice, "Alice", true))));
        var economy = mock(com.smile.aceeconomy.commands.v2.ports.EconomyCommandService.class);
        when(economy.knownCurrencyIds()).thenReturn(List.of("dollar"));
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        return new CommandServices(economy, players,
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.ImportCommandService.class),
                null, control, controlExecutor);
    }

    private Sender console() {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.admin");
        doReturn(true).when(sender).hasPermission(SecurityCommandPolicy.FREEZE_PERMISSION);
        doReturn(true).when(sender).hasPermission(SecurityCommandPolicy.UNFREEZE_PERMISSION);
        doReturn(true).when(sender).hasPermission(SecurityCommandPolicy.READONLY_PERMISSION);
        return sender;
    }

    @Test
    @DisplayName("freeze with a slow store returns immediately and publishes on the IO thread")
    void freezeSlowStoreRunsOnIo() throws Exception {
        SlowStore slow = new SlowStore();
        SecurityControlService control = new SecurityControlService(slow);
        UUID alice = UUID.randomUUID();
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-io-freeze");
            t.setDaemon(true);
            return t;
        });
        // Final replies for player senders travel the region dispatch seam;
        // console replies answer synchronously. Use console here and assert
        // the mutation thread only.
        CommandServices services = servicesWith(control, alice, io, mock(ReplySink.class));
        CommandRegistryImpl registry = new CommandRegistryImpl(mock(ReplySink.class));
        V2CommandRegistry.create(services).register(registry);

        CountDownLatch dispatchReturned = new CountDownLatch(1);
        Thread region = new Thread(() -> {
            try {
                registry.dispatch(console(), "aceeco", List.of("freeze", "alice"));
            } finally {
                dispatchReturned.countDown();
            }
        }, "test-region-freeze");
        region.start();

        assertTrue(slow.enteredSave.await(10, TimeUnit.SECONDS), "slow save must be entered on IO");
        assertTrue(dispatchReturned.await(5, TimeUnit.SECONDS),
                "handler must return while the file publish is still slow");
        assertTrue(slow.saveThread.get().getName().startsWith("test-io-"),
                "file publish must run on the IO executor, not the region: " + slow.saveThread.get());

        slow.releaseSave.countDown();
        region.join(10_000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!control.isFrozen(alice) && System.nanoTime() < deadline) {
            // Await the async apply without sleeping: park briefly on the
            // future instead of the clock.
            CompletableFuture.runAsync(() -> { }, io).get(100, TimeUnit.MILLISECONDS);
        }
        assertTrue(control.isFrozen(alice), "freeze must eventually apply on IO");
    }

    @Test
    @DisplayName("readonly with a slow store returns immediately and switches on IO")
    void readonlySlowStoreRunsOnIo() throws Exception {
        SlowStore slow = new SlowStore();
        SecurityControlService control = new SecurityControlService(slow);
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-io-readonly");
            t.setDaemon(true);
            return t;
        });
        var players = mock(com.smile.aceeconomy.commands.v2.ports.PlayerLookupService.class);
        when(players.onlinePlayerNames()).thenReturn(List.of());
        var economy = mock(com.smile.aceeconomy.commands.v2.ports.EconomyCommandService.class);
        when(economy.knownCurrencyIds()).thenReturn(List.of("dollar"));
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        CommandServices services = new CommandServices(economy, players,
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.ImportCommandService.class),
                null, control, io);
        CommandRegistryImpl registry = new CommandRegistryImpl(mock(ReplySink.class));
        V2CommandRegistry.create(services).register(registry);

        CountDownLatch dispatchReturned = new CountDownLatch(1);
        Thread region = new Thread(() -> {
            try {
                registry.dispatch(console(), "aceeco", List.of("readonly", "on"));
            } finally {
                dispatchReturned.countDown();
            }
        }, "test-region-readonly");
        region.start();

        assertTrue(slow.enteredSave.await(10, TimeUnit.SECONDS), "slow save must be entered on IO");
        assertTrue(dispatchReturned.await(5, TimeUnit.SECONDS),
                "readonly handler must return while the publish is still slow");
        assertTrue(slow.saveThread.get().getName().startsWith("test-io-"),
                "readonly publish must run on IO, not the region");
        slow.releaseSave.countDown();
        region.join(10_000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!control.isGlobalReadonly() && System.nanoTime() < deadline) {
            CompletableFuture.runAsync(() -> { }, io).get(100, TimeUnit.MILLISECONDS);
        }
        assertTrue(control.isGlobalReadonly(), "read-only must eventually apply on IO");
    }

    @Test
    @DisplayName("rejecting executor refuses once with no state change")
    void rejectingExecutorRefusesOnce() throws Exception {
        SecurityControlService control = new SecurityControlService();
        UUID alice = UUID.randomUUID();
        java.util.concurrent.Executor rejecting = command -> {
            throw new RejectedExecutionException("test rejection");
        };
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger replies = new AtomicInteger();
        ReplySink sink = mock(ReplySink.class);
        org.mockito.Mockito.doAnswer(inv -> {
            errors.incrementAndGet();
            replies.incrementAndGet();
            return null;
        }).when(sink).sendError(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(inv -> {
            replies.incrementAndGet();
            return null;
        }).when(sink).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        CommandServices services = servicesWith(control, alice, rejecting, sink);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(console(), "aceeco", List.of("freeze", "alice"));
        // The rejection travels the typed error path; the dependent stage
        // never runs, so at most one error reply lands. Await the reply
        // through the mock instead of sleeping.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (errors.get() == 0 && System.nanoTime() < deadline) {
            CompletableFuture.runAsync(() -> { }).get(50, TimeUnit.MILLISECONDS);
        }
        assertEquals(1, errors.get(), "a rejected mutation must refuse exactly once");
        assertEquals(1, replies.get(), "no double reply on rejection");
        assertFalse(control.isFrozen(alice), "a rejected mutation must change no state");
        assertEquals(0L, control.controlVersion());
    }

    @Test
    @DisplayName("missing executor refuses with a typed error and no state change")
    void missingExecutorRefusesTyped() {
        SecurityControlService control = new SecurityControlService();
        UUID alice = UUID.randomUUID();
        AtomicInteger errors = new AtomicInteger();
        ReplySink sink = mock(ReplySink.class);
        org.mockito.Mockito.doAnswer(inv -> {
            errors.incrementAndGet();
            return null;
        }).when(sink).sendError(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        CommandServices services = servicesWith(control, alice, null, sink);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(console(), "aceeco", List.of("freeze", "alice"));

        assertEquals(1, errors.get(), "missing executor must be a typed refusal, never silent");
        assertFalse(control.isFrozen(alice));
    }
}
