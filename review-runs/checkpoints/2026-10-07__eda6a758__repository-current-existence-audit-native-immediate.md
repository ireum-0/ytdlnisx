# Repository current-existence audit — native execution immediate dispositions

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: f313e4c4c4b16b7cf8d82810f73be148d944768a
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-NATIVE-01 — VERIFIED_CLOSED

Historical root:
a durable STARTING marker written before ProcessBuilder.start() could survive process death with no pgid
and become permanently unrecoverable.

Current exact source:
- every new native marker receives an immutable random generation token before launch;
- the token is inherited by supervisor/descendant processes through YTDLNISX_NATIVE_GENERATION;
- recovery does not require a pgid for STARTING;
- recoverDetailed() recovers any non-QUIESCENT tokenized marker through a generation selector;
- if no process with that exact token exists, absence is proven from /proc and the marker is published
  QUIESCENT/cleared;
- if launch progressed far enough that a supervisor/descendant exists, that exact token is discoverable
  and remains the recovery authority.

The pre-start crash window and the supervisor-start transition are therefore convergent without age-based
marker clearing.

Disposition: VERIFIED_CLOSED.

## BUG-NATIVE-02 — VERIFIED_CLOSED

Historical root:
recovery treated a reusable numeric pgid as exact execution identity and could signal a newer sibling
that inherited the number.

Current exact source:
- the durable marker carries immutable generationToken and process start-time observations;
- recovery scans /proc for processes whose environment carries the exact generation token;
- signalExactProcess rereads the current process, verifies its startTime and exact generation
  environment immediately before signalling;
- numeric PID/PGID equality alone is explicitly not sufficient authority.

Disposition: VERIFIED_CLOSED.

## BUG-NATIVE-03 — VERIFIED_CLOSED

Historical root:
normal Download success could delete its Download row while exact descendant quiescence remained
unresolved, and startup recovery then discarded the orphan marker because no row existed.

Current exact source closes both halves:
- YoutubeDLCompat.execute() consumes executeWithQuiescence() and throws NativeExecutionFailure when the
  exact native finalization is not proven quiescent, so an unresolved generation is not returned as an
  ordinary successful YoutubeDLResponse to semantic success publication;
- executeWithQuiescence carries a typed FinalizationResult;
- DownloadExecutionRecovery discovery separately retains orphanNativeProcesses for durable download
  markers whose Download row is already absent instead of dropping them from recovery.

Disposition: VERIFIED_CLOSED.

## BUG-NATIVE-04 — VERIFIED_CLOSED

Historical root:
the exact generation reached QUIESCENT, cleanup deleted the marker, then the caller reread marker absence
as nativeQuiescent=false.

Current exact source:
- finalizeTrackedProcess returns YtdlpNativeProcessBarrier.FinalizationResult;
- FinalizationResult distinguishes PROVEN_QUIESCENT_AND_CLEARED,
  PROVEN_QUIESCENT_CLEANUP_PENDING, UNRESOLVED, and OWNER_OR_GENERATION_CHANGED;
- ExecutionResult.nativeQuiescent is derived from that carried finalization result, not by rereading the
  marker after cleanup;
- successful marker deletion therefore preserves the already-proven positive quiescence decision.

Disposition: VERIFIED_CLOSED.

## BUG-NATIVE-05 — VERIFIED_CLOSED

Historical root:
migrated Active/PostProcessing rows with blank executionId entered strict
YtdlpProcessIdentity.download(...), threw before recovery mutation, and looped forever.

Current exact recovery explicitly branches legacy blank identity:
- processId is null when executionId is blank instead of constructing strict YtdlpProcessIdentity;
- observeDownloadExecution(downloadId, blank) enumerates same-Download durable marker state;
- exact generation tokens, when present, are recovered by token;
- absence/no-marker legacy state can reach the legacy-safe cancel/cleanup contract;
- DownloadWorker.cancelProcessesForExecution(blank) refuses unsafe numeric-ID cancellation and succeeds
  only when no same-Download native authority remains.

Disposition: VERIFIED_CLOSED.

## BUG-NATIVE-06 — VERIFIED_CLOSED

Historical root:
new Download claims checked only process-local registries and could admit E2 while unresolved E1 durable
native marker debt survived restart.

Current exact claim gate:
- DownloadWorker.hasAnyRegisteredNativeProcess(downloadId) includes
  YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(downloadId);
- claimDownloadThroughProductionAdmission checks this gate before entering the global claim lock and
  rechecks it inside the lock immediately before the Room claim;
- DownloadExecutionRecovery.hasPendingRecovery likewise treats process-local or durable native authority
  as blocking responsibility.

Thus item-local recovery may defer A without globally blocking sibling B, while A itself cannot publish a
new execution until its durable marker debt is cleared.

Disposition: VERIFIED_CLOSED.

## Immediate audit result

candidate_ids_audited_here: 6
verified_open_here: 0
verified_closed_here: 6

Corrected cumulative lower-bound progress:
- candidate IDs: 136
- audited unique IDs: 72
- verified closed/currently not reproduced: 62
- verified open: 10
- not yet audited inside lower bound: 64

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
