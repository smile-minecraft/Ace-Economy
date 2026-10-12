package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.domain.SecurityControlAudit;
import com.smile.aceeconomy.domain.SecurityControlState;

/**
 * Durable store for the server-local safety control.
 *
 * <p>The control file is managed independently from the economy snapshot: economy backups
 * never contain it and restoring an economy snapshot never replaces it. State and its
 * control audits publish together atomically; a failed publish leaves neither a partial
 * state nor an orphaned audit. A corrupt file fails closed and is never overwritten or
 * deleted by the load path.
 */
public interface SecurityControlStore {

    /** Current control snapshot; fail-closed when the file cannot be read. */
    SecurityControlState load();

    /**
     * Publish state and its audit together atomically.
     *
     * @throws IllegalStateException when the publish cannot be completed; nothing is applied
     */
    void save(SecurityControlState state, SecurityControlAudit audit);

    /** True when the last load failed and mutations must stay blocked. */
    boolean isLoadFailed();

    /** Fixed loud diagnosis for the load failure; empty when the load succeeded. */
    String loadDiagnosis();

    /** Control audits persisted so far, oldest first. */
    java.util.List<SecurityControlAudit> audits();
}
