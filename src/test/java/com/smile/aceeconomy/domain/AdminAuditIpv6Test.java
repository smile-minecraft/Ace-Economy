package com.smile.aceeconomy.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Network-address guard for audit reasons: IPv6 shapes must be refused with a
 * fixed message that never echoes the raw value, while ordinary reasons keep
 * passing through unchanged.
 */
class AdminAuditIpv6Test {

    @Test
    void compressedAndFullIpv6ReasonsAreRejectedWithoutEcho() {
        String[] hostile = {
            "2001:db8::1",
            "::1",
            "fe80::1",
            "2001:db8:0:0:0:0:2:1",
            "2001:0db8:0000:0000:0000:0000:0000:0001",
        };
        for (String raw : hostile) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> AdminAudit.validateReason(raw),
                    "IPv6 reason must be rejected");
            assertFalse(rejected.getMessage().contains(raw),
                    "the rejection must not echo the sensitive value");
        }
    }

    @Test
    void bracketedMappedAndZoneIpv6ReasonsAreRejectedWithoutEcho() {
        String[] hostile = {
            "[::1]",
            "[2001:db8::1]",
            "::ffff:192.0.2.1",
            "fe80::1%eth0",
        };
        for (String raw : hostile) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> AdminAudit.validateReason(raw),
                    "IPv6 reason must be rejected");
            assertFalse(rejected.getMessage().contains(raw),
                    "the rejection must not echo the sensitive value");
        }
    }

    @Test
    void consoleWithIpv6ReasonIsRejectedWithoutEcho() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> AdminAudit.console("2001:db8::1"));
        assertFalse(rejected.getMessage().contains("2001:db8::1"),
                "the rejection must not echo the sensitive value");
    }

    @Test
    void ordinaryReasonStillPasses() {
        assertEquals("harbor-check", AdminAudit.validateReason("harbor-check"));
        assertEquals(AdminAudit.DEFAULT_REASON, AdminAudit.validateReason(null));
    }
}
