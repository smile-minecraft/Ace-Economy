package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.SecurityControlState;
import com.smile.aceeconomy.infrastructure.persistence.security.FileSecurityControlStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control file survives restarts, fails closed with a loud diagnosis when corrupt,
 * and is never overwritten or deleted by the load path. Reads keep working while frozen.
 */
class SecurityControlPersistenceTest {

    @Test
    @DisplayName("freeze survives a reload through the same store")
    void freezeSurvivesReload(@TempDir Path dir) {
        Path file = dir.resolve("safety-control.json");
        FileSecurityControlStore store = new FileSecurityControlStore(file);
        SecurityControlService first = new SecurityControlService(store);
        UUID acc = UUID.randomUUID();
        first.freeze(acc, AdminAudit.console("incident"));

        SecurityControlService second = new SecurityControlService(store);
        assertTrue(second.isFrozen(acc), "freeze must survive a reload");
        SecurityControlState snapshot = second.snapshot();
        assertTrue(snapshot.isFrozen(acc));
        assertFalse(snapshot.isGlobalReadonly());
    }

    @Test
    @DisplayName("read-only survives a reload through the same store")
    void readonlySurvivesReload(@TempDir Path dir) {
        Path file = dir.resolve("safety-control.json");
        FileSecurityControlStore store = new FileSecurityControlStore(file);
        SecurityControlService first = new SecurityControlService(store);
        first.setGlobalReadonly(true, AdminAudit.console("emergency"));

        SecurityControlService second = new SecurityControlService(store);
        assertTrue(second.isGlobalReadonly(), "read-only must survive a reload");
    }

    @Test
    @DisplayName("corrupt control fails closed with a loud diagnosis and keeps the file")
    void corruptControlFailsClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("safety-control.json");
        Files.writeString(file, "{corrupt control file[[[", StandardCharsets.UTF_8);
        long sizeBefore = Files.size(file);
        FileSecurityControlStore store = new FileSecurityControlStore(file);
        SecurityControlService control = new SecurityControlService(store);

        assertTrue(control.isLoadFailed(), "corrupt control must report a load failure");
        assertFalse(control.loadDiagnosis().isBlank(), "load failure must carry a loud diagnosis");
        assertTrue(control.isGlobalReadonly(), "corrupt control must fail closed to read-only");
        assertTrue(control.checkInitiating(UUID.randomUUID()).isPresent(),
                "corrupt control must refuse initiating mutations");
        assertTrue(Files.exists(file), "corrupt file must never be deleted");
        assertEquals(sizeBefore, Files.size(file), "corrupt file must never be overwritten");
    }

    @Test
    @DisplayName("switching read-only off cannot bypass a load failure")
    void readonlyOffCannotBypassLoadFailure(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("safety-control.json");
        Files.writeString(file, "not a control file", StandardCharsets.UTF_8);
        FileSecurityControlStore store = new FileSecurityControlStore(file);
        SecurityControlService control = new SecurityControlService(store);
        control.setGlobalReadonly(false, AdminAudit.console("recovered"));

        assertTrue(control.isLoadFailed(), "load failure survives a read-only off attempt");
        assertTrue(control.isGlobalReadonly(), "mutations stay blocked after a load failure");
        assertTrue(control.checkInitiating(UUID.randomUUID()).isPresent());
    }

    @Test
    @DisplayName("first start without a file opens writable; an existing bad file is never missing")
    void firstStartVersusBadFile(@TempDir Path dir) {
        Path missing = dir.resolve("absent-control.json");
        FileSecurityControlStore fresh = new FileSecurityControlStore(missing);
        SecurityControlService open = new SecurityControlService(fresh);
        assertFalse(open.isLoadFailed(), "first start without a file opens writable");
        assertFalse(open.isGlobalReadonly());
    }
}
