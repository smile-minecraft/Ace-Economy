package com.smile.aceeconomy;

import com.smile.aceeconomy.api.EconomyProvider;
import com.smile.aceeconomy.manager.ConfigManager;

import com.smile.aceeconomy.manager.MessageManager;
import com.smile.aceeconomy.manager.UserCacheManager;
import com.smile.aceeconomy.storage.StorageHandler;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.Mockito.*;

/**
 * 純 Mockito 測試基底類別。
 * <p>
 * 完全不使用 MockBukkit，改用 MockedStatic&lt;Bukkit&gt; 來模擬
 * 靜態方法 (Bukkit.getPlayer, Bukkit.getPluginManager 等)。
 * 這避免了 paperweight 與 MockBukkit 之間的 SharedConstants 衝突。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class TestBase {

    // --- Mocked Plugin & Managers ---
    @Mock
    protected AceEconomy plugin;
    @Mock
    protected MessageManager messageManager;
    @Mock
    protected ConfigManager configManager;
    @Mock
    protected StorageHandler storageHandler;
    @Mock
    protected UserCacheManager userCacheManager;
    @Mock
    protected EconomyProvider economyProvider;
    @Mock
    protected Command command;
    @Mock
    protected Server server;
    @Mock
    protected PluginManager pluginManager;

    // --- Static mock for Bukkit ---
    protected MockedStatic<Bukkit> bukkitMock;

    @BeforeEach
    public void setUpBase() {
        // Wire plugin getters to return our mocks
        lenient().when(plugin.getMessageManager()).thenReturn(messageManager);
        lenient().when(plugin.getConfigManager()).thenReturn(configManager);
        lenient().when(plugin.getStorageHandler()).thenReturn(storageHandler);
        lenient().when(plugin.getUserCacheManager()).thenReturn(userCacheManager);
        lenient().when(plugin.getEconomyProvider()).thenReturn(economyProvider);
        lenient().when(plugin.getServer()).thenReturn(server);

        // Static Bukkit mock
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        // Default: Bukkit.getPlayer(name) returns null (offline)
        bukkitMock.when(() -> Bukkit.getPlayer((String) any())).thenReturn(null);
        bukkitMock.when(() -> Bukkit.getPlayer((java.util.UUID) any())).thenReturn(null);
    }

    @AfterEach
    public void tearDownBase() {
        if (bukkitMock != null) {
            bukkitMock.close();
        }
    }

    /**
     * 建立一個最小可用的 Mock Player。
     *
     * @param name 玩家名稱
     * @param uuid 玩家 UUID
     * @return 已設定好名稱、UUID、權限的 Mock Player
     */
    protected Player mockPlayer(String name, java.util.UUID uuid) {
        Player player = mock(Player.class);
        lenient().when(player.getName()).thenReturn(name);
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        // Default: has all permissions
        lenient().when(player.hasPermission(anyString())).thenReturn(true);
        return player;
    }
}
