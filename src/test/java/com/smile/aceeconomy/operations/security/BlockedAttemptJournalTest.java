package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.BlockedControlAttempt;
import com.smile.aceeconomy.domain.SecurityControlAudit;
import com.smile.aceeconomy.domain.SecurityControlState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every refused initiating mutation leaves exactly one minimal journal record.
 *
 * <p>Records carry only the operation, target, fixed refusal category, control
 * version and trusted actor — never the full command, IP, or raw reason. Journal
 * work happens on the IO executor, never on a Folia region thread, with a
 * controllable executor (no sleeps). A journal failure still refuses the mutation
 * without claiming audit success, and a failed state publish applies neither state
 * nor audit. All assertions fail on the skeleton no-op journal.
 */
class BlockedAttemptJournalTest {

    private BlockedControlAttempt attempt(String operation, UUID target, String category,
                                          long version) {
        return BlockedControlAttempt.of(operation, target, category, version,
                null, "CONSOLE", Instant.now());
    }

    @Test
    @DisplayName("every refused attempt appends exactly one minimal record")
    void everyRefusalLeavesOneRecord() {
        SecurityControlService control = new SecurityControlService();
        UUID alice = UUID.randomUUID();
        control.freeze(alice, AdminAudit.console("incident"));

        BlockedControlAttempt first =
                attempt("transfer", alice, BlockedControlAttempt.CATEGORY_FROZEN, control.controlVersion());
        BlockedControlAttempt second =
                attempt("withdraw", alice, BlockedControlAttempt.CATEGORY_FROZEN, control.controlVersion());
        control.recordBlockedAttempt(first);
        control.recordBlockedAttempt(second);

        List<BlockedControlAttempt> kept = control.blockedAttempts();
        assertEquals(2, kept.size(), "every refused attempt must leave one record");
        assertEquals("transfer", kept.get(0).operation());
        assertEquals(alice, kept.get(0).targetAccount().orElseThrow());
        assertEquals(BlockedControlAttempt.CATEGORY_FROZEN, kept.get(0).refusalCategory());
    }

    @Test
    @DisplayName("blocked record carries no raw reason, command text, or network identity")
    void blockedRecordCarriesMinimalMetadata() {
        SecurityControlService control = new SecurityControlService();
        UUID alice = UUID.randomUUID();
        control.freeze(alice, AdminAudit.console("incident"));

        control.recordBlockedAttempt(attempt("deposit", alice,
                BlockedControlAttempt.CATEGORY_FROZEN, control.controlVersion()));

        assertEquals(1, control.blockedAttempts().size(),
                "one blocked attempt must leave one record");
        BlockedControlAttempt kept = control.blockedAttempts().get(0);
        assertEquals(BlockedControlAttempt.CATEGORY_FROZEN, kept.refusalCategory());
        assertEquals(control.controlVersion(), kept.controlVersion());
        String serialized = kept.operation() + "|" + kept.refusalCategory() + "|"
                + kept.targetAccount().orElseThrow() + "|" + kept.controlVersion();
        assertFalse(serialized.contains("192.168."),
                "blocked record must never carry a network identity");
        assertFalse(serialized.contains("has space"),
                "blocked record must never carry a raw reason");
    }

    @Test
    @DisplayName("journal append runs on the IO executor, never on the region thread")
    void journalRunsOnIoExecutor() throws Exception {
        SecurityControlService control = new SecurityControlService();
        UUID alice = UUID.randomUUID();
        control.freeze(alice, AdminAudit.console("incident"));

        AtomicReference<String> journalThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Executor io = command -> {
            journalThread.set(Thread.currentThread().getName());
            command.run();
            done.countDown();
        };

        BlockedControlAttempt probe =
                attempt("transfer", alice, BlockedControlAttempt.CATEGORY_FROZEN, control.controlVersion());
        io.execute(() -> control.recordBlockedAttempt(probe));

        assertTrue(done.await(10, TimeUnit.SECONDS), "journal append must complete on the IO executor");
        assertEquals(1, control.blockedAttempts().size(),
                "IO-appended refusal must be retained");
        assertFalse(Thread.currentThread().getName().equals(journalThread.get())
                        && journalThread.get().contains("Region"),
                "journal work must not run on a Folia region thread");
    }

    @Test
    @DisplayName("journal failure still refuses the mutation without claiming audit success")
    void journalFailureStillRefuses() {
        SecurityControlStore failingJournal = new SecurityControlStore() {
            @Override
            public SecurityControlState load() {
                return SecurityControlState.open();
            }

            @Override
            public void save(SecurityControlState state, SecurityControlAudit audit) {
                throw new IllegalStateException("injected journal failure");
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
        };
        SecurityControlService control = new SecurityControlService(failingJournal);
        UUID alice = UUID.randomUUID();

        boolean failed = false;
        try {
            control.freeze(alice, AdminAudit.console("incident"));
        } catch (IllegalStateException expected) {
            failed = true;
        }

        assertTrue(failed, "journal/publish failure must surface instead of claiming success");
        assertFalse(control.isFrozen(alice), "failed control change must leave no state behind");
        assertTrue(control.audits().isEmpty(), "failed control change must leave no orphaned audit");
        assertTrue(control.checkInitiating(alice).isPresent()
                        || control.blockedAttempts().isEmpty(),
                "failed journal must never flip a refusal into an allowance");
    }

    @Test
    @DisplayName("failed state publish applies neither state nor audit")
    void failedPublishAppliesNothingNew() {
        SecurityControlService control = new SecurityControlService(new SecurityControlStore() {
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
        });
        UUID alice = UUID.randomUUID();
        boolean failed = false;
        try {
            control.setGlobalReadonly(true, AdminAudit.console("emergency"));
        } catch (IllegalStateException expected) {
            failed = true;
        }

        assertTrue(failed, "publish failure must surface");
        assertFalse(control.isGlobalReadonly(), "failed read-only switch must leave no state behind");
        assertTrue(control.audits().isEmpty(), "failed switch must leave no orphaned audit");
    }
}
