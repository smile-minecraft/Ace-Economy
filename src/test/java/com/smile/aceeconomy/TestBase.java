package com.smile.aceeconomy;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.smile.aceeconomy.manager.ConfigManager;
import com.smile.aceeconomy.manager.CurrencyManager;
import com.smile.aceeconomy.manager.MessageManager;
import com.smile.aceeconomy.manager.UserCacheManager;
import com.smile.aceeconomy.storage.StorageHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Field;

public class TestBase {

    protected ServerMock server;
    protected AceEconomy plugin;

    @BeforeEach
    public void setUp() {
        // Start MockBukkit
        server = MockBukkit.mock();

        // Mock the plugin loading
        // We load the real plugin class to test actual command logic
        plugin = MockBukkit.load(AceEconomy.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Use reflection to inject a mock object into a private field of the plugin.
     * 
     * @param fieldName The name of the field in AceEconomy class.
     * @param mock      The mock object to inject.
     */
    protected void injectMock(String fieldName, Object mock) {
        try {
            Field field = AceEconomy.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(plugin, mock);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to inject mock for field: " + fieldName, e);
        }
    }
}
