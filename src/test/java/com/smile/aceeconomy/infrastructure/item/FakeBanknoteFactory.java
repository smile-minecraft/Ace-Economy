package com.smile.aceeconomy.infrastructure.item;

import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.BanknoteFactory;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.mockito.Mockito;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic fake of {@link BanknoteFactory} for offline tests. Real {@link ItemStack} instances
 * cannot be constructed without a live server, so this fake returns Mockito mocks and stores the
 * decoded claim in an identity map. It exercises the same {@link BanknoteFactory} contract the
 * production {@code V2BanknoteFactory} implements against AceLib's {@code AceItemFactory}.
 */
public final class FakeBanknoteFactory implements BanknoteFactory {

    private final Map<ItemStack, BanknoteClaim> store = new IdentityHashMap<>();

    @Override
    public @NotNull Optional<ItemStack> mint(@NotNull BanknoteClaim claim) {
        if (claim.value() <= 0) {
            return Optional.empty();
        }
        ItemStack stack = Mockito.mock(ItemStack.class);
        store.put(stack, claim);
        return Optional.of(stack);
    }

    @Override
    public @NotNull Optional<BanknoteClaim> decode(@NotNull ItemStack stack) {
        return Optional.ofNullable(store.get(stack));
    }

    public boolean wasMinted(@NotNull ItemStack stack) {
        return store.containsKey(stack);
    }
}
