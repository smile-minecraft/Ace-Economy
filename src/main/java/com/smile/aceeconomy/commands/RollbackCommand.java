package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.LogManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class RollbackCommand implements CommandExecutor {

    private final AceEconomy plugin;
    private final LogManager logManager;

    public RollbackCommand(AceEconomy plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("aceeconomy.admin.rollback")) {
            sender.sendMessage(Component.text("權限不足！", NamedTextColor.RED));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(Component.text("用法: /aceeco rollback <交易ID>", NamedTextColor.RED));
            return true;
        }

        String transactionId = args[0];
        sender.sendMessage(Component.text("正在嘗試回溯交易 " + transactionId + "...", NamedTextColor.YELLOW));

        logManager.rollbackTransaction(transactionId).thenAccept(resultMessage -> {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(resultMessage));
        });

        return true;
    }
}
