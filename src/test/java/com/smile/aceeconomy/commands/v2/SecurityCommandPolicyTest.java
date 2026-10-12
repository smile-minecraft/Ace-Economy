package com.smile.aceeconomy.commands.v2;

import com.smile.aceeconomy.domain.AdminAudit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Freeze and read-only commands need independent permissions under the admin wildcard,
 * refuse unknown accounts explicitly, stay idempotent on repeats, and validate reasons
 * through the shared admin policy for both console and player senders.
 */
class SecurityCommandPolicyTest {

    @Test
    @DisplayName("freeze and read-only use independent permissions")
    void independentPermissions() {
        assertEquals("aceeconomy.admin.freeze", SecurityCommandPolicy.FREEZE_PERMISSION);
        assertEquals("aceeconomy.admin.readonly", SecurityCommandPolicy.READONLY_PERMISSION);
        assertTrue(SecurityCommandPolicy.hasFreezePermission(p -> p.equals("aceeconomy.admin.freeze")),
                "matching freeze permission must be accepted");
        assertFalse(SecurityCommandPolicy.hasFreezePermission(p -> false),
                "missing freeze permission must be refused before any state is read");
        assertTrue(SecurityCommandPolicy.hasReadonlyPermission(p -> p.equals("aceeconomy.admin.readonly")),
                "matching read-only permission must be accepted");
        assertFalse(SecurityCommandPolicy.hasReadonlyPermission(p -> false),
                "missing read-only permission must be refused before any state is read");
    }

    @Test
    @DisplayName("unknown target account is refused explicitly")
    void unknownAccountRefused() {
        Optional<String> missing = SecurityCommandPolicy.checkTargetExists(false, "ghost");
        assertTrue(missing.isPresent(), "unknown account must be refused explicitly");
        Optional<String> known = SecurityCommandPolicy.checkTargetExists(true, "alice");
        assertTrue(known.isEmpty(), "known account must pass validation");
    }

    @Test
    @DisplayName("repeated freeze and unfreeze are idempotent")
    void repeatIsIdempotent() {
        assertTrue(SecurityCommandPolicy.isIdempotentRepeat(true),
                "repeating the current state must report idempotent with no version change");
        assertFalse(SecurityCommandPolicy.isIdempotentRepeat(false),
                "first transition must report a fresh state change");
    }

    @Test
    @DisplayName("console and player senders both validate reasons through the shared policy")
    void consoleAndPlayerReasons() {
        UUID operator = UUID.randomUUID();
        AdminAudit console = SecurityCommandPolicy.auditFor(false, null, "CONSOLE", null);
        assertEquals(AdminAudit.DEFAULT_REASON, console.reason());
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, console.operatorName());

        AdminAudit player = SecurityCommandPolicy.auditFor(true, operator, "Alice", "harbor-check");
        assertEquals(operator, player.operatorId());
        assertEquals("harbor-check", player.reason());

        boolean rejected = false;
        try {
            SecurityCommandPolicy.auditFor(true, operator, "Alice", "has space");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected, "multi-word reason must be refused for player senders too");

        Optional<String> bad = SecurityCommandPolicy.checkReason("has space");
        assertTrue(bad.isPresent(), "invalid reason token must be refused without echoing it");
        assertTrue(SecurityCommandPolicy.checkReason("harbor-check").isEmpty());
    }
}
