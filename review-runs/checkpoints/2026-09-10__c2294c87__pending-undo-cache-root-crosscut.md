# Independent Track A checkpoint — Pending Undo + cache-root authority

Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
Verdict: `NOT_CLEAN`
Independent execution: NOT EXECUTED

This review intentionally uses the fixed contiguous independently-CLEAN Review Basis and does not inspect in-progress implementation work.

## Pending Undo / retry-generation scan

Result: no new blocker.

Cancellation Undo is bound to the exact durable token, low-quality child state, and operation. `restoreCancelledStatusForPendingCancellation` requires the exact `operationId` and token and a RUNNING uncancelled parent. Removal Undo uses the durable token as its once-only carrier and does not overwrite an unrelated row if the original numeric Download id is already occupied. Startup `LowQualityRedownloadManager.reconcileCancellationDebt()` calls `reconcileAbandonedUndoDebts()`, which enumerates both pending low-quality tokens and `pending_undo_carriers`, so unlinked durable removal RESTORE/COMMIT debt is recoverable after process death.

## Existing `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` — D4 scope extension

`DownloadRepository.deleteProcessing()` snapshots Processing rows outside the Room transaction, uses that snapshot only to choose History-barrier ids, and then executes a status-wide `DELETE FROM downloads WHERE status='Processing'` inside the transaction. A row can enter Processing after the snapshot and still be deleted without belonging to the snapshot/revalidation set. This is the same stale/current-set mismatch root as the existing destructive snapshot finding; count unchanged for D4.

Acceptance remains: destructive maintenance must operate on an exact revalidated set (id + relevant status/operation/execution generation) under one authority boundary, and linked ledgers/barriers/cache authority must be mutated for exactly that same set.

## New `BUG-CACHE-ROOT-01` — CONFIRMED P2

The cache directory preference can be changed without an active Download/Terminal quiescence gate. `FileUtil.getCachePath()` re-reads the preference on every call. Download and Terminal workers call it repeatedly within one admitted execution.

Concrete Download path:
- a runner captures `rawTempFileDir` under cache root C1;
- the user changes `cache_path` to C2;
- later ownership recording/cleanup resolves C2 while produced files and marker remain under C1;
- retry reset explicitly rejects the captured C1 temp directory because its parent no longer equals the current C2 root.

Terminal similarly uses the current cache root independently at admission/output creation and later recovery reconciliation, allowing one execution's storage namespace to change mid-generation.

Impact: a valid in-flight generation can fail solely because the setting changed; its exact ownership/recovery artifacts can be split across old/current roots and ordinary maintenance/recovery can operate on the wrong namespace.

Acceptance:
1. Pin the cache root as part of each exact Download/Terminal generation and use that exact root for staging, ownership, publication recovery, cleanup and finalization.
2. Or refuse/migrate cache-root changes until every live generation and durable recovery debt using the old root is quiescent; an active-row count alone is insufficient.
3. Startup recovery must remain able to discover durable old-root generations after a preference change.
4. Add production-path tests that mutate the preference between admission/preparation and artifact recording, retry cleanup, Terminal output/recovery, and process restart.

## Working independent recount

`P0 2 / P1 3 / P2 22`

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.
Authoritative ledger unchanged.

A separately produced checkpoint/recount on `review/remediation` does not override this independent semantic review unless its claims are independently supported at the fixed Review Basis.
