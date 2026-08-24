package com.smile.aceeconomy.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the command/permission surface declared in {@code plugin.yml}.
 *
 * <p>These are external plugin-contract assertions; they reference NO v1 class
 * names. They freeze the v2 command surface (money/pay/aceeco/withdraw/baltop/
 * bank) and the permission contract (including rollback and debt-bypass) so a
 * clean-slate rewrite cannot silently drop a retained product feature.</p>
 */
class CommandSurfaceCapabilityTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadPluginYml() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in, "plugin.yml 必須位於測試 classpath");
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("plugin.yml 必須保留核心指令表面")
    void testCoreCommandsPresent() {
        Map<String, Object> cfg = loadPluginYml();
        Map<String, Object> commands = (Map<String, Object>) cfg.get("commands");
        assertNotNull(commands, "commands 區塊必須存在");
        for (String cmd : List.of("money", "pay", "aceeco", "withdraw", "baltop", "bank")) {
            assertTrue(commands.containsKey(cmd), "必須保留指令 /" + cmd);
        }
    }

    @Test
    @DisplayName("plugin.yml 必須保留權限契約 (含 rollback / debt bypass)")
    void testPermissionsPresent() {
        Map<String, Object> cfg = loadPluginYml();
        Map<String, Object> perms = (Map<String, Object>) cfg.get("permissions");
        assertNotNull(perms, "permissions 區塊必須存在");
        for (String p : List.of(
                "aceeconomy.admin", "aceeconomy.command.pay", "aceeconomy.command.money",
                "aceeconomy.command.baltop", "aceeconomy.command.withdraw",
                "aceeconomy.admin.give", "aceeconomy.admin.take", "aceeconomy.admin.set",
                "aceeconomy.admin.history", "aceeconomy.admin.rollback",
                "aceeconomy.bypass.debt", "aceeconomy.command.bank")) {
            assertTrue(perms.containsKey(p), "必須保留權限 " + p);
        }
    }

    @Test
    @DisplayName("plugin.yml 必須宣告可選 Vault 整合與 Folia 支援")
    void testDependenciesAndFolia() {
        Map<String, Object> cfg = loadPluginYml();
        assertTrue(cfg.containsKey("depend"), "必須宣告 depend");
        List<String> softdepend = (List<String>) cfg.get("softdepend");
        assertTrue(softdepend.contains("Vault"), "Vault 應為可選整合 (由 readiness gate 控制)");
        assertEquals(true, cfg.get("folia-supported"), "必須宣告 folia-supported: true");
    }
}
