package com.smile.aceeconomy.infrastructure.persistence.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonFilePublisher;
import com.smile.aceeconomy.operations.security.SecurityControlService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The control file publishes atomically or not at all.
 *
 * <p>A filesystem without atomic-move support fails closed: the previous file
 * bytes, the previous state and the previous audits stay exactly as they
 * were, and the freeze stays refused. A durability-sync failure after the
 * replacement landed must never claim that nothing was applied: the new
 * state is adopted from the trusted file content and the failure surfaces
 * loudly, and a repeated toggle stays idempotent instead of publishing twice.
 */
class ControlAtomicPublishTest {

    private static final class ScriptedPublisher implements JsonFilePublisher {
        private final IOException moveFailure;
        private final IOException syncFailure;

        ScriptedPublisher(IOException moveFailure, IOException syncFailure) {
            this.moveFailure = moveFailure;
            this.syncFailure = syncFailure;
        }

        @Override
        public Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
            return Files.createTempFile(dir, prefix, suffix);
        }

        @Override
        public void writeString(Path tmp, String content) throws IOException {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
        }

        @Override
        public void syncFile(Path file) throws IOException {
            if (syncFailure != null) {
                throw syncFailure;
            }
            try (var channel = java.nio.channels.FileChannel.open(file,
                    java.nio.file.StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        @Override
        public void moveAtomic(Path tmp, Path target) throws IOException {
            if (moveFailure != null) {
                throw moveFailure;
            }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void syncDirectory(Path dir) throws IOException {
            if (syncFailure != null) {
                throw syncFailure;
            }
        }
    }

    @Test
    @DisplayName("filesystem without atomic move fails closed with old bytes and state intact")
    void atomicMoveUnsupportedFailsClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("security-control.json");
        FileSecurityControlStore setup = new FileSecurityControlStore(file);
        SecurityControlService seeded = new SecurityControlService(setup);
        UUID kept = UUID.randomUUID();
        seeded.freeze(kept, AdminAudit.console("incident"));
        byte[] before = Files.readAllBytes(file);

        FileSecurityControlStore store = new FileSecurityControlStore(file,
                new ScriptedPublisher(
                        new AtomicMoveNotSupportedException("x", "y", "no atomic move"), null));
        SecurityControlService control = new SecurityControlService(store);
        assertTrue(control.isFrozen(kept), "setup freeze must be visible before the publish");

        UUID next = UUID.randomUUID();
        try {
            control.freeze(next, AdminAudit.console("incident"));
            fail("publish without atomic replacement must be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("atomic"),
                    "the refusal must name the atomic-replacement requirement");
        }

        assertArrayEquals(before, Files.readAllBytes(file),
                "the previous control file must stay byte-identical");
        assertFalse(control.isFrozen(next), "the refused freeze must leave no state behind");
        assertTrue(control.isFrozen(kept), "the previous freeze must survive");
        assertEquals(1, control.audits().size(), "the refused freeze must leave no orphaned audit");
    }

    @Test
    @DisplayName("post-publish sync failure adopts the landed state instead of denying it")
    void postPublishSyncFailureAdoptsLandedState(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("security-control.json");
        FileSecurityControlStore store = new FileSecurityControlStore(file,
                new ScriptedPublisher(null, new IOException("injected durability sync failure")));
        SecurityControlService control = new SecurityControlService(store);
        UUID acc = UUID.randomUUID();

        try {
            control.freeze(acc, AdminAudit.console("incident"));
            fail("a post-publish sync failure must surface instead of claiming success");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("no state or audit was applied"),
                    "a landed replacement must never be reported as not applied: "
                            + expected.getMessage());
        }

        // The replacement landed on disk, so memory must agree with the trusted
        // file content instead of staying open as if nothing happened.
        SecurityControlService reread =
                new SecurityControlService(new FileSecurityControlStore(file));
        assertTrue(reread.isFrozen(acc), "the landed freeze must be visible on disk");
        assertTrue(control.isFrozen(acc),
                "memory must adopt the landed state instead of pretending nothing applied");
        assertEquals(1, control.audits().size(),
                "the landed audit must be adopted, never duplicated or dropped");

        // No toggle is re-sent: repeating the freeze is idempotent.
        control.freeze(acc, AdminAudit.console("incident"));
        assertEquals(1, control.audits().size(),
                "repeating the landed freeze must stay idempotent");
    }
}
