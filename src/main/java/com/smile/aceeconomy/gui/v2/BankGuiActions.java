package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Bridges a validated {@link BankGuiLayout} to the slot resolver consumed by
 * {@link V2BankGuiSession}. Unconfigured slots resolve to {@link BankGuiAction#none()}.
 */
public final class BankGuiActions {

    private BankGuiActions() {
    }

    public static @NotNull Function<Integer, BankGuiAction> resolver(
            @NotNull BankGuiLayout layout) {
        Objects.requireNonNull(layout, "layout");
        Map<Integer, BankGuiAction> bySlot = new HashMap<>();
        for (BankGuiLayout.SlotConfig slot : layout.actions().values()) {
            BankGuiAction action = switch (slot.type()) {
                case DEPOSIT -> BankGuiAction.deposit();
                case WITHDRAW -> BankGuiAction.withdraw(slot.amount(), slot.currencyId());
                case CLOSE -> BankGuiAction.close();
                case NONE -> BankGuiAction.none();
            };
            bySlot.put(slot.slot(), action);
        }
        Map<Integer, BankGuiAction> sealed = Map.copyOf(bySlot);
        return slot -> sealed.getOrDefault(slot, BankGuiAction.none());
    }
}
