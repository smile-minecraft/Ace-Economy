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

    /**
     * Checked operation for {@link #runExclusive}: may touch persistence state and throw the
     * lifecycle's checked exceptions.
     *
     * @param <R> operation result type
     */
    @FunctionalInterface
    interface ExclusiveOperation<R> {
        R run() throws PersistenceException, IOException;
    }

    /**
     * Runs {@code operation} while holding THIS lifecycle instance's persistence boundary
     * exclusively, so ordinary repository writes on the same backend cannot interleave
     * between the operations composed inside it (for example a safety backup followed by a
     * restore). Implementations must reuse the same reentrant lock / monitor that guards the
     * regular repository methods — never a second lock — so nesting stays safe and writes
     * from other threads simply wait for the window to close.
     *
     * <p>Deliberately abstract with no default: running the operation without the backend's
     * own persistence boundary would silently break the exclusivity guarantee, so every
     * implementation is forced to provide a real one at compile time.</p>
     */
    <R> R runExclusive(ExclusiveOperation<R> operation)
            throws PersistenceException, IOException;
}
