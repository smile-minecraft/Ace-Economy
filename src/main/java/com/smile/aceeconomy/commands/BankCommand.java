package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.gui.BankMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class BankCommand implements CommandExecutor {

    private final AceEconomy plugin;

    public BankCommand(AceEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            plugin.getMessageManager().send(sender, "general.console-only-player");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("aceeconomy.command.bank")) {
            plugin.getMessageManager().send(player, "general.no-permission");
            return true;
        }

        new BankMenu(plugin, player).open();
        return true;
    }
}
