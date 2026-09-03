package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.operations.ImportOutcome;
import com.smile.aceeconomy.ports.operations.ImportSource;

import java.util.concurrent.CompletableFuture;

/**
 * Async boundary for {@code /aceeco import}. The command slice hands over an
 * already-validated source, gate-relative path and currency id; parsing, the
 * path gate, the pre-import safety backup and the balance writes all stay
 * behind this port. Implementations must run the blocking work off the
 * command thread. {@code preview} is always a zero-write dry run; only
 * {@code apply} (reached with the exact {@code apply confirm} pair) writes.
 */
public interface ImportCommandService {

    CompletableFuture<ImportOutcome> preview(ImportSource source, String path, String currencyId);

    CompletableFuture<ImportOutcome> apply(ImportSource source, String path, String currencyId);
}
