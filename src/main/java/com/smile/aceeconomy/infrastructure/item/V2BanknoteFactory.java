package com.smile.aceeconomy.infrastructure.item;

import com.smile.acelib.item.AceItemFactory;
import com.smile.acelib.item.ItemIdentity;
import com.smile.acelib.item.ItemSchemaVersion;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.BanknoteFactory;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * Production {@link BanknoteFactory} backed by AceLib's {@link AceItemFactory}. It is the single
 * place that converts between a Bukkit {@link ItemStack} and a {@link BanknoteClaim}, encoding the
 * v2 identity, schema, and typed gameplay tags (value / issuer / nonce / currency).
 *
 * <p>Construction calls {@link AceItemFactory#create(String)}, which requires a live server to
 * initialise AceLib's identity keys; this class is therefore instantiated by the production
 * composition root at server start, not in offline unit tests. Offline tests use a deterministic
 * fake that implements the same {@link BanknoteFactory} contract.
 */
public final class V2BanknoteFactory implements BanknoteFactory {

    private static final Material BANKNOTE_MATERIAL = Material.PAPER;

    private final AceItemFactory factory;

    public V2BanknoteFactory() {
        this.factory = AceItemFactory.create(BanknoteClaim.V2_NAMESPACE);
    }

    @Override
    public @NotNull Optional<ItemStack> mint(BanknoteClaim claim) {
        if (claim.value() <= 0) {
            return Optional.empty();
        }
        ItemIdentity identity = new ItemIdentity(
                BanknoteClaim.V2_NAMESPACE,
                BanknoteClaim.V2_KEY,
                BanknoteClaim.V2_SCHEMA.major(),
                BanknoteClaim.V2_SCHEMA.minor());
        AceItemFactory.ItemSpec spec = AceItemFactory.ItemSpec.builder()
                .material(BANKNOTE_MATERIAL)
                .amount(1)
                .identity(identity)
                .displayName("Banknote " + claim.value())
                .gameplayTag("value", Long.toString(claim.value()))
                .gameplayTag("issuer", claim.issuer().toString())
                .gameplayTag("nonce", claim.nonce().toString())
                .gameplayTag("currency", claim.currency())
                .build();
        return Optional.of(factory.create(spec));
    }

    @Override
    public @NotNull Optional<BanknoteClaim> decode(@NotNull ItemStack stack) {
        if (!factory.identify(stack)) {
            return Optional.empty();
        }
        Optional<ItemIdentity> idOpt = factory.readIdentity(stack);
        if (idOpt.isEmpty()) {
            return Optional.empty();
        }
        ItemIdentity id = idOpt.get();
        Optional<ItemSchemaVersion> schemaOpt = factory.readSchemaVersion(stack, BanknoteClaim.V2_KEY);
        if (schemaOpt.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> valueOpt = factory.readGameplayString(stack, "value");
        Optional<String> issuerOpt = factory.readGameplayString(stack, "issuer");
        Optional<String> nonceOpt = factory.readGameplayString(stack, "nonce");
        Optional<String> currencyOpt = factory.readGameplayString(stack, "currency");
        if (valueOpt.isEmpty() || issuerOpt.isEmpty() || nonceOpt.isEmpty() || currencyOpt.isEmpty()) {
            return Optional.empty();
        }
        final long value;
        final UUID issuer;
        final UUID nonce;
        try {
            value = Long.parseLong(valueOpt.get());
            issuer = UUID.fromString(issuerOpt.get());
            nonce = UUID.fromString(nonceOpt.get());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (value <= 0) {
            return Optional.empty();
        }
        return Optional.of(new BanknoteClaim(id, schemaOpt.get(), value, issuer, nonce, currencyOpt.get()));
    }
}
