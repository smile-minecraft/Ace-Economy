package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.RollbackCommandService;
import com.smile.aceeconomy.operations.RollbackError;
import com.smile.aceeconomy.operations.RollbackResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the {@code /aceeco rollback <transaction-id>} admin surface:
 * spec registration, root/child permission nodes, console-only sender policy, UUID
 * argument validation and the typed {@link RollbackResult} to reply mapping. Every
 * typed path is dispatched through the real registry so sender/permission/argument
 * handling is exercised together with the mapping.
 */
class RollbackCommandTest {

    private RollbackCommandService rollbackFacade;

    private CommandServices services() {
        rollbackFacade = mock(RollbackCommandService.class);
        return new CommandServices(mock(EconomyCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.PlayerLookupService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.HistoryQueryService.class),
                rollbackFacade,
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
    }

    private Sender consoleSender(boolean rootPermission, boolean childPermission) {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(rootPermission).when(sender).hasPermission("aceeconomy.admin");
        doReturn(childPermission).when(sender).hasPermission("aceeconomy.admin.rollback");
        return sender;
    }

    private Sender playerSender(boolean rootPermission, boolean childPermission) {
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        doReturn(rootPermission).when(sender).hasPermission("aceeconomy.admin");
        doReturn(childPermission).when(sender).hasPermission("aceeconomy.admin.rollback");
        doReturn(player).when(sender).asPlayer();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Alex");
        return sender;
    }

    private CommandRegistryImpl registryFor(CommandServices services, ReplySink sink) {
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);
        return registry;
    }

    private Throwable capturedError(ReplySink sink) {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(org.mockito.ArgumentMatchers.any(Sender.class), error.capture());
        return error.getValue();
    }

