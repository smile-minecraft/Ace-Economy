package com.smile.aceeconomy.infrastructure.item;

import com.smile.acelib.item.AceItemFactory;
import com.smile.acelib.item.ItemIdentity;
import com.smile.acelib.item.ItemMigration;
import com.smile.acelib.item.ItemMigrationChain;
import com.smile.acelib.item.ItemMigrationContext;
import com.smile.acelib.item.ItemSchemaVersion;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.BanknoteFactory;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Production {@link BanknoteFactory} backed by AceLib's {@link AceItemFactory}. It is the single
 * place that converts between a Bukkit {@link ItemStack} and a {@link BanknoteClaim}, encoding the
 * v2 identity, schema, and typed gameplay tags (value / issuer / nonce / currency).
 *
 * <p>{@link #mint} calls {@link AceItemFactory#create(AceItemFactory.ItemSpec)}, which materialises
 * a real {@link ItemStack} and therefore needs a live server; production instantiates this class
 * from the composition root at server start. {@link #decode} only reads item metadata, so the
 * identity / schema contract is exercised offline against a mocked stack as well.
 *
 * <p>The minted face (display name and lore) comes from {@link LocalizedBanknoteFace}, so the
 * note shows its actual value and currency in the active locale. The face is display-only:
 * {@link #decode} recognises banknotes strictly by identity, schema and gameplay tags, so notes
 * minted with any older face remain redeemable.
 */
public final class V2BanknoteFactory implements BanknoteFactory {

    private static final Material BANKNOTE_MATERIAL = Material.PAPER;

    /**
     * Stamps a freshly minted note with the v2 schema. AceLib's {@link AceItemFactory.ItemSpec}
     * carries the item identity but {@link AceItemFactory#create} always writes {@code _version}
     * {@code 1.0} regardless of that identity, so the banknote has to advance its own schema to
     * match the identity it recognises in {@link #decode}. The bump goes through AceLib's public
     * migration API so the PDC key layout stays owned by the library. The chain is stateless after
     * construction, so one shared instance is safe.
     */
    private static final ItemMigrationChain V2_SCHEMA_STAMP = new ItemMigrationChain()
            .add(new ItemMigration() {
                @Override
                public ItemSchemaVersion fromVersion() {
                    return ItemSchemaVersion.V1_0;
                }

                @Override
                public ItemSchemaVersion toVersion() {
                    return BanknoteClaim.V2_SCHEMA;
                }

                @Override
                public void migrate(ItemMigrationContext context) {
                    context.writeVersion(BanknoteClaim.V2_SCHEMA);
                }
            });

    private final AceItemFactory factory;
    private final LocalizedBanknoteFace face;

    public V2BanknoteFactory(@NotNull LocalizedBanknoteFace face) {
        this.factory = AceItemFactory.create(BanknoteClaim.V2_NAMESPACE);
        this.face = Objects.requireNonNull(face, "face");
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
                .displayName(face.displayName(claim))
                .lore(face.lore(claim))
                .gameplayTag("value", Long.toString(claim.value()))
                .gameplayTag("issuer", claim.issuer().toString())
                .gameplayTag("nonce", claim.nonce().toString())
                .gameplayTag("currency", claim.currency())
                .build();
        ItemStack note = factory.create(spec);
        // AceLib stamps _version=1.0 on every create; advance it to the v2 schema so the note
        // decodes with the same schema version the validator requires.
        factory.migrate(note, BanknoteClaim.V2_SCHEMA, V2_SCHEMA_STAMP);
        return Optional.of(note);
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
        // The schema is stamped under the factory namespace (aceeconomy.v2), not the item key.
        Optional<ItemSchemaVersion> schemaOpt = factory.readSchemaVersion(stack, BanknoteClaim.V2_NAMESPACE);
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
