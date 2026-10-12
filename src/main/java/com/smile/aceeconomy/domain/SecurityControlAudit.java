package com.smile.aceeconomy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Independent audit record for a safety-control change.
 *
 * <p>The record reuses the {@link AdminAudit} reason policy but lives outside the money
 * transaction list and outside the restore operation collection: freezing never fabricates
 * a deposit and never widens the restore record. A freeze names its target account; a
 * read-only switch carries no target.
 */
public final class SecurityControlAudit {

    /** Control operations recorded here. */
    public static final String OPERATION_FREEZE = "FREEZE";
    /** Control operations recorded here. */
    public static final String OPERATION_UNFREEZE = "UNFREEZE";
    /** Control operations recorded here. */
    public static final String OPERATION_READONLY_ON = "READONLY_ON";
    /** Control operations recorded here. */
    public static final String OPERATION_READONLY_OFF = "READONLY_OFF";

    private final UUID id;
    private final String operation;
    private final UUID operatorId;
    private final String operatorName;
    private final String reason;
    private final Instant timestamp;
    private final UUID targetAccount;

    private SecurityControlAudit(UUID id, String operation, UUID operatorId, String operatorName,
                                 String reason, Instant timestamp, UUID targetAccount) {
        this.id = id;
        this.operation = operation;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.reason = reason;
        this.timestamp = timestamp;
        this.targetAccount = targetAccount;
    }

    /** Audit for freezing one account. */
    public static SecurityControlAudit freeze(AdminAudit audit, Instant timestamp, UUID target) {
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(target, "target");
        if (!audit.isRecorded()) {
            throw new IllegalArgumentException("control audit requires a recorded operator and reason");
        }
        return new SecurityControlAudit(UUID.randomUUID(), OPERATION_FREEZE,
                audit.operatorId(), audit.operatorName(), audit.reason(), timestamp, target);
    }

    /** Audit for unfreezing one account. */
    public static SecurityControlAudit unfreeze(AdminAudit audit, Instant timestamp, UUID target) {
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(target, "target");
        if (!audit.isRecorded()) {
            throw new IllegalArgumentException("control audit requires a recorded operator and reason");
        }
        return new SecurityControlAudit(UUID.randomUUID(), OPERATION_UNFREEZE,
                audit.operatorId(), audit.operatorName(), audit.reason(), timestamp, target);
    }

    /** Audit for switching the global read-only flag. */
    public static SecurityControlAudit readonly(AdminAudit audit, Instant timestamp, boolean enabled) {
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(timestamp, "timestamp");
        if (!audit.isRecorded()) {
            throw new IllegalArgumentException("control audit requires a recorded operator and reason");
        }
        return new SecurityControlAudit(UUID.randomUUID(),
                enabled ? OPERATION_READONLY_ON : OPERATION_READONLY_OFF,
                audit.operatorId(), audit.operatorName(), audit.reason(), timestamp, null);
    }

    /**
     * Rebuild the exact audit stored in the control file.
     *
     * <p>Internal: only the control-file load path calls this, after it has
     * validated the stored id, reason, timestamp and target. New control
     * operations must use {@link #freeze}, {@link #unfreeze} or
     * {@link #readonly}, which each generate a fresh id.
     */
    public static SecurityControlAudit rehydrate(UUID id, AdminAudit audit, Instant timestamp,
                                                 String operation, UUID target) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(operation, "operation");
        if (!audit.isRecorded()) {
            throw new IllegalArgumentException("control audit requires a recorded operator and reason");
        }
        return switch (operation) {
            case OPERATION_FREEZE, OPERATION_UNFREEZE -> {
                Objects.requireNonNull(target, "target");
                yield new SecurityControlAudit(id, operation,
                        audit.operatorId(), audit.operatorName(), audit.reason(), timestamp, target);
            }
            case OPERATION_READONLY_ON, OPERATION_READONLY_OFF -> {
                if (target != null) {
                    throw new IllegalArgumentException("read-only audit must not name a target");
                }
                yield new SecurityControlAudit(id, operation,
                        audit.operatorId(), audit.operatorName(), audit.reason(), timestamp, null);
            }
            default -> throw new IllegalArgumentException("unknown control operation");
        };
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

    /** Frozen or unfrozen account; empty for read-only switches. */
    public java.util.Optional<UUID> targetAccount() {
        return java.util.Optional.ofNullable(targetAccount);
    }
}
