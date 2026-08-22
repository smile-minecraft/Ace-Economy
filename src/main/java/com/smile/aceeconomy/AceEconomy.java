package com.smile.aceeconomy;

import com.smile.aceeconomy.bootstrap.CompositionRoot;
import org.bukkit.plugin.java.JavaPlugin;

/** Thin Paper/Folia entrypoint; all v2 construction and teardown lives in {@link CompositionRoot}. */
public final class AceEconomy extends JavaPlugin {
    private CompositionRoot root;

    @Override
    public void onEnable() {
        root = new CompositionRoot(this);
        try {
            root.start();
        } catch (Exception failure) {
            getLogger().severe("AceEconomy cannot start: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (root != null) {
            root.stop();
            root = null;
        }
    }
}
