package com.smile.aceeconomy.infrastructure.item;

import com.smile.acelib.item.ItemIdentity;
import com.smile.acelib.item.ItemSchemaVersion;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity / schema / amount matrix for the v2 banknote. A v2 banknote is recognised only by its
 * distinct namespace, key and schema version; legacy / wrong-namespace / wrong-key / stale-schema
 * items are rejected. The {@link FakeBanknoteFactory} exercises the same {@link com.smile.aceeconomy.ports.BanknoteFactory}
 * contract the production {@link V2BanknoteFactory} implements against AceLib.
 */
class BanknoteIdentitySchemaTest {

    private static BanknoteClaim validClaim() {
        ItemIdentity id = new ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY,
                BanknoteClaim.V2_SCHEMA.major(), BanknoteClaim.V2_SCHEMA.minor());
        return new BanknoteClaim(id, BanknoteClaim.V2_SCHEMA, 100L, UUID.randomUUID(), UUID.randomUUID(), "USD");
    }

    @Test
    void v2ConstantsAreDistinctFromLegacy() {
        assertEquals("aceeconomy.v2", BanknoteClaim.V2_NAMESPACE);
        assertEquals("banknote", BanknoteClaim.V2_KEY);
        assertEquals(2, BanknoteClaim.V2_SCHEMA.major());
        assertEquals(0, BanknoteClaim.V2_SCHEMA.minor());
        // legacy v1 banknotes used the plugin namespace with PDC keys, never the v2 namespace
        assertNotEquals("aceeconomy", BanknoteClaim.V2_NAMESPACE);
    }

    @Test
    void validV2ClaimValidates() {
        BanknoteValidator v = new BanknoteValidator(new InMemoryIdempotencyGuard());
        ValidationResult r = v.validate(validClaim());
        assertTrue(r.success(), "valid v2 claim must pass");
    }

    @Test
    void legacyNamespaceRejected() {
        ItemIdentity id = new ItemIdentity("aceeconomy", "banknote", 1, 0);
        BanknoteClaim claim = new BanknoteClaim(id, new ItemSchemaVersion(1, 0), 100L,
                UUID.randomUUID(), UUID.randomUUID(), "USD");
        BanknoteValidator v = new BanknoteValidator(new InMemoryIdempotencyGuard());
        ValidationResult r = v.validate(claim);
        assertFalse(r.success());
        assertEquals("identity.namespace", r.reasonCode());
    }

    @Test
    void wrongKeyRejected() {
        ItemIdentity id = new ItemIdentity(BanknoteClaim.V2_NAMESPACE, "coin", 2, 0);
        BanknoteClaim claim = new BanknoteClaim(id, BanknoteClaim.V2_SCHEMA, 100L,
                UUID.randomUUID(), UUID.randomUUID(), "USD");
        BanknoteValidator v = new BanknoteValidator(new InMemoryIdempotencyGuard());
        ValidationResult r = v.validate(claim);
        assertFalse(r.success());
        assertEquals("identity.key", r.reasonCode());
    }

    @Test
    void staleIdentitySchemaRejected() {
        ItemIdentity id = new ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY, 1, 0);
        BanknoteClaim claim = new BanknoteClaim(id, new ItemSchemaVersion(1, 0), 100L,
                UUID.randomUUID(), UUID.randomUUID(), "USD");
        BanknoteValidator v = new BanknoteValidator(new InMemoryIdempotencyGuard());
        ValidationResult r = v.validate(claim);
        assertFalse(r.success());
        assertEquals("identity.schema", r.reasonCode());
    }

    @Test
    void factoryMintDecodeRoundTrip() {
        FakeBanknoteFactory f = new FakeBanknoteFactory();
        BanknoteClaim claim = validClaim();
        Optional<ItemStack> minted = f.mint(claim);
        assertTrue(minted.isPresent());
        assertTrue(f.wasMinted(minted.get()));
        Optional<BanknoteClaim> decoded = f.decode(minted.get());
        assertTrue(decoded.isPresent());
        assertEquals(claim, decoded.get());
    }

    @Test
    void counterfeitItemDecodesEmpty() {
        FakeBanknoteFactory f = new FakeBanknoteFactory();
        ItemStack counterfeit = Mockito.mock(ItemStack.class);
        assertFalse(f.decode(counterfeit).isPresent(), "plain item must not decode as a banknote");
    }

    @Test
    void negativeValueNotMinted() {
        FakeBanknoteFactory f = new FakeBanknoteFactory();
        BanknoteClaim claim = validClaim();
        BanknoteClaim negative = new BanknoteClaim(claim.identity(), claim.schema(), -5L,
                claim.issuer(), claim.nonce(), claim.currency());
        assertFalse(f.mint(negative).isPresent());
    }
}
