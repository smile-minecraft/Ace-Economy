package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.commands.v2.TypedErrors;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Freeze, read-only and control-unavailable refusals each have their own message
 * key in all three locales; the typed mapping must use them instead of the generic
 * transaction failure.
 *
 * <p>Registry and resource assertions already hold (the skeleton added the keys);
 * the mapping assertions fail because the skeleton still maps all three to
 * {@code general.transaction-failed}.
 */
class SecurityRefusalMessageTest {

    private static final Set<String> NEW_KEYS = Set.of(
            "general.account-frozen",
            "general.global-readonly",
            "general.control-unavailable");

    private static Set<String> loadKeys(String resource) {
        Yaml yaml = new Yaml();
        try (InputStream in = SecurityRefusalMessageTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertTrue(in != null, "missing test resource: " + resource);
            Map<String, Object> root = yaml.load(in);
            Set<String> out = new HashSet<>();
            collect("", root, out);
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collect(String prefix, Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String full = prefix.isEmpty() ? key : prefix + "." + key;
                Object value = entry.getValue();
                if (value instanceof Map) {
                    collect(full, value, out);
                } else {
                    out.add(full);
                }
            }
        }
    }

    @Test
    @DisplayName("registry and all three locales contain the three refusal keys")
    void registryAndLocalesContainRefusalKeys() {
        assertTrue(ProductionMessageKeys.EXPECTED.containsAll(NEW_KEYS),
                "registry must contain the three refusal keys");

        for (String locale : new String[]{"lang/en_US.yml", "lang/zh_TW.yml", "lang/zh_CN.yml"}) {
            Set<String> keys = loadKeys(locale);
            assertTrue(keys.containsAll(NEW_KEYS), locale + " must contain the three refusal keys");
            for (String key : NEW_KEYS) {
                assertFalse(key.isBlank(), "refusal key must not be blank");
            }
        }

        Set<String> en = loadKeys("lang/en_US.yml");
        Set<String> tw = loadKeys("lang/zh_TW.yml");
        Set<String> cn = loadKeys("lang/zh_CN.yml");
        assertTrue(tw.containsAll(en.stream().filter(NEW_KEYS::contains).toList()),
                "zh_TW must match en for the new keys");
        assertTrue(cn.containsAll(en.stream().filter(NEW_KEYS::contains).toList()),
                "zh_CN must match en for the new keys");
    }

    @Test
    @DisplayName("frozen and read-only map to distinct message keys")
    void frozenAndReadonlyUseDistinctKeys() {
        ConfigLangAdapter messages = mock(ConfigLangAdapter.class);
        when(messages.plainMessage(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        String frozen = TypedErrors.from(messages,
                EconomyResult.failure(EconomyError.ACCOUNT_FROZEN, "frozen")).getMessage();
        String readonly = TypedErrors.from(messages,
                EconomyResult.failure(EconomyError.GLOBAL_READ_ONLY, "readonly")).getMessage();

        assertNotEquals("general.transaction-failed", frozen,
                "frozen refusal must not reuse the generic transaction failure");
        assertNotEquals("general.transaction-failed", readonly,
                "read-only refusal must not reuse the generic transaction failure");
        assertNotEquals(frozen, readonly, "frozen and read-only must be distinguishable");
    }

    @Test
    @DisplayName("control-unavailable maps to its own key with its own code")
    void unavailableHasOwnKeyAndCode() {
        var frozen = TypedErrors.from(EconomyResult.failure(EconomyError.ACCOUNT_FROZEN, "x"));
        var unavailable =
                TypedErrors.from(EconomyResult.failure(EconomyError.CONTROL_UNAVAILABLE, "x"));

        assertNotEquals(frozen.getCode(), unavailable.getCode(),
                "unavailable must carry its own error code");
        assertEquals("ACELIB-CMD-CONTROL-UNAVAILABLE", unavailable.getCode());

        ConfigLangAdapter messages = mock(ConfigLangAdapter.class);
        when(messages.plainMessage(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        String text = TypedErrors.from(messages,
                EconomyResult.failure(EconomyError.CONTROL_UNAVAILABLE, "x")).getMessage();
        assertNotEquals("general.transaction-failed", text,
                "unavailable must not reuse the generic transaction failure");
    }

    @Test
    @DisplayName("load failure diagnosis never echoes the raw file content")
    void loadDiagnosisNeverEchoesRaw() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("control-diagnosis");
        java.nio.file.Path file = dir.resolve("safety-control.json");
        java.nio.file.Files.writeString(file, "{corrupt control file[[[",
                java.nio.charset.StandardCharsets.UTF_8);
        com.smile.aceeconomy.infrastructure.persistence.security.FileSecurityControlStore store =
                new com.smile.aceeconomy.infrastructure.persistence.security.FileSecurityControlStore(file);
        com.smile.aceeconomy.operations.security.SecurityControlService control =
                new com.smile.aceeconomy.operations.security.SecurityControlService(store);

        assertTrue(control.isLoadFailed(), "corrupt control must report a load failure");
        String diagnosis = control.loadDiagnosis();
        assertFalse(diagnosis.isBlank(), "load failure must carry a loud fixed diagnosis");
        assertFalse(diagnosis.contains("{corrupt"),
                "diagnosis must be fixed text, never the raw file content");
    }
}
