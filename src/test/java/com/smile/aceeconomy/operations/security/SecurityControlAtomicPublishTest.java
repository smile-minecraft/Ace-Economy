package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.SecurityControlAudit;
import com.smile.aceeconomy.domain.SecurityControlState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State and its control audit publish together: a failed publish applies neither, and
 * every applied change carries exactly one matching audit that can be found by reason.
 */
class SecurityControlAtomicPublishTest {

    private static final class FailingStore implements SecurityControlStore {
        @Override
        public SecurityControlState load() {
            return SecurityControlState.open();
        }

        @Override
        public void save(SecurityControlState state, SecurityControlAudit audit) {
            throw new IllegalStateException("injected publish failure");
        }

        @Override
        public boolean isLoadFailed() {
            return false;
        }

        @Override
        public String loadDiagnosis() {
            return "";
        }

        @Override
        public List<SecurityControlAudit> audits() {
            return List.of();
        }
    }

    @Test
    @DisplayName("failed publish applies neither state nor audit")
    void failedPublishAppliesNothing() {
        SecurityControlService control = new SecurityControlService(new FailingStore());
        UUID acc = UUID.randomUUID();
        boolean failed = false;
        try {
            control.freeze(acc, AdminAudit.console("incident"));
        } catch (IllegalStateException expected) {
            failed = true;
        }
        assertTrue(failed, "publish failure must surface instead of claiming success");
        assertFalse(control.isFrozen(acc), "failed freeze must leave no state behind");
        assertTrue(control.audits().isEmpty(), "failed freeze must leave no orphaned audit");
    }

    @Test
    @DisplayName("applied freeze carries one matching audit found by reason")
    void appliedFreezeCarriesMatchingAudit() {
        SecurityControlService control = new SecurityControlService();
        UUID acc = UUID.randomUUID();
        control.freeze(acc, AdminAudit.console("harbor-check"));

        assertTrue(control.isFrozen(acc));
        assertEquals(1, control.audits().size(), "one freeze must publish exactly one audit");
        SecurityControlAudit audit = control.audits().get(0);
        assertEquals(SecurityControlAudit.OPERATION_FREEZE, audit.operation());
        assertEquals(acc, audit.targetAccount().orElseThrow());
        List<SecurityControlAudit> found = control.findAuditsByReason("harbor-check");
        assertEquals(1, found.size(), "control audit must be findable by reason");
        assertEquals(audit.id(), found.get(0).id());
    }

    @Test
    @DisplayName("applied read-only switch carries one matching audit")
    void appliedReadonlyCarriesMatchingAudit() {
        SecurityControlService control = new SecurityControlService();
        control.setGlobalReadonly(true, AdminAudit.console("storm-hold"));
        assertTrue(control.isGlobalReadonly());
        assertEquals(1, control.audits().size());
        assertEquals(SecurityControlAudit.OPERATION_READONLY_ON, control.audits().get(0).operation());
        assertEquals(1, control.findAuditsByReason("storm-hold").size());
        assertTrue(control.findAuditsByReason("missing-reason-xyz").isEmpty());
    }

    @Test
    @DisplayName("control audit reuses the shared reason policy without echoing secrets")
    void controlAuditReusesReasonPolicy() {
        SecurityControlService control = new SecurityControlService();
        UUID acc = UUID.randomUUID();
        boolean rejected = false;
        try {
            control.freeze(acc, AdminAudit.console("has space"));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected, "multi-word reason must be refused like any admin audit");
        assertFalse(control.isFrozen(acc));
        assertTrue(control.audits().isEmpty());
        Instant now = Instant.now();
        SecurityControlAudit kept = SecurityControlAudit.freeze(
                AdminAudit.console("harbor-check"), now, acc);
        assertEquals("harbor-check", kept.reason());
    }
}
