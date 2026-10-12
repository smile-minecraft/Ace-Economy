package com.smile.aceeconomy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Minimal metadata kept for one refused initiating mutation.
 *
 * <p>Design contract for the completing implementation (kept here so the failing tests
 * can name it precisely):
 *
 * <ul>
 *   <li>One record per refused attempt, appended on the IO executor, never on a Folia
 *       region thread. Regions only enqueue; they never block on the journal.</li>
 *   <li>Metadata is limited to the operation name, the target account when there is
 *       one, the fixed refusal category ({@code ACCOUNT_FROZEN},
 *       {@code GLOBAL_READ_ONLY} or {@code CONTROL_UNAVAILABLE}), the control version
 *       at refusal time, and the operator identity when it can be trusted from the
 *       sender. The full command line, IP, and raw reason text are never stored.</li>
 *   <li>A journal failure fails closed with an explicit diagnosis: the mutation stays
 *       refused, and the reply never claims the audit succeeded.</li>
 *   <li>Attempt records are read-only evidence. They never reconstruct control state,
 *       so bypassing the state file through the journal is impossible.</li>
 * </ul>
 *
 * <p>The factory only fills fields; the control service admits solely the
 * fixed category set, so hand-built categories can never pollute the journal.
 */
public final class BlockedControlAttempt {

    /** Fixed refusal categories; nothing else may be stored here. */
    public static final String CATEGORY_FROZEN = "ACCOUNT_FROZEN";

    /** Fixed refusal categories; nothing else may be stored here. */
    public static final String CATEGORY_READONLY = "GLOBAL_READ_ONLY";

    /** Fixed refusal categories; nothing else may be stored here. */
    public static final String CATEGORY_UNAVAILABLE = "CONTROL_UNAVAILABLE";

    private final UUID id;
    private final Instant timestamp;
    private final String operation;
    private final UUID targetAccount;
    private final String refusalCategory;
    private final long controlVersion;
    private final UUID operatorId;
    private final String operatorName;

    private BlockedControlAttempt(UUID id, Instant timestamp, String operation, UUID targetAccount,
                                  String refusalCategory, long controlVersion,
                                  UUID operatorId, String operatorName) {
        this.id = id;
        this.timestamp = timestamp;
        this.operation = operation;
        this.targetAccount = targetAccount;
        this.refusalCategory = refusalCategory;
        this.controlVersion = controlVersion;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
    }

    /** Builds a record; the control service admits solely the fixed category set. */
    public static BlockedControlAttempt of(String operation, UUID targetAccount,
                                           String refusalCategory, long controlVersion,
                                           UUID operatorId, String operatorName,
                                           Instant timestamp) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(refusalCategory, "refusalCategory");
        Objects.requireNonNull(timestamp, "timestamp");
        return new BlockedControlAttempt(UUID.randomUUID(), timestamp, operation, targetAccount,
                refusalCategory, controlVersion, operatorId, operatorName);
    }

    public UUID id() {
        return id;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public String operation() {
        return operation;
    }

    public java.util.Optional<UUID> targetAccount() {
        return java.util.Optional.ofNullable(targetAccount);
    }

    public String refusalCategory() {
        return refusalCategory;
    }

    public long controlVersion() {
        return controlVersion;
    }

    public java.util.Optional<UUID> operatorId() {
        return java.util.Optional.ofNullable(operatorId);
    }

    public java.util.Optional<String> operatorName() {
        return java.util.Optional.ofNullable(operatorName);
    }
}
