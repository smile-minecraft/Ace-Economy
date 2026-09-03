package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.WithdrawResult;

import org.bukkit.inventory.ItemStack;
import org.mockito.Mockito;

import java.util.UUID;

/**
 * Configurable fake of {@link BankGuiUseCase} for GUI contract tests. It records the withdraw and
 * deposit arguments and returns programmed outcomes (success / inventory-full / rejected) so the
 * controller and inventory policies can be exercised deterministically.
 */
public final class StubBankGuiUseCase implements BankGuiUseCase {

    public enum Mode { SUCCESS, INVENTORY_FULL, REJECTED }

    public enum DepositMode { SUCCESS, REJECTED }

    public Mode mode = Mode.SUCCESS;
    public UUID lastPlayer;
    public long lastAmount;
    public int withdrawCalls;

    public DepositMode depositMode = DepositMode.SUCCESS;
    public String depositReason = "business.rejected";
    /** Actually-credited value, deliberately settable apart from the note face value in tests. */
    public long depositValue = 100L;
    public UUID lastDepositPlayer;
    public ItemStack lastDepositItem;
    public int depositCalls;

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

    @Override
    public DepositResult deposit(UUID playerUuid, ItemStack heldItem) {
        lastDepositPlayer = playerUuid;
        lastDepositItem = heldItem;
        depositCalls++;
        if (depositMode == DepositMode.REJECTED) {
            return DepositResult.rejected(depositReason);
        }
        return DepositResult.success(depositValue, "dollar");
    }
}
