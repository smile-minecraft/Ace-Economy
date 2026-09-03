package com.smile.aceeconomy.infrastructure.acelib;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract for the bank GUI layout parser: defaults reproduce the legacy
 * slot behaviour, and every malformed shape is rejected with a precise path.
 */
class BankGuiConfigParserTest {

    private static final Set<String> KNOWN = Set.of("dollar", "token");

    @Test
    void absentBlockFallsBackToLegacyDefaults() {
        BankGuiLayout layout = BankGuiConfigParser.parse(null, KNOWN);

        assertTrue(layout.enabled());
        assertEquals("gui.bank-title", layout.titleKey());
        assertEquals(27, layout.size());
        assertEquals(BankGuiLayout.ActionType.DEPOSIT,
                layout.actionForSlot(4).orElseThrow().type());
        assertEquals(BankGuiLayout.ActionType.WITHDRAW,
                layout.actionForSlot(11).orElseThrow().type());
        assertEquals(100L, layout.actionForSlot(11).orElseThrow().amount());
        assertEquals(BankGuiLayout.ActionType.WITHDRAW,
                layout.actionForSlot(13).orElseThrow().type());
        assertEquals(500L, layout.actionForSlot(13).orElseThrow().amount());
        assertEquals(BankGuiLayout.ActionType.CLOSE,
                layout.actionForSlot(15).orElseThrow().type());
        assertTrue(layout.actionForSlot(0).isEmpty());
    }

    @Test
    void invalidSizesAreRejected() {
        for (Object size : new Object[]{0, 7, 28, 63, "large", 27.5}) {
            Map<String, Object> raw = baseRaw();
            raw.put("size", size);
            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class,
                            () -> BankGuiConfigParser.parse(raw, KNOWN));
            assertTrue(failure.getMessage().contains("bank-gui.size"),
                    "expected size path, got: " + failure.getMessage());
        }
    }

    @Test
    void duplicateSlotsAreRejected() {
        Map<String, Object> raw = baseRaw();
        slot(raw, "close").put("slot", 4);
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> BankGuiConfigParser.parse(raw, KNOWN));
        assertTrue(failure.getMessage().contains("slot"),
                "expected slot path, got: " + failure.getMessage());
    }

    @Test
    void slotOutsideSizeIsRejected() {
        Map<String, Object> raw = baseRaw();
        raw.put("size", 9);
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> BankGuiConfigParser.parse(raw, KNOWN));
        assertTrue(failure.getMessage().contains("slot"),
                "expected slot path, got: " + failure.getMessage());
    }

    @Test
    void unknownActionTypeIsRejected() {
        Map<String, Object> raw = baseRaw();
        slot(raw, "deposit").put("type", "teleport");
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> BankGuiConfigParser.parse(raw, KNOWN));
        assertTrue(failure.getMessage().contains("bank-gui.actions.deposit.type"),
                "expected type path, got: " + failure.getMessage());
    }

    @Test
    void nonPositiveWithdrawAmountIsRejected() {
        for (Object amount : new Object[]{0, -50, "100"}) {
            Map<String, Object> raw = baseRaw();
            slot(raw, "withdraw100").put("amount", amount);
            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class,
                            () -> BankGuiConfigParser.parse(raw, KNOWN));
            assertTrue(failure.getMessage().contains("bank-gui.actions.withdraw100.amount"),
                    "expected amount path, got: " + failure.getMessage());
        }
    }

    @Test
    void unknownCurrencyIsRejected() {
        Map<String, Object> raw = baseRaw();
        slot(raw, "withdraw100").put("currency", "gem");
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> BankGuiConfigParser.parse(raw, KNOWN));
        assertTrue(failure.getMessage().contains("bank-gui.actions.withdraw100.currency"),
                "expected currency path, got: " + failure.getMessage());
    }

    @Test
    void amountOnNonWithdrawIsRejected() {
        Map<String, Object> raw = baseRaw();
        slot(raw, "deposit").put("amount", 10);
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> BankGuiConfigParser.parse(raw, KNOWN));
        assertTrue(failure.getMessage().contains("bank-gui.actions.deposit.amount"),
                "expected amount path, got: " + failure.getMessage());
    }

    @Test
    void illegalMaterialIsRejected() {
        Map<String, Object> raw = baseRaw();
        slot(raw, "deposit").put("material", "NOT_A_REAL_MATERIAL");
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> BankGuiConfigParser.parse(raw, KNOWN));
        assertTrue(failure.getMessage().contains("bank-gui.actions.deposit.material"),
                "expected material path, got: " + failure.getMessage());
    }

    @Test
    void blankTitleKeyIsRejected() {
        Map<String, Object> raw = baseRaw();
        raw.put("title-key", "  ");
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> BankGuiConfigParser.parse(raw, KNOWN));
        assertTrue(failure.getMessage().contains("bank-gui.title-key"),
                "expected title-key path, got: " + failure.getMessage());
    }

    // --- helpers -----------------------------------------------------------

    private static Map<String, Object> baseRaw() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("enabled", true);
        raw.put("title-key", "gui.bank-title");
        raw.put("size", 27);
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("deposit", action(4, "deposit", null, null, "CHEST",
                "gui.bank-deposit-name", List.of("gui.bank-deposit-lore")));
        actions.put("withdraw100", action(11, "withdraw", 100, "dollar", "PAPER",
                "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("withdraw500", action(13, "withdraw", 500, "dollar", "PAPER",
                "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("close", action(15, "close", null, null, "BARRIER",
                "gui.bank-close-name", List.of()));
        raw.put("actions", actions);
        return raw;
    }

    private static Map<String, Object> action(int slot, String type, Object amount,
                                              String currency, String material,
                                              String nameKey, List<String> loreKeys) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slot", slot);
        out.put("type", type);
        if (amount != null) {
            out.put("amount", amount);
        }
        if (currency != null) {
            out.put("currency", currency);
        }
        out.put("material", material);
        out.put("name-key", nameKey);
        out.put("lore-keys", loreKeys);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> slot(Map<String, Object> raw, String name) {
        return (Map<String, Object>) ((Map<String, Object>) raw.get("actions")).get(name);
    }
}
