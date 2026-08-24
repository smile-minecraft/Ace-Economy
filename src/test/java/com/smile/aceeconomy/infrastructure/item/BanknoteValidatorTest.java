package com.smile.aceeconomy.infrastructure.item;

import com.smile.acelib.item.ItemIdentity;
import com.smile.acelib.item.ItemSchemaVersion;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full reject-code matrix for {@link BanknoteValidator}. Every credit boundary is exercised so the
 * stable reason codes are locked: identity namespace / key / schema, schema version, non-positive
 * value, missing issuer, missing nonce, and replay (idempotency).
 */
class BanknoteValidatorTest {

    private static BanknoteClaim validClaim() {
        ItemIdentity id = new ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY,
                BanknoteClaim.V2_SCHEMA.major(), BanknoteClaim.V2_SCHEMA.minor());
        return new BanknoteClaim(id, BanknoteClaim.V2_SCHEMA, 100L, UUID.randomUUID(), UUID.randomUUID(), "USD");
    }

    @Test
    void validClaimAccepted() {
        BanknoteValidator v = new BanknoteValidator(new InMemoryIdempotencyGuard());
        assertTrue(v.validate(validClaim()).success(), "valid v2 claim must pass");
    }

    @Test
    void schemaVersionMismatchRejected() {
        BanknoteClaim c = validClaim();
        // identity is valid v2, but the claim's own schema record is stale
        BanknoteClaim wrong = new BanknoteClaim(c.identity(), new ItemSchemaVersion(1, 0),
                100L, c.issuer(), c.nonce(), "USD");
        BanknoteValidator v = new BanknoteValidator(new InMemoryIdempotencyGuard());
        ValidationResult r = v.validate(wrong);
        assertFalse(r.success());
        assertEquals("schema.version", r.reasonCode());
    }

    @Test
    void nonpositiveValueRejected() {
        BanknoteClaim c = validClaim();
        BanknoteClaim zero = new BanknoteClaim(c.identity(), c.schema(), 0L, c.issuer(), c.nonce(), "USD");
        BanknoteClaim negative = new BanknoteClaim(c.identity(), c.schema(), -10L, c.issuer(), c.nonce(), "USD");
        BanknoteValidator v = new BanknoteValidator(new InMemoryIdempotencyGuard());
        assertEquals("value.nonpositive", v.validate(zero).reasonCode());
        assertEquals("value.nonpositive", v.validate(negative).reasonCode());
    }

    @Test
    void missingIssuerRejected() {
        BanknoteClaim c = validClaim();
        BanknoteClaim noIssuer = new BanknoteClaim(c.identity(), c.schema(), 100L, null, c.nonce(), "USD");
        BanknoteValidator v = new BanknoteValidator(new InMemoryIdempotencyGuard());
        ValidationResult r = v.validate(noIssuer);
        assertFalse(r.success());
        assertEquals("issuer.missing", r.reasonCode());
    }

    @Test
    void missingNonceRejected() {
        BanknoteClaim c = validClaim();
        BanknoteClaim noNonce = new BanknoteClaim(c.identity(), c.schema(), 100L, c.issuer(), null, "USD");
        BanknoteValidator v = new BanknoteValidator(new InMemoryIdempotencyGuard());
        ValidationResult r = v.validate(noNonce);
        assertFalse(r.success());
        assertEquals("nonce.missing", r.reasonCode());
    }

    @Test
    void replayDetectedWhenNoncePreConsumed() {
        BanknoteClaim c = validClaim();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        guard.consume(c.nonce()); // simulate a prior redemption of this nonce
        BanknoteValidator v = new BanknoteValidator(guard);
        ValidationResult r = v.validate(c);
        assertFalse(r.success());
        assertEquals("replay.detected", r.reasonCode());
    }

    @Test
    void sameNonceRejectedOnSecondValidation() {
        BanknoteClaim c = validClaim();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BanknoteValidator v = new BanknoteValidator(guard);
        assertTrue(v.validate(c).success(), "first validation must accept");
        ValidationResult second = v.validate(c);
        assertFalse(second.success(), "second validation of same nonce is a replay");
        assertEquals("replay.detected", second.reasonCode());
    }

    @Test
    void validateStructureAcceptsWithoutConsumingTheNonce() {
        BanknoteClaim c = validClaim();
        InMemoryIdempotencyGuard guard = new InMemoryIdempotencyGuard();
        BanknoteValidator v = new BanknoteValidator(guard);

        ValidationResult structural = v.validateStructure(c);

        assertTrue(structural.success(), "structural checks must accept a valid claim");
        assertFalse(guard.isConsumed(c.nonce()),
                "structure-only validation must leave the nonce unconsumed");
        // The full validate() still consumes, so the two entry points compose correctly.
        assertTrue(v.validate(c).success());
        assertTrue(guard.isConsumed(c.nonce()));
    }

    @Test
    void validateStructureReturnsSameReasonCodesAsValidate() {
        BanknoteClaim c = validClaim();
        BanknoteClaim wrong = new BanknoteClaim(c.identity(), new ItemSchemaVersion(1, 0),
                100L, c.issuer(), c.nonce(), "USD");
        BanknoteValidator v = new BanknoteValidator(new InMemoryIdempotencyGuard());
        assertEquals("schema.version", v.validateStructure(wrong).reasonCode());
    }
}
