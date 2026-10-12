package com.smile.aceeconomy.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Embedded IPv6 containment for audit reasons: a full eight-group literal must
 * be refused even when it arrives with an {@code ip=} / {@code review-} style
 * prefix or a suffix, while ordinary single-colon business tokens keep passing
 * with a fixed message that never echoes the raw value.
 */
class AdminAuditIpv6EmbeddedTest {

    @Test
    void fullIpv6WithPrefixOrSuffixIsRejectedWithoutEcho() {
        String[] hostile = {
            "ip=2001:db8:0:0:0:0:0:1",
            "review-2001:db8:0:0:0:0:0:1",
            "2001:db8:0:0:0:0:0:1-suffix",
            "prefix-2001:DB8:0:0:0:0:0:1",
            "addr=2001:0db8:0000:0000:0000:0000:0000:0001",
            "[2001:db8:0:0:0:0:0:1]",
            "2001:db8:0:0:0:0:0:1%eth0",
            "review-2001:db8::1",
        };
        for (String raw : hostile) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> AdminAudit.validateReason(raw),
                    "embedded IPv6 reason must be rejected: " + raw);
            assertFalse(rejected.getMessage().contains(raw),
                    "the rejection must not echo the sensitive value");
        }
    }

    @Test
    void mixedTailWithPrefixIsRejectedWithoutEcho() {
        String raw = "addr:1:2:3:4:5:6:192.0.2.1";
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> AdminAudit.validateReason(raw),
                "embedded mixed IPv4-tail reason must be rejected");
        assertFalse(rejected.getMessage().contains(raw),
                "the rejection must not echo the sensitive value");
    }

    @Test
    void ordinarySingleColonTokensStillPass() {
        assertEquals("foo:bar", AdminAudit.validateReason("foo:bar"));
        assertEquals("dead:beef", AdminAudit.validateReason("dead:beef"));
        assertEquals("a:b:c", AdminAudit.validateReason("a:b:c"));
        assertEquals("1:2:3:4:5:6:7", AdminAudit.validateReason("1:2:3:4:5:6:7"));
        assertEquals("1:2:3:4:5:6:7:8:9", AdminAudit.validateReason("1:2:3:4:5:6:7:8:9"));
        assertEquals("dead:beef:cafe", AdminAudit.validateReason("dead:beef:cafe"));
        assertEquals("harbor-check", AdminAudit.validateReason("harbor-check"));
    }
}
