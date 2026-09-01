package com.smile.aceeconomy.infrastructure.acelib;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage test: production-covered keys must exist in all three canonical lang files.
 *
 * <p>The expected set comes from {@link ProductionMessageKeys#EXPECTED}, an explicit
 * auditable registry, not from the resources themselves. This avoids tautology:
 * a missing production key in any locale fails even if all three resources agree.</p>
 */
public class LanguageCoverageTest {

    @Test
    void allLocalesHaveCompleteKeyCoverage() {
        Set<String> expected = ProductionMessageKeys.EXPECTED;
        assertFalse(expected.isEmpty(), "production registry must not be empty");
        // Also verify the three lang files agree with each other (drift detection)
        Set<String> enKeys = loadKeys("lang/en_US.yml");
        Set<String> zhTWKeys = loadKeys("lang/zh_TW.yml");
        Set<String> zhCNKeys = loadKeys("lang/zh_CN.yml");

        assertFalse(enKeys.isEmpty(), "en_US.yml must not be empty");
        assertFalse(zhTWKeys.isEmpty(), "zh_TW.yml must not be empty");
        assertFalse(zhCNKeys.isEmpty(), "zh_CN.yml must not be empty");

        for (String locale : new String[]{"en_US", "zh_TW", "zh_CN"}) {
            Set<String> keys = switch (locale) {
                case "en_US" -> enKeys;
                case "zh_TW" -> zhTWKeys;
                case "zh_CN" -> zhCNKeys;
                default -> Set.of();
            };
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(keys);
            assertTrue(missing.isEmpty(),
                    locale + " is missing production keys: " + missing);
        }

        // Bidirectional drift: ensure no locale has extra keys absent from expected
        // (keeps registry in sync with resources)
        for (String locale : new String[]{"en_US", "zh_TW", "zh_CN"}) {
            Set<String> keys = switch (locale) {
                case "en_US" -> enKeys;
                case "zh_TW" -> zhTWKeys;
                case "zh_CN" -> zhCNKeys;
                default -> Set.of();
            };
            Set<String> extra = new HashSet<>(keys);
            extra.removeAll(expected);
            assertTrue(extra.isEmpty(),
                    locale + " has keys absent from ProductionMessageKeys registry: " + extra
                            + " — update the registry or lang files");
        }

        // Also verify the three files are mutually consistent (no locale drift)
        Set<String> missingTw = new HashSet<>(enKeys);
        missingTw.removeAll(zhTWKeys);
        assertTrue(missingTw.isEmpty(), "zh_TW.yml is missing keys vs en_US: " + missingTw);

        Set<String> missingCn = new HashSet<>(enKeys);
        missingCn.removeAll(zhCNKeys);
        assertTrue(missingCn.isEmpty(), "zh_CN.yml is missing keys vs en_US: " + missingCn);
    }

    @Test
    void productionKeysCoverScannerBoundary() {
        // Boundary: any literal lang key in src/main/java must be in the registry.
        // Scans production sources for quoted keys that look like lang keys.
        // This guards future command migration: adding a new key literal without updating the registry fails.
        Set<String> registry = ProductionMessageKeys.EXPECTED;
        Set<String> literals = scanProductionLiterals();
        Set<String> notInRegistry = new HashSet<>(literals);
        notInRegistry.removeAll(registry);
        assertTrue(notInRegistry.isEmpty(),
                "production literals not in registry (update ProductionMessageKeys): " + notInRegistry);
    }

    private static java.nio.file.Path resolveSourceRoot() {
        java.nio.file.Path candidate = java.nio.file.Paths.get("src/main/java");
        if (java.nio.file.Files.exists(candidate) && java.nio.file.Files.isDirectory(candidate)) {
            return candidate;
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            java.nio.file.Path alt = java.nio.file.Paths.get(userDir, "src/main/java");
            if (java.nio.file.Files.exists(alt) && java.nio.file.Files.isDirectory(alt)) {
                return alt;
            }
        }
        // Fallback: try to locate via classloader resource
        try {
            java.net.URL url = LanguageCoverageTest.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                java.nio.file.Path p = java.nio.file.Paths.get(url.toURI());
                // Walk up to project root
                java.nio.file.Path cur = p;
                for (int i = 0; i < 6 && cur != null; i++) {
                    java.nio.file.Path tryRoot = cur.resolve("src/main/java");
                    if (java.nio.file.Files.exists(tryRoot) && java.nio.file.Files.isDirectory(tryRoot)) {
                        return tryRoot;
                    }
                    cur = cur.getParent();
                }
            }
        } catch (Exception ignored) {}
        return candidate;
    }

    private Set<String> scanProductionLiterals() {
        // Limited to literal lang-like strings that are likely message keys.
        // Scans only command/gui/acelib usage surfaces, not config/persistence where
        // "economy.allow-negative-balance" etc. are config paths, not message keys.
        // Also only counts literals that appear near message API names to avoid
        // false positives from config keys.
        // Fail-closed: missing source root or any walk/read failure must fail the test.
        java.nio.file.Path root = resolveSourceRoot();
        if (!java.nio.file.Files.exists(root) || !java.nio.file.Files.isDirectory(root)) {
            fail("source root missing or not a directory (fail-closed): " + root.toAbsolutePath());
        }
        java.util.regex.Pattern keyPattern = java.util.regex.Pattern.compile(
                "\"((?:message|general|economy|admin|command|error|gui|banknote|baltop|history|rollback|usage|console)\\.[a-z0-9_.-]+)\"");
        java.util.regex.Pattern apiHint = java.util.regex.Pattern.compile(
                "MessageService|LangManager|renderMessage|plainMessage|formatComponent|format\\(");
        Set<String> found = new HashSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
            java.util.List<java.nio.file.Path> files = walk
                    .filter(f -> f.toString().endsWith(".java"))
                    .toList();
            if (files.isEmpty()) {
                fail("source root contains no .java files (fail-closed): " + root.toAbsolutePath());
            }
            for (java.nio.file.Path f : files) {
                String content;
                try {
                    content = java.nio.file.Files.readString(f);
                } catch (Exception e) {
                    fail("failed to read source file (fail-closed): " + f + " : " + e.getMessage());
                    continue;
                }
                String path = f.toString();
                if (path.contains("/persistence/") || path.contains("/bootstrap/") || path.contains("/storage/")) {
                    if (!apiHint.matcher(content).find()) {
                        continue;
                    }
                }
                if (!apiHint.matcher(content).find() && !path.contains("/commands/") && !path.contains("/gui/")) {
                    continue;
                }
                java.util.regex.Matcher m = keyPattern.matcher(content);
                while (m.find()) {
                    String key = m.group(1);
                    if (key.equals("economy.allow-negative-balance") || key.equals("economy.default-debt-limit")) {
                        continue;
                    }
                    if (key.startsWith("banknote.invalid") || key.equals("currency.unknown") || key.equals("value.nonpositive")) {
                        continue;
                    }
                    found.add(key);
                }
            }
        } catch (Exception e) {
            fail("failed to walk source root (fail-closed): " + root.toAbsolutePath() + " : " + e.getMessage());
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private Set<String> loadKeys(String resourcePath) {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(inputStream, "Could not find resource: " + resourcePath);
            Map<String, Object> obj = yaml.load(inputStream);
            return flattenKeys(obj, "");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + resourcePath, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> flattenKeys(Map<String, Object> map, String prefix) {
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                keys.addAll(flattenKeys((Map<String, Object>) entry.getValue(), key));
            } else {
                keys.add(key);
            }
        }
        return keys;
    }
}
