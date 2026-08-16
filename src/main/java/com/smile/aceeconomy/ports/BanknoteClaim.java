package com.smile.aceeconomy.ports;

import com.smile.acelib.item.ItemIdentity;
import com.smile.acelib.item.ItemSchemaVersion;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable decoded representation of a v2 banknote, carrying the real AceLib
 * {@link ItemIdentity} and {@link ItemSchemaVersion} records plus the gameplay payload.
 *
 * <p>The validator and the GUI use case operate on this value object, never on a raw
 * {@link org.bukkit.inventory.ItemStack}, so the security boundary (identity + schema +
 * issuer/value/nonce) is testable without a live server. The production
 * {@code V2BanknoteFactory} is the only place that converts between an {@code ItemStack}
 * and a {@code BanknoteClaim}.
 */
public final class BanknoteClaim {

    /** v2 banknote namespace. Distinct from any v1 / legacy namespace so old items are never recognised. */
    public static final String V2_NAMESPACE = "aceeconomy.v2";

    /** v2 banknote item key. */
    public static final String V2_KEY = "banknote";

    /** v2 banknote schema version. Bumped from the v1 baseline so stale schemas are rejected. */
    public static final ItemSchemaVersion V2_SCHEMA = new ItemSchemaVersion(2, 0);

    private final ItemIdentity identity;
    private final ItemSchemaVersion schema;
    private final long value;
    private final UUID issuer;
    private final UUID nonce;
    private final String currency;

    public BanknoteClaim(ItemIdentity identity, ItemSchemaVersion schema, long value,
                         UUID issuer, UUID nonce, String currency) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.value = value;
        this.issuer = issuer;
        this.nonce = nonce;
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    public ItemIdentity identity() {
        return identity;
    }

    public ItemSchemaVersion schema() {
        return schema;
    }

    public long value() {
        return value;
    }

    public UUID issuer() {
        return issuer;
    }

    public UUID nonce() {
        return nonce;
    }

    public String currency() {
        return currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BanknoteClaim)) {
            return false;
        }
        BanknoteClaim that = (BanknoteClaim) o;
        return value == that.value
                && identity.equals(that.identity)
                && schema.equals(that.schema)
                && Objects.equals(issuer, that.issuer)
                && Objects.equals(nonce, that.nonce)
                && currency.equals(that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, schema, value, issuer, nonce, currency);
    }

    @Override
    public String toString() {
        return "BanknoteClaim{ns=" + identity.namespace() + ", key=" + identity.key()
                + ", schema=" + schema + ", value=" + value
                + ", issuer=" + issuer + ", nonce=" + nonce + ", currency=" + currency + '}';
    }
}
