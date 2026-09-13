package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistry;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.Sender;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link CommandRegistry} decorator that lets a configured root command run its default
 * subcommand when a player invokes the bare label.
 *
 * <p>AceLib v1.2.1 has no root-level {@code CommandSpec} handler: an empty argument list is
 * always answered with the generated help text. That makes {@code /money} show usage instead
 * of the caller's balance even though the {@code balance} subcommand already implements the
 * self-balance path with no arguments. Rewriting the empty dispatch to the configured default
 * subcommand restores the intended UX without touching AceLib and without changing how
 * explicit subcommands are parsed — {@code /money balance}, {@code /money balance <player>}
 * and every other subcommand keep flowing through unchanged.</p>
 *
 * <p>Console senders are never rewritten: only a player has "their own" balance, so a console
 * {@code /money} keeps showing help rather than failing the player-only self-balance path.
 * Aliases resolve through the delegate, so the default applies to the primary name and every
 * alias (for example {@code /bal}) uniformly.</p>
 */
public final class DefaultSubcommandRegistry implements CommandRegistry {

    private final CommandRegistry delegate;
    private final Map<String, String> defaultsByPrimaryName;

    public DefaultSubcommandRegistry(CommandRegistry delegate, Map<String, String> defaultsByPrimaryName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.defaultsByPrimaryName = Map.copyOf(
                Objects.requireNonNull(defaultsByPrimaryName, "defaultsByPrimaryName"));
    }

    @Override
    public void dispatch(Sender sender, String commandLabel, List<String> args) {
        if (args.isEmpty() && sender != null && sender.isPlayer()) {
            CommandSpec spec = delegate.findCommand(commandLabel);
            String defaultSub = spec == null ? null : defaultsByPrimaryName.get(spec.name());
            if (defaultSub != null) {
                delegate.dispatch(sender, commandLabel, List.of(defaultSub));
                return;
            }
        }
        delegate.dispatch(sender, commandLabel, args);
    }

    @Override
    public void register(CommandSpec spec) {
        delegate.register(spec);
    }

    @Override
    public void unregister(String name) {
        delegate.unregister(name);
    }

    @Override
    public Collection<CommandSpec> getRegisteredCommands() {
        return delegate.getRegisteredCommands();
    }

    @Override
    public CommandSpec findCommand(String name) {
        return delegate.findCommand(name);
    }

    @Override
    public List<String> tabComplete(Sender sender, String commandLabel, List<String> args) {
        return delegate.tabComplete(sender, commandLabel, args);
    }

    @Override
    public String formatHelp(String commandLabel, Sender sender) {
        return delegate.formatHelp(commandLabel, sender);
    }

    @Override
    public void onPluginDisable() {
        delegate.onPluginDisable();
    }

    @Override
    public boolean isDisabled() {
        return delegate.isDisabled();
    }
}
