package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract for forwarding plugin.yml-declared command aliases into the AceLib registry.
 *
 * <p>Bukkit routes every alias declared in plugin.yml to the root command's own
 * {@code PluginCommand}, but hands the typed alias to the executor as the dispatch label.
 * The v2 registry must therefore resolve every statically routed label (root and alias) to
 * the canonical spec, otherwise Folia answers {@code ACELIB-CMD-002 unknown command} for
 * labels like {@code /bal} even though the canonical root works.</p>
 */
class BukkitAliasForwardingTest {

    /** Command declarations shaped exactly like the shipped plugin.yml. */
    private static final Map<String, Map<String, Object>> SHIPPED_DECLARED_COMMANDS = Map.of(
            "money", Map.of("aliases", List.of("balance", "bal")),
            "pay", Map.of(),
            "aceeco", Map.of(),
            "withdraw", Map.of(),
            "baltop", Map.of("aliases", List.of("balancetop", "top")),
            "bank", Map.of("aliases", List.of("menu", "bankmenu")));

    private static V2CommandRegistry shippedRegistry(boolean leaderboardEnabled) {
        return V2CommandRegistry.create(mockedServices(), "aceeco", leaderboardEnabled,
                MainCommandAliasPolicy.declaredBukkitLabels(SHIPPED_DECLARED_COMMANDS),
                MainCommandAliasPolicy.declaredAliasesByRoot(SHIPPED_DECLARED_COMMANDS));
    }

    private static CommandServices mockedServices() {
        return new CommandServices(
                mock(com.smile.aceeconomy.commands.v2.ports.EconomyCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.PlayerLookupService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
    }

    @Test
    @DisplayName("every shipped plugin.yml alias resolves to its canonical spec; roots stay available")
    void forwardsShippedPluginAliasesToCanonicalSpecs() {
        CommandRegistryImpl registry = new CommandRegistryImpl(mock(ReplySink.class));
        shippedRegistry(true).register(registry);

        assertResolvesTo(registry, "balance", "money");
        assertResolvesTo(registry, "bal", "money");
        assertResolvesTo(registry, "balancetop", "baltop");
        assertResolvesTo(registry, "top", "baltop");
        assertResolvesTo(registry, "menu", "bank");
        assertResolvesTo(registry, "bankmenu", "bank");

        assertResolvesTo(registry, "money", "money");
        assertResolvesTo(registry, "baltop", "baltop");
        assertResolvesTo(registry, "bank", "bank");
    }

    @Test
    @DisplayName("dispatching through an alias reaches canonical help instead of ACELIB-CMD-002")
    void aliasDispatchReachesCanonicalHelpInsteadOfUnknownCommand() {
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        shippedRegistry(true).register(registry);

        Sender sender = mock(Sender.class);
        when(sender.hasPermission("aceeconomy.command.money")).thenReturn(true);
        registry.dispatch(sender, "bal", List.of());

        verify(sink).send(same(sender), org.mockito.ArgumentMatchers.contains("=== money ==="));
        verify(sink, never()).sendError(any(), any());
    }

    @Test
    @DisplayName("duplicate, self-referential and empty declared aliases collapse without breaking registration")
    void toleratesDuplicateSelfAndEmptyDeclaredAliases() {
        Map<String, Map<String, Object>> declared = Map.of(
                "money", Map.of("aliases", List.of("bal", "BAL", "bal", "money")),
                "bank", Map.of("aliases", List.of()));

        V2CommandRegistry commands = V2CommandRegistry.create(mockedServices(), "aceeco", true,
                MainCommandAliasPolicy.declaredBukkitLabels(declared),
                MainCommandAliasPolicy.declaredAliasesByRoot(declared));
        CommandRegistryImpl registry = new CommandRegistryImpl(mock(ReplySink.class));
        commands.register(registry);

        CommandSpec money = commands.specs().stream()
                .filter(spec -> spec.name().equals("money")).findFirst().orElseThrow();
        assertEquals(List.of("balance", "bal"), money.aliases(),
                "duplicates and the spec's own name must collapse into one entry");
        assertResolvesTo(registry, "bal", "money");
        assertResolvesTo(registry, "money", "money");
    }

    @Test
    @DisplayName("an alias claimed by two roots still fails fast at registration")
    void crossRootAliasCollisionStillFailsFastAtRegistration() {
        Map<String, Map<String, Object>> declared = Map.of(
                "money", Map.of("aliases", List.of("clash")),
                "bank", Map.of("aliases", List.of("clash")));

        V2CommandRegistry commands = V2CommandRegistry.create(mockedServices(), "aceeco", true,
                MainCommandAliasPolicy.declaredBukkitLabels(declared),
                MainCommandAliasPolicy.declaredAliasesByRoot(declared));
        CommandRegistryImpl registry = new CommandRegistryImpl(mock(ReplySink.class));

        assertThrows(IllegalArgumentException.class, () -> commands.register(registry),
                "two roots claiming the same alias must fail fast instead of double-binding");
    }

    @Test
    @DisplayName("disabled leaderboard leaves baltop aliases unrouted while other aliases forward")
    void disabledLeaderboardLeavesBaltopAliasesUnroutedButOthersForward() {
        CommandRegistryImpl registry = new CommandRegistryImpl(mock(ReplySink.class));
        shippedRegistry(false).register(registry);

        assertNull(registry.findCommand("baltop"));
        assertNull(registry.findCommand("balancetop"));
        assertNull(registry.findCommand("top"));

        assertResolvesTo(registry, "bal", "money");
        assertResolvesTo(registry, "menu", "bank");
    }

    @Test
    @DisplayName("alias labels share the canonical lifecycle: disabled registry reports REGISTRY_DISABLED")
    void aliasLabelsShareTheCanonicalLifecycleAfterDisable() {
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        shippedRegistry(true).register(registry);
        registry.onPluginDisable();

        Sender sender = mock(Sender.class);
        registry.dispatch(sender, "bal", List.of());

        org.mockito.ArgumentCaptor<Throwable> error =
                org.mockito.ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(same(sender), error.capture());
        assertTrue(error.getValue() instanceof com.smile.acelib.command.CommandException
                        && ((com.smile.acelib.command.CommandException) error.getValue()).getCode()
                                .equals("ACELIB-CMD-009"),
                "alias dispatch after disable must report REGISTRY_DISABLED, got: "
                        + error.getValue());
    }

    private static void assertResolvesTo(CommandRegistryImpl registry, String label, String expectedName) {
        CommandSpec spec = registry.findCommand(label);
        assertNotNull(spec, "label '" + label + "' must resolve to a registered spec");
        assertEquals(expectedName, spec.name(),
                "label '" + label + "' must dispatch to the '" + expectedName + "' spec");
    }
}
