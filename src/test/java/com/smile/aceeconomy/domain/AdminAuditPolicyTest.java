package com.smile.aceeconomy.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reason policy for administrative audits: defaults, rejection of blank,
 * over-long, multi-word and secret-looking values without echoing them, and
 * the recorded/unrecorded markers that tell legacy rows apart from console rows.
 */
class AdminAuditPolicyTest {

    @Test
    void missingReasonFallsBackToAnExplicitDefault() {
        assertEquals(AdminAudit.DEFAULT_REASON, AdminAudit.validateReason(null));
        assertEquals(AdminAudit.DEFAULT_REASON, AdminAudit.console(null).reason());
        assertEquals(AdminAudit.DEFAULT_REASON,
                AdminAudit.player(UUID.randomUUID(), "Morgan", null).reason());
    }

    @Test
    void blankReasonIsRejectedInsteadOfSilentlyAccepted() {
        assertThrows(IllegalArgumentException.class, () -> AdminAudit.validateReason(""));
        assertThrows(IllegalArgumentException.class, () -> AdminAudit.validateReason("   "));
    }

    @Test
    void overlongReasonIsRejectedWithoutEchoingItself() {
        String overlong = "r".repeat(AdminAudit.MAX_REASON_LENGTH + 44);

        IllegalArgumentException rejected =
                assertThrows(IllegalArgumentException.class, () -> AdminAudit.validateReason(overlong));

        assertFalse(rejected.getMessage().contains(overlong),
                "the rejection must not echo the over-long value back");
    }

    @Test
    void networkAndSecretLookingReasonsAreRejectedWithoutEcho() {
        String[] hostile = {
            "192.168.1.20",
            "10.0.0.9",
            "https://hooks.example/relay/abc",
            "http://internal.local/collect",
            "webhook-relay",
            "bearer-token",
            "sk-live-abcdef0123456789",
            "ghp_abcdef0123456789abcdef",
        };

        for (String secret : hostile) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> AdminAudit.validateReason(secret),
                    "secret-looking reason must be rejected: " + secret.length() + " chars");
            assertFalse(rejected.getMessage().contains(secret),
                    "the rejection must not echo the sensitive value");
        }
    }

    @Test
    void multiWordReasonIsRejectedToKeepTheSingleTokenShape() {
        assertThrows(IllegalArgumentException.class, () -> AdminAudit.validateReason("two words"));
    }

    @Test
    void ordinaryReasonPassesThroughUnchanged() {
        assertEquals("harbor-check", AdminAudit.validateReason("harbor-check"));
        assertEquals("harbor-check", AdminAudit.validateReason("  harbor-check  "));
    }

    @Test
    void unrecordedMarkerStaysApartFromConsole() {
        AdminAudit legacy = AdminAudit.unrecorded();

        assertFalse(legacy.isRecorded());
        assertNull(legacy.operatorId());
        assertNull(legacy.operatorName());
        assertNull(legacy.reason());

        AdminAudit console = AdminAudit.console(null);

        assertTrue(console.isRecorded());
        assertNull(console.operatorId());
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, console.operatorName());
    }
}
