package com.smile.aceeconomy.bootstrap;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the withdraw dispatch-timeout configuration boundary:
 *
 * <ul>
 *   <li>the shipped {@code config.yml} declares {@code withdraw.dispatch-timeout-seconds}
 *       with the documented positive default;</li>
 *   <li>all three configuration guides document the key, including the late-callback
 *       fail-closed behaviour and the zero/negative fail-safe floor;</li>
 *   <li>a zero or negative operator value is clamped to the one-second fail-safe floor
 *       instead of arming every bounded wait with a zero delay (which would time out
 *       every withdraw immediately), while positive values stay operator-owned.</li>
 * </ul>
 */
class WithdrawDispatchTimeoutConfigTest {

    private static final String KEY = "withdraw.dispatch-timeout-seconds";

    private static Map<String, Object> loadShippedConfig() throws Exception {
        try (InputStream in = WithdrawDispatchTimeoutConfigTest.class
                .getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "missing resource on classpath: config.yml");
            return new Yaml().load(in);
        }
    }

    private static String readDoc(String name) throws Exception {
        Path doc = Path.of("docs", name);
        assertTrue(Files.isRegularFile(doc), "missing configuration guide: " + doc);
        return Files.readString(doc, StandardCharsets.UTF_8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shippedConfigDeclaresPositiveDefaultTimeout() throws Exception {
        Map<String, Object> cfg = loadShippedConfig();
        Object withdraw = cfg.get("withdraw");
        assertNotNull(withdraw, "config.yml must declare the withdraw section");
        Object timeout = assertInstanceOf(Map.class, withdraw, "withdraw must be a mapping")
                .get("dispatch-timeout-seconds");
        assertNotNull(timeout, "config.yml must declare " + KEY);
        assertInstanceOf(Number.class, timeout, KEY + " must be numeric");
        assertTrue(((Number) timeout).intValue() >= 1,
                KEY + " must default to a positive value");
    }

    @Test
    void configurationGuidesDocumentTheTimeoutKey() throws Exception {
        for (String doc : new String[]{"config.md", "config.zh-CN.md", "config.zh-TW.md"}) {
            String text = readDoc(doc);
            assertTrue(text.contains(KEY),
                    doc + " must document " + KEY);
            assertTrue(text.contains("withdraw:")
                            && text.contains("dispatch-timeout-seconds"),
                    doc + " must show the withdraw config snippet");
        }
    }

    @Test
    void zeroOrNegativeTimeoutClampsToTheFailSafeFloor() {
        assertEquals(Duration.ofSeconds(1), CompositionRoot.effectiveDispatchTimeout(0),
                "a zero timeout must clamp to the 1-second fail-safe floor");
        assertEquals(Duration.ofSeconds(1), CompositionRoot.effectiveDispatchTimeout(-5),
                "a negative timeout must clamp to the 1-second fail-safe floor");
    }

    @Test
    void positiveTimeoutStaysOperatorOwned() {
        assertEquals(Duration.ofSeconds(1), CompositionRoot.effectiveDispatchTimeout(1));
        assertEquals(Duration.ofSeconds(10), CompositionRoot.effectiveDispatchTimeout(10),
                "the documented default must pass through unchanged");
        assertEquals(Duration.ofSeconds(30), CompositionRoot.effectiveDispatchTimeout(30));
    }
}
