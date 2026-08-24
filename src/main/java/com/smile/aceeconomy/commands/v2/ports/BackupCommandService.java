package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.operations.BackupResult;
import com.smile.aceeconomy.operations.RestoreResult;

import java.util.concurrent.CompletableFuture;

/**
 * Async management boundary for {@code /aceeco backup} and {@code /aceeco restore}.
 *
 * <p>The command slice hands over an already-validated label / backup id token and receives
 * typed outcomes; the controlled backup directory, safety backup, online-player gate and the
 * persistence lifecycle stay behind this port. Snapshots are published only inside that
 * controlled directory: the complete target is created handle-relative with {@code CREATE_NEW},
 * fully written and forced, then a handle-relative {@code .ready} marker is created with
 * {@code CREATE_NEW} as an application-level logical commit — never an operating-system
 * atomic rename or hard link. Implementations must run the blocking service calls off the
 * command thread.</p>
 */
public interface BackupCommandService {

    /** @param label optional safe label; null/blank means no label */
    CompletableFuture<BackupResult> createBackup(String label);

    CompletableFuture<RestoreResult> restore(String backupId);
}
