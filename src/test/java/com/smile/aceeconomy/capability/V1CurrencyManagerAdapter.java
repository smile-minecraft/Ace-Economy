package com.smile.aceeconomy.capability;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.data.Currency;
import com.smile.aceeconomy.manager.ConfigManager;
import com.smile.aceeconomy.manager.CurrencyManager;
import com.smile.aceeconomy.manager.PermissionManager;
import com.smile.aceeconomy.storage.StorageHandler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * [CONTRACT:P2] 測試專用 adapter，把 v1 {@link CurrencyManager} 對應到 v2
 * {@link EconomyCapability} 契約。
 *
 * <p>這是唯一被允許引用 v1 實作類別的類別，是 anti-corruption seam：
 * capability 測試只依賴契約，因此 v2 只需以新引擎的 adapter 替換本類別，
 * 測試即保持有效。本類別絕不可被 production code 使用。</p>
 */
public final class V1CurrencyManagerAdapter implements EconomyCapability, AutoCloseable {

    private final CurrencyManager delegate;
    private final boolean allowNegativeBalance;
    private final double debtLimit;
    private final MockedStatic<Bukkit> bukkitMock;

    private V1CurrencyManagerAdapter(CurrencyManager delegate, boolean allowNegativeBalance,
            double debtLimit, MockedStatic<Bukkit> bukkitMock) {
        this.delegate = delegate;
        this.allowNegativeBalance = allowNegativeBalance;
        this.debtLimit = debtLimit;
        this.bukkitMock = bukkitMock;
    }

    /**
     * Build an adapter over a v1 CurrencyManager with the given debt policy.
     * A Bukkit static mock is only installed when debt is enabled, because
     * {@code getDebtLimit} calls {@code Bukkit.getOfflinePlayer} only then.
     */
    public static V1CurrencyManagerAdapter create(boolean allowNegativeBalance, double debtLimit) {
        AceEconomy plugin = mock(AceEconomy.class);
        StorageHandler storageHandler = mock(StorageHandler.class);
        PermissionManager permissionManager = mock(PermissionManager.class);
        ConfigManager configManager = mock(ConfigManager.class);

        Currency dollar = new Currency("dollar", "金幣", "$", "#,##0.00", true);
        Currency token = new Currency("token", "活動代幣", "ⓒ", "#,##0", false);

        when(configManager.getCurrencies()).thenReturn(Map.of("dollar", dollar, "token", token));
        when(configManager.getDefaultCurrency()).thenReturn(dollar);
        when(configManager.getStartBalance()).thenReturn(1000.0);
        when(configManager.isAllowNegativeBalance()).thenReturn(allowNegativeBalance);
        when(configManager.getCurrency("dollar")).thenReturn(dollar);
        when(configManager.getCurrency("token")).thenReturn(token);
        when(configManager.getCurrency("nonexistent")).thenReturn(null);

        MockedStatic<Bukkit> bukkitMock = null;
        if (allowNegativeBalance) {
            OfflinePlayer offlinePlayer = mock(OfflinePlayer.class);
            bukkitMock = mockStatic(Bukkit.class);
            bukkitMock.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenReturn(offlinePlayer);
            when(permissionManager.getDebtLimit(any(OfflinePlayer.class))).thenReturn(debtLimit);
        }

        CurrencyManager cm = new CurrencyManager(plugin, permissionManager, storageHandler, configManager);
        return new V1CurrencyManagerAdapter(cm, allowNegativeBalance, debtLimit, bukkitMock);
    }

    /** Adapter with debt disabled (balance can never go below 0). */
    public static V1CurrencyManagerAdapter createDebtDisabled() {
        return create(false, 0.0);
    }

    /** Adapter with debt enabled and the given debt limit. */
    public static V1CurrencyManagerAdapter createDebtEnabled(double debtLimit) {
        return create(true, debtLimit);
    }

    @Override
    public void createAccount(UUID uuid, String ownerName) {
        delegate.createAccount(uuid, ownerName);
    }

    @Override
    public boolean hasAccount(UUID uuid) {
        return delegate.hasAccount(uuid);
    }

    @Override
    public double getBalance(UUID uuid) {
        return delegate.getBalance(uuid);
    }

    @Override
    public boolean deposit(UUID uuid, double amount) {
        return delegate.deposit(uuid, amount);
    }

    @Override
    public boolean withdraw(UUID uuid, double amount) throws InsufficientFundsException {
        try {
            return delegate.withdraw(uuid, amount);
        } catch (com.smile.aceeconomy.exception.InsufficientFundsException e) {
            throw new InsufficientFundsException(e.getMessage());
        }
    }

    @Override
    public boolean setBalance(UUID uuid, double amount) {
        return delegate.setBalance(uuid, amount);
    }

    @Override
    public boolean isNegativeBalanceAllowed() {
        return allowNegativeBalance;
    }

    @Override
    public double getDebtLimit(UUID uuid) {
        return allowNegativeBalance ? debtLimit : 0.0;
    }

    @Override
    public boolean currencyExists(String currencyId) {
        return delegate.currencyExists(currencyId);
    }

    @Override
    public String getDefaultCurrencyId() {
        return delegate.getDefaultCurrencyId();
    }

    @Override
    public void close() {
        if (bukkitMock != null) {
            bukkitMock.close();
        }
    }
}
