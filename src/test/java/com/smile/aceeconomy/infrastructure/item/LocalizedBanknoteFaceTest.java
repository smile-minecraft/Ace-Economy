package com.smile.aceeconomy.infrastructure.item;

import com.smile.acelib.item.ItemIdentity;
import com.smile.aceeconomy.ports.BanknoteClaim;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Face contract for the v2 banknote: the minted item must be recognizable by its actual value and
 * currency, so the display name and lore are rendered from the registered {@code banknote.*} lang
 * keys with the claim's value / currency / issuer as safely-substituted vars — never a hardcoded
 * English-only face. A renderer failure degrades to the legacy plain face instead of failing the
 * mint (the balance has already left the account by the time a banknote is minted).
 */
class LocalizedBanknoteFaceTest {

    private static BanknoteClaim claim() {
        ItemIdentity id = new ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY,
                BanknoteClaim.V2_SCHEMA.major(), BanknoteClaim.V2_SCHEMA.minor());
        return new BanknoteClaim(id, BanknoteClaim.V2_SCHEMA, 100L,
                UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
                UUID.fromString("00000000-0000-0000-0000-0000000000b2"), "USD");
    }

    @Test
    void displayNameUsesNameKeyWithClaimVars() {
        List<String> calls = new ArrayList<>();
        LocalizedBanknoteFace face = new LocalizedBanknoteFace((key, vars) -> {
            calls.add(key + "=" + vars);
            return Component.text("f:" + key);
        });

        Component name = face.displayName(claim());

        assertEquals(Component.text("f:banknote.name"), name);
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).startsWith("banknote.name="), "the name must render from banknote.name");
        Map<?, ?> vars = varsOf(calls.get(0));
        assertEquals("100", vars.get("value"), "the face must show the actual note value");
        assertEquals("USD", vars.get("currency"), "the face must show the note currency");
        assertEquals("00000000-0000-0000-0000-0000000000a1", vars.get("issuer"),
                "the face must show the issuing account");
    }

    @Test
    void loreRendersValueIssuerClickInOrder() {
        List<String> calls = new ArrayList<>();
        LocalizedBanknoteFace face = new LocalizedBanknoteFace((key, vars) -> {
            calls.add(key + "=" + vars);
            return Component.text("f:" + key);
        });

        List<Component> lore = face.lore(claim());

        assertEquals(List.of(
                Component.text("f:banknote.lore-value"),
                Component.text("f:banknote.lore-issuer"),
                Component.text("f:banknote.lore-click")), lore);
        assertEquals(3, calls.size(), "each lore line is one lang key render");
        Map<?, ?> valueVars = varsOf(calls.get(0));
        assertEquals("banknote.lore-value", calls.get(0).substring(0, calls.get(0).indexOf('=')));
        assertEquals("100", valueVars.get("value"));
        assertEquals("USD", valueVars.get("currency"));
        assertEquals("banknote.lore-issuer", calls.get(1).substring(0, calls.get(1).indexOf('=')));
        assertEquals("00000000-0000-0000-0000-0000000000a1", varsOf(calls.get(1)).get("issuer"));
        assertEquals("banknote.lore-click", calls.get(2).substring(0, calls.get(2).indexOf('=')),
                "the redeem hint line renders from banknote.lore-click");
    }

    @Test
    void rendererFailureFallsBackToLegacyPlainFace() {
        LocalizedBanknoteFace face = new LocalizedBanknoteFace((key, vars) -> {
            throw new IllegalStateException("injected message outage");
        });

        Component name = face.displayName(claim());
        List<Component> lore = face.lore(claim());

        assertEquals(Component.text("Banknote 100"),
                name, "a display outage must degrade to the legacy plain face, never fail the mint");
        assertTrue(lore.isEmpty(), "a display outage leaves the legacy lore-less face");
    }

    @Test
    void currencyVarCarriesResolvedDisplayNameNotRawId() {
        List<String> calls = new ArrayList<>();
        LocalizedBanknoteFace face = new LocalizedBanknoteFace(
                (key, vars) -> {
                    calls.add(key + "=" + vars);
                    return Component.text("f:" + key);
                },
                currencyId -> "USD".equals(currencyId) ? "金幣" : null);

        face.displayName(claim());
        face.lore(claim());

        assertTrue(calls.stream().anyMatch(c -> c.startsWith("banknote.name=")),
                "the name line must render");
        for (String call : calls) {
            String key = call.substring(0, call.indexOf('='));
            if (key.equals("banknote.name") || key.equals("banknote.lore-value")) {
                assertEquals("金幣", varsOf(call).get("currency"),
                        key + " must show the resolved currency display name, not the id");
            }
        }
    }

    @Test
    void unknownCurrencyFallsBackToRawId() {
        List<String> calls = new ArrayList<>();
        LocalizedBanknoteFace face = new LocalizedBanknoteFace(
                (key, vars) -> {
                    calls.add(key + "=" + vars);
                    return Component.text("f:" + key);
                },
                currencyId -> null);

        face.displayName(claim());

        assertEquals("USD", varsOf(calls.get(0)).get("currency"),
                "an unresolvable currency must fall back to the raw claim id, never null or a render failure");
    }

    @Test
    void resolverFailureFallsBackToRawId() {
        List<String> calls = new ArrayList<>();
        LocalizedBanknoteFace face = new LocalizedBanknoteFace(
                (key, vars) -> {
                    calls.add(key + "=" + vars);
                    return Component.text("f:" + key);
                },
                currencyId -> {
                    throw new IllegalStateException("injected registry outage");
                });

        face.displayName(claim());
        face.lore(claim());

        for (String call : calls) {
            String key = call.substring(0, call.indexOf('='));
            if (key.equals("banknote.name") || key.equals("banknote.lore-value")) {
                assertEquals("USD", varsOf(call).get("currency"),
                        key + " must degrade to the raw id when the resolver fails, never fail the render");
            }
        }
    }

    private static Map<?, ?> varsOf(String call) {
        String vars = call.substring(call.indexOf('=') + 1);
        assertTrue(vars.startsWith("{") && vars.endsWith("}"), "recorded vars must be a map: " + call);
        return new java.util.LinkedHashMap<Object, Object>() {
            {
                // Minimal parser for Map.toString output of the small var maps used here.
                String body = vars.substring(1, vars.length() - 1);
                if (!body.isEmpty()) {
                    for (String entry : body.split(", ")) {
                        int eq = entry.indexOf('=');
                        put(entry.substring(0, eq), entry.substring(eq + 1));
                    }
                }
            }
        };
    }
}
