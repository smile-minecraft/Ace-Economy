package com.smile.aceeconomy.infrastructure.item;

import com.smile.acelib.item.ItemIdentity;
import com.smile.acelib.item.ItemSchemaVersion;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.IdempotencyGuard;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure validation of a decoded v2 banknote claim. Every credit (deposit / redeem) must pass through
 * this boundary before any balance mutation. The checks are order-independent of I/O and cover:
 *
 * <ul>
 *   <li>identity namespace must equal the v2 namespace (rejects v1 / other plugins),</li>
 *   <li>identity key must equal {@code banknote},</li>
 *   <li>schema version must equal the v2 schema (rejects stale / migrated schemas),</li>
 *   <li>value must be strictly positive (rejects zero / negative / malformed),</li>
 *   <li>issuer and nonce must be present (a banknote without a nonce cannot be replay-protected),</li>
 *   <li>the nonce must not already be consumed (replay protection via {@link IdempotencyGuard}).</li>
 * </ul>
 *
 * <p>No Bukkit / AceLib runtime call happens here, so the boundary is deterministically testable.
 */
public final class BanknoteValidator {

    private final IdempotencyGuard idempotency;

    public BanknoteValidator(IdempotencyGuard idempotency) {
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    }

    public ValidationResult validate(BanknoteClaim claim) {
        Objects.requireNonNull(claim, "claim");

        ItemIdentity identity = claim.identity();
        if (!BanknoteClaim.V2_NAMESPACE.equals(identity.namespace())) {
            return ValidationResult.rejected("identity.namespace");
        }
        if (!BanknoteClaim.V2_KEY.equals(identity.key())) {
            return ValidationResult.rejected("identity.key");
        }
        ItemSchemaVersion expected = BanknoteClaim.V2_SCHEMA;
        if (identity.major() != expected.major() || identity.minor() != expected.minor()) {
            return ValidationResult.rejected("identity.schema");
        }
        if (claim.schema().major() != expected.major() || claim.schema().minor() != expected.minor()) {
            return ValidationResult.rejected("schema.version");
        }
        if (claim.value() <= 0) {
            return ValidationResult.rejected("value.nonpositive");
        }
        UUID issuer = claim.issuer();
        if (issuer == null) {
            return ValidationResult.rejected("issuer.missing");
        }
        UUID nonce = claim.nonce();
        if (nonce == null) {
            return ValidationResult.rejected("nonce.missing");
        }
        if (!idempotency.consume(nonce)) {
            return ValidationResult.rejected("replay.detected");
        }
        return ValidationResult.success(claim);
    }
}
