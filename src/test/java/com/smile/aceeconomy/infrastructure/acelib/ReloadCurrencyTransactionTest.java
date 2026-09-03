package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * All-or-nothing reload transaction covering currencies and restart-only settings.
 *
 * <p>A reload must validate the candidate config, lang, currencies and GUI layout
 * before swapping anything: any failure keeps the previous runtime untouched.
 * Display-only currency changes hot-apply through the runtime hook; structural
 * currency changes are refused with a restart notice instead of a silent
 * half-application.
 */
class ReloadCurrencyTransactionTest {

    @TempDir
    Path tempDir;

    JavaPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        Mockito.when(plugin.getLogger()).thenReturn(Mockito.mock(Logger.class));
        for (Locale loc : List.of(Locale.US, Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE)) {
            String fileName = ConfigLangAdapter.localeToFileName(loc);
            copyResource("lang/" + fileName, tempDir.resolve("lang").resolve(fileName));
        }
        copyResource("config.yml", tempDir.resolve("config.yml"));
        Mockito.doAnswer(inv -> {
            String res = inv.getArgument(0);
            boolean replace = inv.getArgument(1);
            Path target = tempDir.resolve(res);
            if (Files.exists(target) && !replace) {
                return null;
            }
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(res)) {
                if (in == null) {
                    throw new IllegalArgumentException("missing resource " + res);
                }
                Files.createDirectories(target.getParent());
                if (replace || !Files.exists(target)) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return null;
        }).when(plugin).saveResource(Mockito.anyString(), Mockito.anyBoolean());
    }

    private void copyResource(String resourcePath, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "missing classpath resource: " + resourcePath);
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Live registry matching the shipped config fixture. */
    private static CurrencyRegistry shippedRegistry() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 2, true),
                Currency.define("token", "活動代幣", "ⓒ", 0, false)));
    }

    /** Test runtime hook: approves only identical / display-only plans, records swaps. */
    private static final class RecordingRuntime implements ReloadRuntime {
        private final CurrencyRegistry live;
        final List<CurrencyReloadPlan.Classification> reviewed = new CopyOnWriteArrayList<>();
        final List<CurrencyRegistry> applied = new CopyOnWriteArrayList<>();
        final List<BankGuiLayout> appliedLayouts = new CopyOnWriteArrayList<>();

        RecordingRuntime(CurrencyRegistry live) {
            this.live = live;
        }

        @Override
        public CurrencyRegistry liveCurrencies() {
            return live;
        }

        @Override
        public List<String> reviewCurrencyCandidate(CurrencyReloadPlan.Classification classification) {
            reviewed.add(classification);
            return switch (classification.disposition()) {
                case IDENTICAL, DISPLAY_ONLY -> List.of();
                case ADDED, DANGEROUS, INVALID ->
                        List.of(classification.summary() + " :: " + String.join("; ", classification.details()));
            };
        }

        @Override
        public void applyApproved(CurrencyReloadPlan.Classification classification, BankGuiLayout layout) {
            applied.add(classification.candidate());
            appliedLayouts.add(layout);
        }
    }

    private void rewriteConfig(String oldText, String newText) throws IOException {
        // Token-key renames survive adapter normalization verbatim; value edits below
        // use rewriteFirstMatchingLine instead.
        Path cfg = tempDir.resolve("config.yml");
        String content = Files.readString(cfg, StandardCharsets.UTF_8);
        assertTrue(content.contains(oldText), "fixture must contain: " + oldText);
        Files.writeString(cfg, content.replace(oldText, newText), StandardCharsets.UTF_8);
    }

    /**
     * Replace the whole Nth line whose trimmed form starts with {@code keyPrefix}.
     * The adapter normalizes scalar formatting on load, so value edits must not depend
     * on the shipped quoting style.
     */
    private void rewriteFirstMatchingLine(String keyPrefix, String replacement, int occurrence)
            throws IOException {
        Path cfg = tempDir.resolve("config.yml");
        java.util.List<String> lines = Files.readAllLines(cfg, StandardCharsets.UTF_8);
        int seen = 0;
        boolean done = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith(keyPrefix)) {
                if (seen == occurrence) {
                    lines.set(i, replacement);
                    done = true;
                    break;
                }
                seen++;
            }
        }
        assertTrue(done, "fixture must contain line starting with: " + keyPrefix);
        Files.write(cfg, lines, StandardCharsets.UTF_8);
    }

    @Test
    void displayOnlySymbolChangeHotApplies() {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE);
        adapter.load();
        RecordingRuntime runtime = new RecordingRuntime(shippedRegistry());

        assertDoesNotThrow(() -> rewriteFirstMatchingLine("symbol:", "    symbol: \"€\"", 0));
        ReloadResult result = adapter.reloadWithRuntime(runtime);

        assertTrue(result.success(), "display-only reload must succeed: " + result.diagnostics());
        assertEquals(1, runtime.reviewed.size());
        assertEquals(CurrencyReloadPlan.Disposition.DISPLAY_ONLY,
                runtime.reviewed.get(0).disposition());
        assertEquals(1, runtime.applied.size());
        assertEquals("€", runtime.applied.get(0).get("dollar").symbol());
        // Config snapshot itself moved forward as part of the same transaction.
        assertNotNull(adapter.getConfig("currencies"));
        assertEquals(1, runtime.appliedLayouts.size());
    }

    @Test
    void removedCurrencyRejectsWholeReloadAndKeepsOldSnapshot() throws IOException {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE);
        adapter.load();
        Object beforeStartBalance = adapter.getConfig("start-balance");
        RecordingRuntime runtime = new RecordingRuntime(shippedRegistry());

        rewriteConfig("  token:", "  renamed:");
        ReloadResult result = adapter.reloadWithRuntime(runtime);

        assertFalse(result.success(), "removal must refuse reload, got: " + result.diagnostics());
        assertEquals(1, runtime.reviewed.size());
        assertEquals(CurrencyReloadPlan.Disposition.DANGEROUS,
                runtime.reviewed.get(0).disposition());
        assertTrue(runtime.applied.isEmpty(), "rejected reload must not apply anything");
        assertTrue(result.diagnostics().contains("restart"),
                "rejection must tell the operator a restart is required: " + result.diagnostics());
        // All-or-nothing: the previous config snapshot is untouched.
        assertEquals(String.valueOf(beforeStartBalance), String.valueOf(adapter.getConfig("start-balance")));
        assertTrue(adapter.getConfig("currencies") != null);
    }

    @Test
    void invalidCandidateCurrenciesFailReloadWithoutSwap() throws IOException {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE);
        adapter.load();
        RecordingRuntime runtime = new RecordingRuntime(shippedRegistry());

        // Two defaults: the candidate section no longer parses. Flip the token entry
        // (the second default line) from false to true.
        rewriteFirstMatchingLine("default:", "    default: true", 1);

        ReloadResult result = adapter.reloadWithRuntime(runtime);

        assertFalse(result.success(), "invalid currencies must fail reload: " + result.diagnostics());
        assertTrue(runtime.applied.isEmpty(), "failed reload must not apply anything");
        assertNotNull(adapter.getConfig("currencies"), "old snapshot must survive");
    }

    @Test
    void restartOnlyAliasChangeAppliesWithClearNote() throws IOException {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE);
        adapter.load();
        RecordingRuntime runtime = new RecordingRuntime(shippedRegistry());

        rewriteFirstMatchingLine("main-command-alias:", "  main-command-alias: \"eco2\"", 0);
        ReloadResult result = adapter.reloadWithRuntime(runtime);

        assertTrue(result.success(), "alias change must not fail the hot-appliable part: "
                + result.diagnostics());
        assertFalse(result.restartNotes().isEmpty(), "restart-only change must be reported");
        assertTrue(result.restartNotes().stream().anyMatch(n -> n.contains("eco2")),
                "note must name the deferred value: " + result.restartNotes());
    }

    @Test
    void concurrentReloadsStayConsistent() throws Exception {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE);
        adapter.load();
        RecordingRuntime runtime = new RecordingRuntime(shippedRegistry());
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ReloadResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(10, TimeUnit.SECONDS));
                    return adapter.reloadWithRuntime(runtime);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<ReloadResult> results = new ArrayList<>();
            for (Future<ReloadResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(threads, results.size());
            for (ReloadResult r : results) {
                assertTrue(r.success(), "identical concurrent reloads must succeed: " + r.diagnostics());
            }
            assertNotNull(adapter.getConfig("currencies"), "snapshot must stay readable");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void legacyReloadWithoutRuntimeStillValidatesCurrencies() throws IOException {
        ConfigLangAdapter adapter = new ConfigLangAdapter(plugin, Locale.TRADITIONAL_CHINESE);
        adapter.load();

        rewriteConfig("  token:", "  renamed:");
        // No runtime hook: structural currency changes cannot hot-apply, so the
        // transaction must fail instead of reporting success while ignoring them.
        ReloadResult result = adapter.reload();

        assertFalse(result.success(),
                "legacy reload must not report success when currencies changed: " + result.diagnostics());
    }
}
