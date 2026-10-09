package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.ports.persistence.IndeterminatePersistenceException;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

/**
 * Maps an escaped storage failure to a typed command error.
 *
 * <p>The application service reports uncommitted storage problems as
 * {@link EconomyError#AUDIT_FAILURE} results, but a failure thrown before a result
 * exists (for example a read that never reached storage) still arrives as an
 * exceptional future. Mapping it here keeps the player-facing code identical either
 * way. Three outcomes stay distinct: uncommitted means nothing happened; committed
 * and indeterminate (commit response lost) both forbid blind retrying and tell the
 * operator to verify first. Nothing is retried here.</p>
 *
 * <p>Player-visible messages are fixed safe strings — backend details (file paths,
 * connection state) never reach the player. The original throwable, with its cause
 * chain and suppressed cleanup failures, is attached to the mapped error and logged
 * at the boundary, so diagnosis loses nothing.</p>
 */
public final class StorageFailures {

    /** The transaction may have committed, or its outcome is uncertain: verify, never retry blindly. */
    public static final String COMMITTED_UNKNOWN_CODE = "ACELIB-CMD-COMMITTED-UNKNOWN";

    private static final System.Logger LOG = System.getLogger(StorageFailures.class.getName());

    private StorageFailures() {
    }

    /**
     * Finds the storage failure in the chain, or {@code null} when this is not a
     * storage failure. Callers leave non-storage throwables on their existing path so
     * a bug can never masquerade as a storage outage.
     */
    public static PersistenceException find(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PersistenceException persistence) {
                return persistence;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Maps a storage failure to a typed command error. Uncommitted failures reuse the
     * audit-failure code the service itself returns for the same situation; committed
     * and indeterminate outcomes share the no-retry code so no caller can mistake them
     * for a safe retry. The original throwable is attached as the cause and logged
     * with it; the reply carries only the fixed safe message.
     */
    public static CommandException from(ConfigLangAdapter messages, PersistenceException failure) {
        CommandException mapped;
        if (failure instanceof IndeterminatePersistenceException) {
            mapped = CommandException.custom(COMMITTED_UNKNOWN_CODE, indeterminateMessage());
        } else if (failure.isCommitted()) {
            mapped = CommandException.custom(COMMITTED_UNKNOWN_CODE, committedMessage());
        } else {
            mapped = TypedErrors.from(messages,
                    EconomyResult.failure(EconomyError.AUDIT_FAILURE, uncommittedMessage()));
        }
        mapped.initCause(failure);
        LOG.log(System.Logger.Level.WARNING,
                "storage failure mapped to " + mapped.getCode() + ": " + failure, failure);
        return mapped;
    }

    static String uncommittedMessage() {
        return "storage unavailable; nothing was changed, please try again later";
    }

    static String committedMessage() {
        return "the transaction is committed; it may already have taken effect — do not retry,"
                + " verify the balance or transaction history first";
    }

    static String indeterminateMessage() {
        return "the result is uncertain; it may already be committed — do not retry,"
                + " verify the balance or transaction history first";
    }
}
