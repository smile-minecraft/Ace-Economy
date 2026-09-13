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

    /** When true, the next mint refuses (simulates an item factory outage). */
    public boolean failNextMint;

    /**
     * When non-null, the next mint throws this instead of returning — a
     * {@link RuntimeException} simulates an AceLib item/PDC failure, an {@link Error}
     * a broken runtime. Cleared after one use, mirroring {@link #failNextMint}.
     */
    public Throwable throwNextMint;

    private final Map<ItemStack, BanknoteClaim> store = new IdentityHashMap<>();

    @Override
    public @NotNull Optional<ItemStack> mint(@NotNull BanknoteClaim claim) {
        if (throwNextMint != null) {
            Throwable toThrow = throwNextMint;
            throwNextMint = null;
            if (toThrow instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (toThrow instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("only unchecked failures can be simulated", toThrow);
        }
        if (claim.value() <= 0) {
            return Optional.empty();
        }
        if (failNextMint) {
            failNextMint = false;
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

    /**
     * Plant an arbitrary claim for {@code stack} so tests can feed malformed or hostile claims
     * (wrong namespace, stale schema, missing nonce, ...) through {@link #decode} exactly like
     * a crafted item would arrive in production.
     */
    public void register(@NotNull ItemStack stack, @NotNull BanknoteClaim claim) {
        store.put(stack, claim);
    }

    public boolean wasMinted(@NotNull ItemStack stack) {
        return store.containsKey(stack);
    }

    /** All claims minted by this fake so far, in insertion order. */
    public java.util.List<BanknoteClaim> minted() {
        return java.util.List.copyOf(store.values());
    }
}
