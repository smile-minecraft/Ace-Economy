package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.operations.security.SecurityControlService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * True registry coverage for {@code /aceeco freeze}, {@code unfreeze} and
 * {@code readonly}.
 *
 * <p>Surface (names, independent permissions, arg shapes, both senders allowed) is
 * locked here together with the true behaviour (dispatch actually freezes,
 * default reason, unknown account, idempotent repeat). Behaviour assertions fail
 * on the skeleton no-op handlers; surface assertions already hold.
 */
class FreezeCommandRegistryTest {

    private CommandServices servicesWith(SecurityControlService control,
                                         java.util.function.Function<String, Optional<PlayerIdentity>> resolve) {
        var players = mock(com.smile.aceeconomy.commands.v2.ports.PlayerLookupService.class);
        when(players.onlinePlayerNames()).thenReturn(List.of("Alice", "Bob"));
        when(players.resolve(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return CompletableFuture.completedFuture(resolve.apply(name));
        });
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
                null, control);
    }

    private Sender sender(boolean player, UUID uuid, String name,
                          boolean root, boolean freeze, boolean unfreeze, boolean readonly) {
        Sender sender = mock(Sender.class);
        doReturn(player).when(sender).isPlayer();
        doReturn(root).when(sender).hasPermission("aceeconomy.admin");
        doReturn(freeze).when(sender).hasPermission(SecurityCommandPolicy.FREEZE_PERMISSION);
        doReturn(unfreeze).when(sender).hasPermission(SecurityCommandPolicy.UNFREEZE_PERMISSION);
        doReturn(readonly).when(sender).hasPermission(SecurityCommandPolicy.READONLY_PERMISSION);
        if (player) {
            PlayerHandle handle = mock(PlayerHandle.class);
            doReturn(handle).when(sender).asPlayer();
            when(handle.getUniqueId()).thenReturn(uuid);
            when(handle.getName()).thenReturn(name);
            when(handle.isOnline()).thenReturn(true);
        }
        return sender;
    }

    private CommandRegistryImpl registryFor(CommandServices services, ReplySink sink) {
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);
        return registry;
    }

    @Test
    @DisplayName("freeze, unfreeze and readonly use three independent permissions")
    void independentPermissionsInSpec() {
        CommandServices services = servicesWith(new SecurityControlService(), name -> Optional.empty());
        CommandSpec aceeco = AceEcoCommandSpec.create(services);

        var freeze = aceeco.findSubCommand("freeze");
        var unfreeze = aceeco.findSubCommand("unfreeze");
        var readonly = aceeco.findSubCommand("readonly");

        assertNotNull(freeze, "/aceeco must expose freeze");
        assertNotNull(unfreeze, "/aceeco must expose unfreeze");
        assertNotNull(readonly, "/aceeco must expose readonly");
        assertEquals(SecurityCommandPolicy.FREEZE_PERMISSION, freeze.permission());
        assertEquals(SecurityCommandPolicy.UNFREEZE_PERMISSION, unfreeze.permission());
        assertEquals(SecurityCommandPolicy.READONLY_PERMISSION, readonly.permission());
        assertEquals("aceeconomy.admin.freeze", SecurityCommandPolicy.FREEZE_PERMISSION);
        assertEquals("aceeconomy.admin.unfreeze", SecurityCommandPolicy.UNFREEZE_PERMISSION);
        assertEquals("aceeconomy.admin.readonly", SecurityCommandPolicy.READONLY_PERMISSION);
    }

    @Test
    @DisplayName("freeze permission alone grants neither unfreeze nor readonly")
    void freezeAloneGrantsNothingElse() {
        java.util.function.Predicate<String> onlyFreeze =
                p -> p.equals(SecurityCommandPolicy.FREEZE_PERMISSION);
        assertTrue(SecurityCommandPolicy.hasFreezePermission(onlyFreeze),
                "freeze holder must pass the freeze check");
        assertFalse(SecurityCommandPolicy.hasUnfreezePermission(onlyFreeze),
                "freeze must never imply unfreeze");
        assertFalse(SecurityCommandPolicy.hasReadonlyPermission(onlyFreeze),
                "freeze must never imply readonly");
    }

    @Test
    @DisplayName("freeze and readonly allow both console and player with 1-2 args")
    void scopeSenderAndArgs() {
        CommandServices services = servicesWith(new SecurityControlService(), name -> Optional.empty());
        CommandSpec aceeco = AceEcoCommandSpec.create(services);

        for (String sub : List.of("freeze", "unfreeze", "readonly")) {
            var spec = aceeco.findSubCommand(sub);
            assertNotNull(spec, sub + " must exist");
            assertFalse(spec.consoleOnly(), sub + " must allow both console and player");
            assertEquals(1, spec.minArgs(), sub + " requires one leading argument");
            assertEquals(2, spec.maxArgs(), sub + " accepts one optional reason");
        }
    }

    @Test
    @DisplayName("dispatching freeze actually freezes the target account")
    void dispatchFreezeFreezes() {
        SecurityControlService control = new SecurityControlService();
        UUID alice = UUID.randomUUID();
        CommandServices services = servicesWith(control,
                name -> "alice".equalsIgnoreCase(name)
                        ? Optional.of(new PlayerIdentity(alice, "Alice", true)) : Optional.empty());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(sender(false, null, "CONSOLE", true, true, true, true),
                "aceeco", List.of("freeze", "alice"));

        assertTrue(control.isFrozen(alice), "dispatching freeze must freeze the resolved account");
    }

    @Test
    @DisplayName("dispatching unfreeze actually lifts the freeze")
    void dispatchUnfreezeLifts() {
        SecurityControlService control = new SecurityControlService();
        UUID alice = UUID.randomUUID();
        control.freeze(alice, AdminAudit.console("incident"));
        assertTrue(control.isFrozen(alice), "setup freeze must be effective before unfreeze");
        CommandServices services = servicesWith(control,
                name -> "alice".equalsIgnoreCase(name)
                        ? Optional.of(new PlayerIdentity(alice, "Alice", true)) : Optional.empty());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(sender(false, null, "CONSOLE", true, false, true, false),
                "aceeco", List.of("unfreeze", "alice"));

        assertFalse(control.isFrozen(alice), "dispatching unfreeze must lift the freeze");
    }

    @Test
    @DisplayName("dispatching readonly on actually blocks initiating mutations")
    void dispatchReadonlyBlocks() {
        SecurityControlService control = new SecurityControlService();
        CommandServices services = servicesWith(control, name -> Optional.empty());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(sender(false, null, "CONSOLE", true, false, false, true),
                "aceeco", List.of("readonly", "on"));

        assertTrue(control.isGlobalReadonly(), "dispatching readonly on must switch to read-only");
        assertTrue(control.checkInitiating(UUID.randomUUID()).isPresent(),
                "read-only must refuse initiating mutations");
    }

    @Test
    @DisplayName("unknown player is refused without touching control state")
    void unknownPlayerRefused() {
        SecurityControlService control = new SecurityControlService();
        CommandServices services = servicesWith(control, name -> Optional.empty());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(sender(false, null, "CONSOLE", true, true, true, true),
                "aceeco", List.of("freeze", "ghost"));

        try {
            verify(sink, org.mockito.Mockito.atLeastOnce())
                    .sendError(any(Sender.class), any(Throwable.class));
        } catch (AssertionError expected) {
            throw new AssertionError("unknown target must be refused with an explicit error", expected);
        }
        assertTrue(control.audits().isEmpty(), "unknown target must leave no control audit behind");
        assertEquals(0L, control.controlVersion(), "unknown target must leave the version unchanged");
    }

    @Test
    @DisplayName("repeating a freeze is idempotent with no version change")
    void repeatFreezeIdempotent() {
        SecurityControlService control = new SecurityControlService();
        UUID alice = UUID.randomUUID();
        CommandServices services = servicesWith(control,
                name -> "alice".equalsIgnoreCase(name)
                        ? Optional.of(new PlayerIdentity(alice, "Alice", true)) : Optional.empty());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        Sender console = sender(false, null, "CONSOLE", true, true, true, true);

        registry.dispatch(console, "aceeco", List.of("freeze", "alice"));
        long afterFirst = control.controlVersion();
        registry.dispatch(console, "aceeco", List.of("freeze", "alice"));

        assertTrue(control.isFrozen(alice), "account stays frozen after a repeat");
        assertEquals(afterFirst, control.controlVersion(),
                "repeating the current state must leave the version unchanged");
        assertEquals(1, control.audits().size(),
                "repeating a freeze must not publish a second audit");
    }

    @Test
    @DisplayName("omitted reason falls back to the shared default")
    void omittedReasonUsesDefault() {
        SecurityControlService control = new SecurityControlService();
        UUID alice = UUID.randomUUID();
        CommandServices services = servicesWith(control,
                name -> "alice".equalsIgnoreCase(name)
                        ? Optional.of(new PlayerIdentity(alice, "Alice", true)) : Optional.empty());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(sender(false, null, "CONSOLE", true, true, true, true),
                "aceeco", List.of("freeze", "alice"));

        assertEquals(1, control.audits().size(), "freeze must publish exactly one audit");
        assertEquals(AdminAudit.DEFAULT_REASON, control.audits().get(0).reason(),
                "omitted reason must fall back to the shared default");
    }
}
