package com.smile.aceeconomy.ports.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lifecycle, versioning, backup/restore and schema recreation seam for a v2
 * persistence backend. Implement in {@code infrastructure.persistence}.
 *
 * <p>Invariants:</p>
 * <ul>
 *   <li>{@link #initialize()} is idempotent: a fresh create and a restart must both
 *       succeed and leave an identical, consistent schema.</li>
 *   <li>If initialization fails partway, no partial schema is left behind; a later
 *       {@link #initialize()} call must be able to recover.</li>
 *   <li>{@link #backup} / {@link #restore} move a portable snapshot; a corrupt source
 *       must not destroy the live data.</li>
 * </ul>
 */
public interface PersistenceLifecycle {

    void initialize() throws PersistenceException;

    void close();

    boolean isInitialized();

    int schemaVersion() throws PersistenceException;

    /** True when the stored schema version is incompatible and must be recreated. */
    boolean needsRecreation() throws PersistenceException;

    /** Drop and recreate all v2 schema (clean slate). Caller accepts data loss. */
    void truncateAndRecreate() throws PersistenceException;

    void backup(OutputStream out) throws PersistenceException, IOException;

    void restore(InputStream in) throws PersistenceException, IOException;
}
