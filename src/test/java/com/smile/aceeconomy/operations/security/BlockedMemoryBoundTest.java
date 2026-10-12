package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.domain.BlockedControlAttempt;
import com.smile.aceeconomy.infrastructure.persistence.security.FileBlockedAttemptJournal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-memory blocked-attempt history is a bounded recent view: at most the
 * latest 1024 records are kept, so a flood of refusals can never grow memory
 * without limit. The durable journal still appends every record completely —
 * nothing is dropped, and the full history is read back from the journal,
 * never from the bounded memory.
 */
class BlockedMemoryBoundTest {

    private static final Executor DIRECT = Runnable::run;

    private BlockedControlAttempt attempt(String operation, long version) {
        return BlockedControlAttempt.of(operation, UUID.randomUUID(),
                BlockedControlAttempt.CATEGORY_FROZEN, version, null, "CONSOLE", Instant.now());
    }

    @Test
    @DisplayName("memory keeps the latest 1024 while the journal keeps every record")
    void memoryBoundedJournalComplete(@TempDir Path dir) {
        Path journalFile = dir.resolve("attempts.jsonl");
        SecurityControlService control = new SecurityControlService();
        control.bindAttemptJournal(new FileBlockedAttemptJournal(journalFile), DIRECT);

        int total = 1024 + 50;
        for (int i = 0; i < total; i++) {
            control.recordBlockedAttempt(attempt("transfer", control.controlVersion()));
        }

        List<BlockedControlAttempt> recent = control.blockedAttempts();
        assertTrue(recent.size() <= 1024,
                "in-memory history must stay bounded, but kept " + recent.size());
        assertEquals(1024, recent.size(), "the recent view holds the latest 1024 records");
        List<BlockedControlAttempt> journaled =
                new FileBlockedAttemptJournal(journalFile).list();
        assertEquals(total, journaled.size(),
                "the durable journal must append every record without dropping any");
    }
}
