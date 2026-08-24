package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.operations.RollbackResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Async rollback boundary for {@code /aceeco rollback <transaction-id>}.
 *
 * <p>The command slice hands over a parsed transaction id and receives the typed
 * {@link RollbackResult}; repositories, reversal executors and marker persistence stay behind
 * this port. Implementations must run the blocking rollback off the command thread.</p>
 */
public interface RollbackCommandService {

    CompletableFuture<RollbackResult> rollback(UUID transactionId);
}
