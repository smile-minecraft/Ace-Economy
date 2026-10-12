package com.smile.aceeconomy.operations.security;

import com.smile.aceeconomy.domain.BlockedControlAttempt;

import java.util.List;

/**
 * Append-only journal for refused initiating mutations.
 *
 * <p>The control state file stays the single source of truth: state changes publish
 * state and its control audit together atomically in that file. Attempt records live
 * in an independent append-only journal used only as read-only evidence; they can
 * never replace or bypass the state file.
 */
public interface BlockedAttemptJournal {

    /** Append one refused-attempt record. */
    void append(BlockedControlAttempt attempt);

    /** Attempt records so far, oldest first. */
    List<BlockedControlAttempt> list();
}