    private String capturedReply(ReplySink sink) {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sink).send(org.mockito.ArgumentMatchers.any(Sender.class), message.capture());
        return message.getValue();
    }

    @Test
    void aceecoSpecRegistersRollbackWithAdminChildPermissionAndArgumentBounds() {
        CommandServices services = services();

        CommandSpec aceeco = AceEcoCommandSpec.create(services);
        var rollback = aceeco.findSubCommand("rollback");

        assertNotNull(rollback, "/aceeco must expose a rollback subcommand");
        assertEquals("aceeconomy.admin.rollback", rollback.permission());
        assertEquals(1, rollback.minArgs(), "rollback requires exactly one transaction id");
        assertEquals(1, rollback.maxArgs(), "rollback accepts no extra arguments");
        assertTrue(rollback.consoleOnly(),
                "rollback is a destructive admin operation and must be console-only");
        assertFalse(rollback.playerOnly());

        CommandSpec registered = V2CommandRegistry.create(services).specs().stream()
                .filter(spec -> spec.name().equals("aceeco"))
                .findFirst().orElseThrow();
        assertNotNull(registered.findSubCommand("rollback"),
                "the registered aceeco spec must contain rollback");
    }

    @Test
    void playerDispatchIsRejectedAsConsoleOnlyBeforeAnyRollbackHappens() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        // Even with both permission nodes granted, a player sender must be rejected.
        registry.dispatch(playerSender(true, true), "aceeco",
                List.of("rollback", UUID.randomUUID().toString()));

        assertEquals("ACELIB-CMD-005", ((CommandException) capturedError(sink)).getCode(),
                "a player invoking the console-only rollback must hit PLAYER_NOT_ALLOWED");
        verify(sink, never()).send(
                org.mockito.ArgumentMatchers.any(Sender.class), org.mockito.ArgumentMatchers.anyString());
        verify(rollbackFacade, never()).rollback(org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void missingChildPermissionRejectsRollbackEvenWhenRootPermissionPresent() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(consoleSender(true, false), "aceeco",
                List.of("rollback", UUID.randomUUID().toString()));

        assertEquals("ACELIB-CMD-003", ((CommandException) capturedError(sink)).getCode(),
                "missing aceeconomy.admin.rollback must be rejected as no-permission");
        verify(rollbackFacade, never()).rollback(org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void missingRootPermissionRejectsRollbackEvenWhenChildPermissionPresent() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(consoleSender(false, true), "aceeco",
                List.of("rollback", UUID.randomUUID().toString()));

        assertEquals("ACELIB-CMD-003", ((CommandException) capturedError(sink)).getCode(),
                "missing root aceeconomy.admin must be rejected before the child check matters");
        verify(rollbackFacade, never()).rollback(org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void consoleDispatchParsesTheUuidInvokesTheFacadeAndReportsReversalAuditIds() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        UUID transactionId = UUID.randomUUID();
        UUID reversalId = UUID.randomUUID();
        when(rollbackFacade.rollback(transactionId)).thenReturn(
                CompletableFuture.completedFuture(RollbackResult.success(List.of(reversalId))));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("rollback", transactionId.toString()));

        String reply = capturedReply(sink);
        assertTrue(reply.contains(transactionId.toString()),
                "success reply must identify the rolled back transaction: " + reply);
        assertTrue(reply.contains(reversalId.toString()),
                "success reply must report the reversal audit record ids: " + reply);
        verify(sink, never()).sendError(
                org.mockito.ArgumentMatchers.any(Sender.class),
                org.mockito.ArgumentMatchers.any(Throwable.class));
    }

    @Test
    void alreadyRevertedIsAnExplicitNoOpAndNotANewReversal() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        UUID transactionId = UUID.randomUUID();
        when(rollbackFacade.rollback(transactionId)).thenReturn(
                CompletableFuture.completedFuture(RollbackResult.alreadyReverted()));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("rollback", transactionId.toString()));

        String reply = capturedReply(sink);
        assertTrue(reply.contains(transactionId.toString()), "no-op reply names the transaction: " + reply);
        assertTrue(reply.toLowerCase().contains("already reverted"),
                "no-op reply must state the transaction was already reverted: " + reply);
        assertFalse(reply.contains("Rolled back"),
                "a no-op must not read like a freshly executed rollback: " + reply);
    }

    @Test
    void unknownTransactionIsATypedErrorAndNeverASuccess() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        UUID transactionId = UUID.randomUUID();
        when(rollbackFacade.rollback(transactionId)).thenReturn(CompletableFuture.completedFuture(
                RollbackResult.failure(RollbackError.UNKNOWN_TRANSACTION,
                        "no transaction with id " + transactionId)));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("rollback", transactionId.toString()));

        Throwable error = capturedError(sink);
        assertEquals("ACELIB-CMD-ROLLBACK-UNKNOWN-TRANSACTION",
                ((CommandException) error).getCode());
        verify(sink, never()).send(
                org.mockito.ArgumentMatchers.any(Sender.class), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingTransferCounterpartHasItsOwnTypedError() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        UUID transactionId = UUID.randomUUID();
        when(rollbackFacade.rollback(transactionId)).thenReturn(CompletableFuture.completedFuture(
                RollbackResult.failure(RollbackError.COUNTERPART_NOT_FOUND, "counterpart leg not found")));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("rollback", transactionId.toString()));

        assertEquals("ACELIB-CMD-ROLLBACK-COUNTERPART-NOT-FOUND",
                ((CommandException) capturedError(sink)).getCode());
    }

    @Test
    void executionFailureIsATypedRetryableErrorWithoutFakeSuccess() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        UUID transactionId = UUID.randomUUID();
        when(rollbackFacade.rollback(transactionId)).thenReturn(CompletableFuture.completedFuture(
                RollbackResult.failure(RollbackError.EXECUTION_FAILED, "account not found for reversal")));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("rollback", transactionId.toString()));

        assertEquals("ACELIB-CMD-ROLLBACK-EXECUTION-FAILED",
                ((CommandException) capturedError(sink)).getCode());
        verify(sink, never()).send(
                org.mockito.ArgumentMatchers.any(Sender.class), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void markFailedStatesTheEffectMayExistAndNeedsManualHandling() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        UUID transactionId = UUID.randomUUID();
        when(rollbackFacade.rollback(transactionId)).thenReturn(CompletableFuture.completedFuture(
                RollbackResult.failure(RollbackError.MARK_FAILED,
                        "reversal applied but marker persist failed: disk full")));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("rollback", transactionId.toString()));

        Throwable error = capturedError(sink);
        assertEquals("ACELIB-CMD-ROLLBACK-MARK-FAILED", ((CommandException) error).getCode());
        assertTrue(error.getMessage().contains("manually"),
                "mark-failed guidance must demand manual reconciliation: " + error.getMessage());
        assertTrue(error.getMessage().contains("marker persist failed"),
                "the underlying cause must stay visible: " + error.getMessage());
        verify(sink, never()).send(
                org.mockito.ArgumentMatchers.any(Sender.class), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void invalidUuidIsRejectedBeforeTheFacadeIsCalled() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(consoleSender(true, true), "aceeco", List.of("rollback", "not-a-uuid"));

        assertEquals("ACELIB-CMD-INVALID-UUID", ((CommandException) capturedError(sink)).getCode(),
                "an unparseable transaction id must fail with the stable invalid-uuid code");
        verify(rollbackFacade, never()).rollback(org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void exceptionalFacadeCompletionIsReportedAsFailureWithoutFakeSuccess() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        UUID transactionId = UUID.randomUUID();
        when(rollbackFacade.rollback(transactionId)).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("storage boom")));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("rollback", transactionId.toString()));

        assertEquals("storage boom", capturedError(sink).getMessage(),
                "the facade failure must reach the typed error reply");
        verify(sink, never()).send(
                org.mockito.ArgumentMatchers.any(Sender.class), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void synchronousFacadeThrowReachesTheTypedErrorReplyWithoutSuccess() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        UUID transactionId = UUID.randomUUID();
        when(rollbackFacade.rollback(transactionId))
                .thenThrow(new IllegalStateException("facade sync boom"));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("rollback", transactionId.toString()));

        Throwable error = capturedError(sink);
        assertEquals("facade sync boom", error.getMessage(),
                "a synchronous facade failure must reach the typed error reply unwrapped, "
                        + "not as a generic dispatcher error");
        verify(sink, never()).send(
                org.mockito.ArgumentMatchers.any(Sender.class), org.mockito.ArgumentMatchers.anyString());
        verify(rollbackFacade, org.mockito.Mockito.times(1)).rollback(transactionId);
    }

    @Test
    void nullFutureFromFacadeIsRejectedWithATypedErrorWithoutSuccess() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        UUID transactionId = UUID.randomUUID();
        when(rollbackFacade.rollback(transactionId)).thenReturn(null);

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("rollback", transactionId.toString()));

        Throwable error = capturedError(sink);
        assertTrue(error instanceof CommandException,
                "a null future must be reported as a typed command error, got: " + error);
        assertEquals("ACELIB-CMD-EMPTY-FUTURE", ((CommandException) error).getCode(),
                "the null-future code must be distinct from the empty-result code");
        verify(sink, never()).send(
                org.mockito.ArgumentMatchers.any(Sender.class), org.mockito.ArgumentMatchers.anyString());
        verify(rollbackFacade, org.mockito.Mockito.times(1)).rollback(transactionId);
    }

    @Test
    void malformedFailureWithoutTypedErrorIsRejectedWithAStableFallbackCode() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        UUID transactionId = UUID.randomUUID();
        when(rollbackFacade.rollback(transactionId)).thenReturn(CompletableFuture.completedFuture(
                RollbackResult.failure(null, "malformed failure without a typed error")));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("rollback", transactionId.toString()));

        Throwable error = capturedError(sink);
        assertTrue(error instanceof CommandException,
                "a malformed failure must be reported as a typed command error, got: " + error);
        assertEquals("ACELIB-CMD-ROLLBACK-INVALID-RESULT", ((CommandException) error).getCode(),
                "a failure without a typed error must map to the stable invalid-result fallback");
        assertTrue(((CommandException) error).getMessage().contains("malformed failure"),
                "the underlying message must stay visible for diagnosis");
        verify(sink, never()).send(
                org.mockito.ArgumentMatchers.any(Sender.class), org.mockito.ArgumentMatchers.anyString());
        verify(rollbackFacade, org.mockito.Mockito.times(1)).rollback(transactionId);
    }
}
