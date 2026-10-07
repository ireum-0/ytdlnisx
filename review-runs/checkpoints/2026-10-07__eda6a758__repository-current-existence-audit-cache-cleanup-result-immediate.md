# Repository current-existence audit — cache/cleanup/result immediate

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: a3232a3ab98bbdf5e63d820af774278a9dc144a3
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-CACHE-03 — VERIFIED_CLOSED

The API 24-25 fallback now checks File.renameTo() and throws on false. MoveCacheFilesWorker catches the
failure and returns Result.failure rather than reporting success.

## BUG-CACHE-04 — VERIFIED_OPEN P2

FolderSettingsFragment still:
- creates MoveCacheFilesWorker;
- calls beginUniqueWork(...).enqueue() and discards the WorkManager Operation;
- observes the shared "cacheFiles" tag;
- checks list == null but then evaluates list.first() without proving the list is nonempty.

An asynchronous enqueue acceptance failure can therefore still produce an empty tag query and an
uncaught NoSuchElementException. The root remains open.

## BUG-CACHE-05 — VERIFIED_CLOSED

MoveCacheFilesWorker runs its entire manifest/move pass inside CacheMaintenanceAuthority.withMaintenanceWindow.
Download execution admission and TerminalExecutionRegistry.admit use the matching withExecutionAdmission
gate, and cache deletion additionally recognizes live Terminal ownership/recovery namespaces. The
historical live-Terminal cache theft race is therefore closed.

## BUG-CLEANUP-02 — VERIFIED_CLOSED

Recurring cleanup no longer deletes a broad DOWNLOAD_TEMP root from a stale zero-active count alone.
It journals an exact cache snapshot, and AppCacheManager.deleteExact executes under
CacheMaintenanceAuthority while refusing live-owned entries and changed/reused paths.

## BUG-CLEANUP-03 — VERIFIED_CLOSED

CleanupScheduleCoordinator now owns a generation/cadence-bound recurring chain. Each completed occurrence
durably records successor debt and scheduleSuccessor() publishes the next one-time work request, with
enqueue-acceptance/replay ownership. The original one-shot-without-successor failure is gone.

## BUG-LOG-01 — VERIFIED_OPEN P3

LogViewModel.delete() still launches detached IO work that deletes the Log row and then separately calls
DownloadRepository.removeLogID(). Undo still reinserts the Log independently. There is no single Room
transaction or relationship-restoring undo carrier, so the original association-loss and fast-Undo race
remain.

## BUG-MOVE-01 — VERIFIED_CLOSED

SAF publication now moves exact source files individually, records per-file errors, deletes only each
successfully published source, and preserves the origin tree whenever any move failure exists.
cleanupMovedSourceTree only removes empty directories for exact source-set callers. The historical
"one copied file authorizes recursive deletion of uncopied siblings" path is gone.

## BUG-RESULT-01 — VERIFIED_OPEN P3

ResultViewModel.reverseResults() still computes future primary keys, returns them immediately, launches a
delayed background coroutine, and applies ResultDao.updateID one row at a time without one transaction or
reservation. Concurrent inserts/process death can still create collision or partial PK remapping.

## BUG-RETRY-01 — VERIFIED_OPEN P2

The Error -> Processing bulk redownload path still calls prepareRetryMetadata(strategy=RECONFIGURED,
settingsConfirmed=true) before any concrete user configuration mutation is proven. An unchanged request
can therefore bypass SAME_SETTINGS retry restrictions simply by entering the reconfiguration UI path.

## BUG-SHARE-01 — VERIFIED_OPEN P3

ShareActivity still calls resultViewModel.deleteAll() when the shared URL has zero or multiple exact
matches, even though its local ResultItem need not own the global Result table. ResultViewModel.deleteAll
still returns detached viewModelScope work rather than an awaited scoped session mutation. An external
share can therefore erase or race an unrelated Home result session.

## Immediate result

candidate_ids_audited_here: 10
verified_closed_here: 5
verified_open_here: 5

136-ID lower-bound progress:
- audited: 110
- verified closed/currently not reproduced: 71
- verified open: 39
- not yet audited: 26

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
