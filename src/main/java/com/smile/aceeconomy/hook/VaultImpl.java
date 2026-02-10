package com.smile.aceeconomy.hook;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.api.EconomyProvider;
import com.smile.aceeconomy.manager.CurrencyManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;

/**
 * Vault 經濟介面實作。
 * <p>
 * 將 Vault 的同步方法映射至 {@link CurrencyManager} 的記憶體快取。
 * 由於快取是即時的，大部分操作可同步完成。
 * 對於離線玩家（未在快取中），會回傳適當的錯誤回應。
 * </p>
 *
 * @author Smile
 */
public class VaultImpl implements Economy {

    private final AceEconomy plugin;
    private final CurrencyManager currencyManager;
    private final EconomyProvider economyProvider;

    /**
     * 建立 Vault 經濟實作。
     *
     * @param plugin          插件實例
     * @param economyProvider 經濟服務提供者
     */
    public VaultImpl(AceEconomy plugin, EconomyProvider economyProvider) {
        this.plugin = plugin;
        this.currencyManager = plugin.getCurrencyManager();
        this.economyProvider = economyProvider;
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "AceEconomy";
    }

    @Override
    public boolean hasBankSupport() {
        // 目前不支援銀行功能
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return String.format("$%.2f", amount);
    }

    @Override
    public String currencyNamePlural() {
        return "元";
    }

    @Override
    public String currencyNameSingular() {
        return "元";
    }

    // ========== 玩家帳戶操作 ==========

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return currencyManager.hasAccount(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(String playerName) {
        // 不推薦使用字串名稱，但仍需實作
        return false;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        // 不支援多世界，忽略 worldName
        return hasAccount(player);
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return currencyManager.getBalance(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName) {
        // 不推薦使用字串名稱
        return 0.0;
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        // 不支援多世界
        return getBalance(player);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "金額不能為負數");
        }

        if (!currencyManager.hasAccount(player.getUniqueId())) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "帳戶未載入（玩家離線）");
        }

        // 使用 EconomyProvider 進行操作（同步等待結果）
        boolean success;
        String errorMessage = "餘額不足或交易被取消";
        try {
            success = economyProvider.withdraw(player.getUniqueId(), amount).join();
        } catch (Exception e) {
            success = false;
            // Unpack CompletionException
            Throwable cause = e.getCause();
            if (cause != null) {
                errorMessage = cause.getMessage();
            } else {
                errorMessage = e.getMessage();
            }
        }

        double balance = currencyManager.getBalance(player.getUniqueId());
        if (success) {
            return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
        } else {
            return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, errorMessage);
        }
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "不支援使用玩家名稱");
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "金額不能為負數");
        }

        if (!currencyManager.hasAccount(player.getUniqueId())) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "帳戶未載入（玩家離線）");
        }

        // 使用 EconomyProvider 進行操作（同步等待結果）
        boolean success = economyProvider.deposit(player.getUniqueId(), amount).join();

        double balance = currencyManager.getBalance(player.getUniqueId());
        if (success) {
            return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
        } else {
            return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, "交易被取消");
        }
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "不支援使用玩家名稱");
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        // 帳戶在玩家登入時自動建立
        return currencyManager.hasAccount(player.getUniqueId());
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return false;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    // ========== 銀行操作（不支援）==========

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "不支援銀行功能");
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }
}
