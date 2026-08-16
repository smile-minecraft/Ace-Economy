package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.WithdrawResult;

import org.bukkit.inventory.ItemStack;
import org.mockito.Mockito;

import java.util.UUID;

/**
 * Configurable fake of {@link BankGuiUseCase} for GUI contract tests. It records the withdraw
 * arguments and returns a programmed outcome (success / inventory-full / rejected) so the controller
 * and inventory-full policy can be exercised deterministically.
 */
public final class StubBankGuiUseCase implements BankGuiUseCase {

    public enum Mode { SUCCESS, INVENTORY_FULL, REJECTED }

    public Mode mode = Mode.SUCCESS;
    public UUID lastPlayer;
    public long lastAmount;
    public int withdrawCalls;

    @Override
    public WithdrawResult withdraw(UUID playerUuid, long amount) {
        lastPlayer = playerUuid;
        lastAmount = amount;
        withdrawCalls++;
        switch (mode) {
            case INVENTORY_FULL:
                return WithdrawResult.inventoryFull();
            case REJECTED:
                return WithdrawResult.rejected("business.rejected");
            default:
                return WithdrawResult.success(Mockito.mock(ItemStack.class));
        }
    }
}
