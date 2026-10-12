package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.OperationAudit;
import com.smile.aceeconomy.ports.operations.OperationAuditStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Internal trace query for restore audits: no public command, only this
 * service. A restore files one independent record carrying the operator, the
 * reason and the two snapshot ids; the reason and the ids stay findable
 * without touching any money record.
 */
final class OperationAuditQueryTest {

    private OperationAuditService serviceWith(OperationAudit record) {
        OperationAuditStore store = new OperationAuditStore() {
            @Override
            public List<OperationAudit> listOperationAudits() {
                return List.of(record);
            }
        };
        return new OperationAuditService(store);
    }

    @Test
    void reasonTextFindsTheRestoreRecord() {
        OperationAudit record = OperationAudit.restore(
                AdminAudit.console("harbor-restore-trace"),
                Instant.parse("2026-02-01T00:00:00Z"),
                "seed-trace", "safety-trace");

        OperationAuditService queries = serviceWith(record);

        assertEquals(1, queries.query(
                OperationAuditQuery.builder().reasonContains("harbor-restore").build()).size(),
                "the restore reason must stay findable through the internal query");
        assertTrue(queries.query(
                OperationAuditQuery.builder().reasonContains("no-such-reason").build()).isEmpty());
    }

    @Test
    void sourceAndSafetyIdsStayTraceable() {
        OperationAudit record = OperationAudit.restore(
                AdminAudit.console("harbor-restore-trace"),
                Instant.parse("2026-02-01T00:00:00Z"),
                "seed-trace", "safety-trace");

        OperationAuditService queries = serviceWith(record);

        assertEquals(1, queries.query(
                OperationAuditQuery.builder().sourceBackupId("seed-trace").build()).size(),
                "the source snapshot id must stay traceable");
        assertEquals(1, queries.query(
                OperationAuditQuery.builder().safetyBackupId("safety-trace").build()).size(),
                "the safety snapshot id must stay traceable");
        assertEquals(OperationAudit.OPERATION_RESTORE, record.operation());
        assertEquals("seed-trace", record.sourceBackupId());
        assertEquals("safety-trace", record.safetyBackupId());
    }
}
