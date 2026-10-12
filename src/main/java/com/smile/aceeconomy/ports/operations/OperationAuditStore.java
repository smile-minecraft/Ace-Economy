package com.smile.aceeconomy.ports.operations;

import com.smile.aceeconomy.domain.OperationAudit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal read seam for world-operation audits.
 *
 * <p>Restore is console-only and has no public query command: operators trace
 * a restore through this internal port (by id or by reason text) without any
 * new player-facing surface. Implement in {@code infrastructure.persistence}.
 */
public interface OperationAuditStore {

    /**
     * Every operation audit visible in the live state, in no particular order.
     * A state written before operation tracking loads as an empty list, never
     * a failure. Skeleton implementations return an empty list.
     */
    List<OperationAudit> listOperationAudits();

    /**
     * Finds one record by its id. Skeleton implementations return an empty
     * result.
     */
    default Optional<OperationAudit> findOperationAudit(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        for (OperationAudit record : listOperationAudits()) {
            if (id.equals(record.id())) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }
}
