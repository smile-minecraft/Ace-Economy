package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.domain.AdminAudit;

/**
 * Derives the {@link AdminAudit} for an administrative command from its sender.
 *
 * <p>A player sender contributes the UUID and the display name seen at command
 * time; any other sender is recorded under the fixed console marker. The raw
 * reason token travels through {@link AdminAudit#validateReason(String)} so a
 * missing reason becomes the default while blank, over-long or secret-looking
 * values are rejected. No command argument can override the operator identity.
 */
public final class AdminAuditSupport {

    /** Code for a reason token the policy refuses. The message never echoes the token. */
    public static final String INVALID_REASON_CODE = "ACELIB-CMD-INVALID-REASON";

    /**
     * Fixed refusal text. It is not a language key yet: the language files are not
     * part of this change, so a localized wording still has to be added there.
     */
    static final String INVALID_REASON_MESSAGE =
            "reason must be one word of up to 256 characters without spaces, addresses or credentials";

    private AdminAuditSupport() {
    }

    /**
     * Builds the audit for one administrative command.
     *
     * @param sender the command sender; a player contributes its UUID and name, anything else the console marker
     * @param rawReason the trailing reason token; null or empty means the reason was omitted
     * @throws CommandException {@code ACELIB-CMD-INVALID-REASON} when the policy refuses the reason
     */
    public static AdminAudit resolve(Sender sender, String rawReason) {
        String reason = rawReason == null || rawReason.isEmpty() ? null : rawReason;
        String checked;
        try {
            checked = AdminAudit.validateReason(reason);
        } catch (IllegalArgumentException rejected) {
            throw CommandException.custom(INVALID_REASON_CODE, INVALID_REASON_MESSAGE);
        }
        if (sender.isPlayer()) {
            var player = sender.asPlayer();
            return AdminAudit.player(player.getUniqueId(), player.getName(), checked);
        }
        return AdminAudit.console(checked);
    }
}
