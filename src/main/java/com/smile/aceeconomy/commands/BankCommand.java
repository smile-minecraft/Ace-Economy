package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.gui.BankMenu;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class BankCommand implements CommandExecutor {

    private final AceEconomy plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public BankCommand(AceEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(miniMessage.deserialize("<red>Only players can use this command."));
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("aceeconomy.command.bank")) {
            player.sendMessage(miniMessage.deserialize("<red>You do not have permission to use this command."));
            return true;
        }

        new BankMenu(plugin, player).open();
        return true;
    }
}
