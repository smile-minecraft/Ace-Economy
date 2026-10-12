package com.smile.aceeconomy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Independent audit record for a world-replacing operation.
 *
 * <p>A restore never fabricates a money movement: it replaces the whole world,
 * so stamping a zero-amount deposit would forge a deposit event, pollute the
 * player account, the leaderboard, rollback and the existing history meaning.
 * The restore audit therefore lives outside the transaction list, in its own
 * collection and table, and travels with the replaced world through the same
 * durable publish as the restored data.
 *
 * <p>A record names the operation ({@code RESTORE}), the sender at command
 * time (a player contributes the UUID and the display name seen then, the
 * console contributes a null id with the fixed console marker), the validated
 * reason, the instant the restore committed, and the two snapshot ids that
 * bound the replacement: the candidate that was restored and the safety
 * snapshot taken of the replaced world. The identity is always derived from
 * the sender itself, never from a command argument.
 *
 * <p>Reason policy reuses {@link AdminAudit}: a missing reason becomes the
 * explicit default, blank, over-long, multi-word and secret-looking values
 * are rejected without echoing the raw value. Backup ids are strict tokens
 * and never blank.
 */
public final class OperationAudit {

    /** The only operation recorded today. */
    public static final String OPERATION_RESTORE = "RESTORE";

    private final UUID id;
    private final String operation;
    private final UUID operatorId;
    private final String operatorName;
    private final String reason;
    private final Instant timestamp;
    private final String sourceBackupId;
    private final String safetyBackupId;

    private OperationAudit(UUID id, String operation, UUID operatorId, String operatorName,
                           String reason, Instant timestamp,
                           String sourceBackupId, String safetyBackupId) {
        this.id = id;
        this.operation = operation;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.reason = reason;
        this.timestamp = timestamp;
        this.sourceBackupId = sourceBackupId;
        this.safetyBackupId = safetyBackupId;
    }

    /**
     * Audit for a restore that just committed.
     *
     * @param audit the validated operator and reason from the command layer;
     *              must be recorded (never {@link AdminAudit#unrecorded()} and never null)
     * @param timestamp when the restore committed; never null
     * @param sourceBackupId the candidate snapshot that was restored; never blank
     * @param safetyBackupId the safety snapshot taken of the replaced world; never blank
     */
    public static OperationAudit restore(AdminAudit audit, Instant timestamp,
                                         String sourceBackupId, String safetyBackupId) {
        Objects.requireNonNull(audit, "audit");
        if (!audit.isRecorded()) {
            throw new IllegalArgumentException("restore audit requires a recorded operator and reason");
        }
        Objects.requireNonNull(timestamp, "timestamp");
        if (sourceBackupId == null || sourceBackupId.isBlank()) {
            throw new IllegalArgumentException("sourceBackupId must not be blank");
        }
        if (safetyBackupId == null || safetyBackupId.isBlank()) {
            throw new IllegalArgumentException("safetyBackupId must not be blank");
        }
        return new OperationAudit(UUID.randomUUID(), OPERATION_RESTORE,
                audit.operatorId(), audit.operatorName(), audit.reason(),
                timestamp, sourceBackupId.trim(), safetyBackupId.trim());
    }

    /**
     * Rehydrates a record read from storage. The reason is already validated
     * at the command boundary; storage reads keep it verbatim so a legacy
     * value never fails to load.
     */
    public static OperationAudit rehydrate(UUID id, String operation, UUID operatorId,
                                           String operatorName, String reason, Instant timestamp,
                                           String sourceBackupId, String safetyBackupId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(operatorName, "operatorName");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(sourceBackupId, "sourceBackupId");
        Objects.requireNonNull(safetyBackupId, "safetyBackupId");
        return new OperationAudit(id, operation, operatorId, operatorName, reason,
                timestamp, sourceBackupId, safetyBackupId);
    }

    public UUID id() {
        return id;
    }

    public String operation() {
        return operation;
    }

    public UUID operatorId() {
        return operatorId;
    }

    public String operatorName() {
        return operatorName;
    }

    public String reason() {
        return reason;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public String sourceBackupId() {
        return sourceBackupId;
    }

    public String safetyBackupId() {
        return safetyBackupId;
    }
}
