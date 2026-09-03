package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Classification contract for currency changes seen by {@code /aceeco reload}.
 *
 * <p>Only display metadata (name / symbol) may hot-apply; every structural change
 * (added / removed / renamed id, scale, default) must be reported as
 * restart-required so reload never leaves a half-applied runtime behind.
 */
class CurrencyReloadPlanTest {

    private static CurrencyRegistry live() {
        return CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 2, true),
                Currency.define("token", "活動代幣", "ⓒ", 0, false)));
    }

    private static Map<String, Object> entry(String name, String symbol, int scale, boolean def) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name);
        fields.put("symbol", symbol);
        fields.put("scale", scale);
        fields.put("default", def);
        return fields;
    }

    private static Map<String, Object> candidateDollarToken(
            String dollarName, String dollarSymbol, int dollarScale, boolean dollarDefault,
            String tokenName, String tokenSymbol, int tokenScale, boolean tokenDefault) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("dollar", entry(dollarName, dollarSymbol, dollarScale, dollarDefault));
        raw.put("token", entry(tokenName, tokenSymbol, tokenScale, tokenDefault));
        return raw;
    }

    @Test
    void identicalRegistriesAreIdentical() {
        CurrencyReloadPlan.Classification plan = CurrencyReloadPlan.classify(
                live(), candidateDollarToken("金幣", "$", 2, true, "活動代幣", "ⓒ", 0, false));
        assertEquals(CurrencyReloadPlan.Disposition.IDENTICAL, plan.disposition());
        assertTrue(plan.details().isEmpty());
        assertNotNull(plan.candidate());
    }

    @Test
    void symbolOnlyChangeIsDisplayOnly() {
        CurrencyReloadPlan.Classification plan = CurrencyReloadPlan.classify(
                live(), candidateDollarToken("金幣", "€", 2, true, "活動代幣", "ⓒ", 0, false));
        assertEquals(CurrencyReloadPlan.Disposition.DISPLAY_ONLY, plan.disposition());
        assertEquals("€", plan.candidate().get("dollar").symbol());
        assertTrue(plan.details().stream().anyMatch(d -> d.contains("dollar")),
                "details must name the changed currency: " + plan.details());
    }

    @Test
    void nameOnlyChangeIsDisplayOnly() {
        CurrencyReloadPlan.Classification plan = CurrencyReloadPlan.classify(
                live(), candidateDollarToken("美金", "$", 2, true, "活動代幣", "ⓒ", 0, false));
        assertEquals(CurrencyReloadPlan.Disposition.DISPLAY_ONLY, plan.disposition());
    }

    @Test
    void addedCurrencyRequiresRestart() {
        Map<String, Object> raw = candidateDollarToken("金幣", "$", 2, true, "活動代幣", "ⓒ", 0, false);
        raw.put("gem", entry("寶石", "G", 1, false));
        CurrencyReloadPlan.Classification plan = CurrencyReloadPlan.classify(live(), raw);
        assertEquals(CurrencyReloadPlan.Disposition.ADDED, plan.disposition());
        assertTrue(plan.details().stream().anyMatch(d -> d.contains("gem")),
                "details must name the added currency: " + plan.details());
        assertTrue(plan.summary().contains("restart"),
                "summary must tell the operator a restart is required: " + plan.summary());
    }

    @Test
    void removedCurrencyIsDangerous() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("dollar", entry("金幣", "$", 2, true));
        CurrencyReloadPlan.Classification plan = CurrencyReloadPlan.classify(live(), raw);
        assertEquals(CurrencyReloadPlan.Disposition.DANGEROUS, plan.disposition());
        assertTrue(plan.details().stream().anyMatch(d -> d.contains("token")),
                "details must name the removed currency: " + plan.details());
    }

    @Test
    void scaleChangeIsDangerous() {
        CurrencyReloadPlan.Classification plan = CurrencyReloadPlan.classify(
                live(), candidateDollarToken("金幣", "$", 3, true, "活動代幣", "ⓒ", 0, false));
        assertEquals(CurrencyReloadPlan.Disposition.DANGEROUS, plan.disposition());
        assertTrue(plan.details().stream().anyMatch(d -> d.contains("scale")),
                "details must explain the scale change: " + plan.details());
    }

    @Test
    void defaultChangeIsDangerous() {
        CurrencyReloadPlan.Classification plan = CurrencyReloadPlan.classify(
                live(), candidateDollarToken("金幣", "$", 2, false, "活動代幣", "ⓒ", 0, true));
        assertEquals(CurrencyReloadPlan.Disposition.DANGEROUS, plan.disposition());
        assertTrue(plan.details().stream().anyMatch(d -> d.contains("default")),
                "details must explain the default change: " + plan.details());
    }

    @Test
    void invalidCandidateIsInvalid() {
        Map<String, Object> raw = candidateDollarToken("金幣", "$", 2, true, "活動代幣", "ⓒ", 0, true);
        CurrencyReloadPlan.Classification plan = CurrencyReloadPlan.classify(live(), raw);
        assertEquals(CurrencyReloadPlan.Disposition.INVALID, plan.disposition());
        assertFalse(plan.details().isEmpty());
        assertNull(plan.candidate());
    }

    @Test
    void displayOnlyGuardAcceptsDisplayChange() {
        CurrencyRegistry candidate = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "€", 2, true),
                Currency.define("token", "活動代幣", "ⓒ", 0, false)));
        assertDoesNotThrow(() -> CurrencyReloadPlan.requireDisplayOnlyChange(live(), candidate));
    }

    @Test
    void displayOnlyGuardRejectsStructuralChange() {
        CurrencyRegistry candidate = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 3, true),
                Currency.define("token", "活動代幣", "ⓒ", 0, false)));
        assertThrows(IllegalArgumentException.class,
                () -> CurrencyReloadPlan.requireDisplayOnlyChange(live(), candidate));
    }
}
