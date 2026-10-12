package com.smile.aceeconomy.commands.v2.ports;

import com.smile.aceeconomy.operations.AuditPage;
import com.smile.aceeconomy.operations.AuditQuery;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Async read-only audit/history query boundary for {@code /aceeco history}.
 *
 * <p>The command slice builds a typed {@link AuditQuery} and receives an immutable
 * {@link AuditPage}; the raw {@code TransactionRepository} is never exposed here.</p>
 */
public interface HistoryQueryService {

    CompletableFuture<AuditPage> query(AuditQuery query);

    /**
     * Executor for history reply continuations (page assembly and reply
     * dispatch preparation). The query itself already runs on this executor
     * in production; the continuation must run there as well so neither
     * timing — an already-completed future or a later completion — leaves
     * page assembly on the calling region thread. The final player reply
     * still goes through the existing region dispatch seam.
     *
     * <p>Production returns the shared IO executor; offline tests supply a
     * controllable executor per case.</p>
     */
    default Executor callbackExecutor() {
        return ForkJoinPool.commonPool();
    }
}
