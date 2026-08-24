package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.operations.AuditPage;
import com.smile.aceeconomy.operations.AuditQuery;

import java.util.concurrent.CompletableFuture;

/**
 * Async read-only audit/history query boundary for {@code /aceeco history}.
 *
 * <p>The command slice builds a typed {@link AuditQuery} and receives an immutable
 * {@link AuditPage}; the raw {@code TransactionRepository} is never exposed here.</p>
 */
public interface HistoryQueryService {

    CompletableFuture<AuditPage> query(AuditQuery query);
}
