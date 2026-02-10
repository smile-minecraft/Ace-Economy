package com.smile.aceeconomy.utils;

import com.smile.aceeconomy.AceEconomy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 指令註冊工具。
 * <p>
 * 用於在執行時動態註冊指令，支援自定義指令別名。
 * </p>
 *
 * @author Smile
 */
public class CommandRegistrar {

    private final AceEconomy plugin;

    public CommandRegistrar(AceEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * 註冊自定義指令別名。
     *
     * @param alias        自定義別名
     * @param executor     指令執行器
     * @param tabCompleter Tab 補全器
     */
    public void registerCustomAlias(String alias, CommandExecutor executor, TabCompleter tabCompleter) {
        if (alias == null || alias.equalsIgnoreCase("aceeco")) {
            return;
        }

        CommandMap commandMap = getCommandMap();
        if (commandMap == null) {
            plugin.getLogger().warning("無法取得 CommandMap，跳過自定義指令別名註冊。");
            return;
        }

        // 檢查衝突
        Command existingCommand = commandMap.getCommand(alias);
        if (existingCommand != null) {
            plugin.getLogger().warning("自定義指令別名 '" + alias + "' 已被其他插件使用！將回退至預設指令 /aceeco。");
            return;
        }

        try {
            // 建立動態指令
            DynamicPluginCommand dynamicCommand = new DynamicPluginCommand(alias, plugin);
            dynamicCommand.setExecutor(executor);
            dynamicCommand.setTabCompleter(tabCompleter);
            dynamicCommand.setUsage("/" + alias + " <args>");
            dynamicCommand.setDescription("AceEconomy 主指令 (別名)");

            // 註冊指令
            commandMap.register(plugin.getName(), dynamicCommand);
            plugin.getLogger().info("已成功註冊自定義指令別名: /" + alias);

        } catch (Exception e) {
            plugin.getLogger().severe("註冊自定義指令別名時發生錯誤: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 取得 Bukkit CommandMap。
     *
     * @return CommandMap 實例
     */
    private CommandMap getCommandMap() {
        try {
            org.bukkit.plugin.PluginManager pluginManager = Bukkit.getPluginManager();
            Field commandMapField = pluginManager.getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            return (CommandMap) commandMapField.get(pluginManager);
        } catch (Exception e) {
            plugin.getLogger().severe("無法透過反射取得 CommandMap: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 動態插件指令類別。
     * <p>
     * 由於 PluginCommand 建構子是保護的，我們需要繼承 Command 並實作必要邏輯，
     * 或者透過反射建立 PluginCommand。這裡採用繼承 Command 的方式以獲得最大相容性。
     * </p>
     */
    private static class DynamicPluginCommand extends Command {

        private final Plugin plugin;
        private CommandExecutor executor;
        private TabCompleter tabCompleter;

        protected DynamicPluginCommand(@NotNull String name, @NotNull Plugin plugin) {
            super(name);
            this.plugin = plugin;
        }

        public void setExecutor(CommandExecutor executor) {
            this.executor = executor;
        }

        public void setTabCompleter(TabCompleter tabCompleter) {
            this.tabCompleter = tabCompleter;
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
            if (executor != null) {
                return executor.onCommand(sender, this, commandLabel, args);
            }
            return false;
        }

        @NotNull
        @Override
        public List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args)
                throws IllegalArgumentException {
            if (tabCompleter != null) {
                List<String> completions = tabCompleter.onTabComplete(sender, this, alias, args);
                if (completions != null) {
                    return completions;
                }
            }
            return super.tabComplete(sender, alias, args);
        }
    }
}
