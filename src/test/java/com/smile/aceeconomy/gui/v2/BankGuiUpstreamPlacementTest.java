package com.smile.aceeconomy.gui.v2;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upstream placement boundary: AceLib 1.5.0 {@code GuiServiceImpl#placeButtonIcons}
 * clones each button icon and writes it with {@code Inventory#setItem}, catching
 * {@code Throwable} per slot and skipping only that slot (WARNING log, open still
 * succeeds). The consumer pre-build guarantee therefore ends at handover: an
 * assembly failure refuses the open, but a placement-time {@code clone()} or
 * {@code setItem()} failure can still leave a single missing button.
 *
 * <p>Both tests below inject exactly one failing slot and prove the neighbour
 * slot is still placed and no exception escapes.
 */
class BankGuiUpstreamPlacementTest {

    private static Method placeMethod() throws Exception {
        Method m = Class.forName("com.smile.acelib.gui.GuiServiceImpl")
                .getDeclaredMethod("placeButtonIcons", Inventory.class, Map.class);
        m.setAccessible(true);
        return m;
    }

    private static Inventory inventory27() {
        Inventory inv = Mockito.mock(Inventory.class);
        Mockito.when(inv.getSize()).thenReturn(27);
        return inv;
    }

    @Test
    void cloneFailureSkipsOnlyThatSlot() throws Exception {
        Inventory inv = inventory27();
        ItemStack good = Mockito.mock(ItemStack.class);
        ItemStack goodClone = Mockito.mock(ItemStack.class);
        Mockito.when(good.clone()).thenReturn(goodClone);
        ItemStack bad = Mockito.mock(ItemStack.class);
        Mockito.when(bad.clone()).thenThrow(new RuntimeException("icon clone boom"));

        Map<Integer, ItemStack> icons = new LinkedHashMap<>();
        icons.put(4, good);
        icons.put(11, bad);

        assertDoesNotThrow(() -> placeMethod().invoke(null, inv, icons),
                "a single icon clone failure must not fail the whole placement");

        Mockito.verify(inv).setItem(Mockito.eq(4), Mockito.eq(goodClone));
        Mockito.verify(inv, Mockito.never()).setItem(Mockito.eq(11), Mockito.any());
        assertTrue(true, "neighbour slot placement proves per-slot isolation");
    }

    @Test
    void setItemFailureSkipsOnlyThatSlot() throws Exception {
        Inventory inv = inventory27();
        ItemStack first = Mockito.mock(ItemStack.class);
        ItemStack firstClone = Mockito.mock(ItemStack.class);
        Mockito.when(first.clone()).thenReturn(firstClone);
        ItemStack second = Mockito.mock(ItemStack.class);
        ItemStack secondClone = Mockito.mock(ItemStack.class);
        Mockito.when(second.clone()).thenReturn(secondClone);
        Mockito.doThrow(new RuntimeException("setItem boom"))
                .when(inv).setItem(Mockito.eq(4), Mockito.any(ItemStack.class));

        Map<Integer, ItemStack> icons = new LinkedHashMap<>();
        icons.put(4, first);
        icons.put(11, second);

        assertDoesNotThrow(() -> placeMethod().invoke(null, inv, icons),
                "a single setItem failure must not fail the whole placement");

        Mockito.verify(inv).setItem(Mockito.eq(4), Mockito.eq(firstClone));
        Mockito.verify(inv).setItem(Mockito.eq(11), Mockito.eq(secondClone));
    }
}
