package com.smile.aceeconomy.infrastructure.persistence.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.SecurityControlAudit;
import com.smile.aceeconomy.operations.security.SecurityControlService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Control audits keep their stored identity across a file reload.
 *
 * <p>A reload must preserve every stored audit field byte-for-byte (id,
 * operation, operator, reason, timestamp, target); a file that repeats a
 * stored audit id must fail closed without touching the file.
 */
class ControlAuditIdentityReloadTest {

    @Test
    @DisplayName("stored audit id and fields survive a file reload")
    void storedAuditFieldsSurviveReload(@TempDir Path dir) {
        Path file = dir.resolve("security-control.json");
        FileSecurityControlStore store = new FileSecurityControlStore(file);
        SecurityControlService first = new SecurityControlService(store);
        UUID target = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        first.freeze(target, AdminAudit.player(operator, "Alex", "incident"));

        SecurityControlAudit written = first.audits().get(0);

        // A fresh store reading the same file must see the identical audit.
        FileSecurityControlStore rereadStore = new FileSecurityControlStore(file);
        SecurityControlService second = new SecurityControlService(rereadStore);

        assertEquals(1, second.audits().size(), "reload must keep exactly one audit");
        SecurityControlAudit reread = second.audits().get(0);
        assertEquals(written.id(), reread.id(), "stored audit id must survive a reload");
        assertEquals(written.operation(), reread.operation());
        assertEquals(written.operatorId(), reread.operatorId());
        assertEquals(written.operatorName(), reread.operatorName());
        assertEquals(written.reason(), reread.reason());
        assertEquals(written.timestamp(), reread.timestamp());
        assertEquals(written.targetAccount(), reread.targetAccount());
    }

    @Test
    @DisplayName("a repeated stored audit id fails closed without touching the file")
    void duplicateStoredAuditIdFailsClosed(@TempDir Path dir) throws Exception {
        UUID repeated = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String audit = "{\"id\":\"" + repeated + "\",\"operation\":\"FREEZE\","
                + "\"operatorId\":null,\"operatorName\":\"CONSOLE\",\"reason\":\"incident\","
                + "\"timestamp\":\"2026-01-01T00:00:00Z\",\"target\":\"" + target + "\"}";
        String document = "{\"format\":1,\"version\":4,\"globalReadonly\":false,"
                + "\"frozen\":[],\"audits\":[" + audit + "," + audit + "]}";
        Path file = dir.resolve("security-control.json");
        Files.writeString(file, document, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);

        SecurityControlService control =
                new SecurityControlService(new FileSecurityControlStore(file));

        assertTrue(control.isLoadFailed(), "a repeated stored audit id must fail closed");
        assertTrue(control.isGlobalReadonly());
        assertTrue(control.audits().isEmpty(), "no audit may be adopted from a bad file");
        assertArrayEquals(before, Files.readAllBytes(file),
                "a bad control file must stay byte-identical");
    }
}
