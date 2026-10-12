package com.smile.aceeconomy.commands.v2;

import com.smile.aceeconomy.domain.AdminAudit;

import java.util.Optional;
import java.util.UUID;

/**
 * Permission and validation policy for the freeze and read-only commands.
 *
 * <p>Freeze, unfreeze, and read-only use independent permissions
 * ({@code aceeconomy.admin.freeze}, {@code aceeconomy.admin.unfreeze},
 * {@code aceeconomy.admin.readonly}), all listed under the
 * {@code aceeconomy.admin} wildcard. A sender without the matching
 * permission is refused before any state is read. Holding one of them never
 * implies another.
 *
 * <p>An unknown target account is refused explicitly; a repeated freeze or
 * unfreeze is idempotent and leaves state and version unchanged. Reason
 * handling reuses {@link AdminAudit#validateReason}: omission becomes the
 * default, blank, multi-word, over-long, and secret-looking values are
 * refused without echoing the raw token.
 */
public final class SecurityCommandPolicy {

    /** Independent permission for freezing one account. */
    public static final String FREEZE_PERMISSION = "aceeconomy.admin.freeze";

    /** Independent permission for unfreezing one account; never implied by freeze. */
    public static final String UNFREEZE_PERMISSION = "aceeconomy.admin.unfreeze";

    /** Independent permission for switching global read-only mode. */
    public static final String READONLY_PERMISSION = "aceeconomy.admin.readonly";

    /** Admin wildcard covering every administrative node, including the three above. */
    public static final String ADMIN_PERMISSION = "aceeconomy.admin";

    private SecurityCommandPolicy() {
    }

    /** True when the sender holds the freeze node (or the admin wildcard). */
    public static boolean hasFreezePermission(java.util.function.Predicate<String> hasPermission) {
        return hasPermission.test(FREEZE_PERMISSION) || hasPermission.test(ADMIN_PERMISSION);
    }

    /** True when the sender holds the unfreeze node (or the admin wildcard). */
    public static boolean hasUnfreezePermission(java.util.function.Predicate<String> hasPermission) {
        return hasPermission.test(UNFREEZE_PERMISSION) || hasPermission.test(ADMIN_PERMISSION);
    }

    /** True when the sender holds the read-only node (or the admin wildcard). */
    public static boolean hasReadonlyPermission(java.util.function.Predicate<String> hasPermission) {
        return hasPermission.test(READONLY_PERMISSION) || hasPermission.test(ADMIN_PERMISSION);
    }

    /** Explicit refusal for an unknown target; empty when the account is known. */
    public static Optional<String> checkTargetExists(boolean exists, String playerName) {
        if (exists) {
            return Optional.empty();
        }
        return Optional.of("unknown player: " + playerName);
    }

    /**
     * Shared reason check. Omission (null or empty) falls back to the default
     * and passes; anything else travels through the shared admin policy. The
     * refusal never echoes the raw token.
     */
    public static Optional<String> checkReason(String rawReason) {
        if (rawReason == null || rawReason.isEmpty()) {
            return Optional.empty();
        }
        try {
            AdminAudit.validateReason(rawReason);
        } catch (IllegalArgumentException refused) {
            return Optional.of("reason refused");
        }
        return Optional.empty();
    }

    /** True when the account already holds the requested state (idempotent repeat). */
    public static boolean isIdempotentRepeat(boolean alreadyInState) {
        return alreadyInState;
    }

    /** Audit for one control command, reusing the shared sender-derived policy. */
    public static AdminAudit auditFor(boolean isPlayer, UUID operatorId, String operatorName,
                                      String rawReason) {
        String checked = rawReason == null || rawReason.isEmpty() ? null : rawReason;
        String reason;
        try {
            reason = AdminAudit.validateReason(checked);
        } catch (IllegalArgumentException rejected) {
            throw new IllegalArgumentException("reason refused");
        }
        if (isPlayer) {
            return AdminAudit.player(operatorId, operatorName, reason);
        }
        return AdminAudit.console(reason);
    }
}
