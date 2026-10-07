# Checkpoint-only production finding reconciliation — immediate A

checkpoint_kind: CHECKPOINT_ONLY_PRODUCTION_FINDING_RECONCILIATION
checkpoint_status: FINAL
review_parent_sha: 4714092b540ebe56b658ae751c305a3ea3a6f505
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

This checkpoint covers production findings that are not reliably represented by the TASKS.md /
TASKS_DELTA.md heading registry. Tooling/governance findings are excluded from production-error counts.

## BUG-DOWNLOAD-DELETE-SNAPSHOT-01 — VERIFIED_OPEN P2

Historical root:
a status/discovery-owned Download snapshot can donate destructive authority after the row has materially
changed before the Room deletion transaction.

Current exact eda6a758 still contains the root.

DownloadRepository status-sweep producers:
- deleteScheduled() captures getScheduledDownloads();
- deleteErrored() captures getErroredDownloads();
- deleteQueued() captures getQueuedDownloads();
- deletePaused() captures getPausedDownloadsList().

Each then calls deleteKnownUserRemoval(items) with expectedStatus=null.

Inside deleteKnownUserRemoval():
- when expectedStatus == null, authorizedItems = items;
- the transaction does not reload/match current status, operationId, executionId or downloadStartTime;
- it terminalizes linked children, deletes History-replacement barriers and deletes the current Download
  rows by ID.

currentTargetsWithStatus() now exists and correctly compares current status + operationId + executionId +
downloadStartTime, but it is used only when a caller supplies expectedStatus. The ordinary status-sweep
methods above do not.

Therefore:
capture stale Queued/Error/Scheduled/Paused D
-> another actor advances D to a newer state/execution/schedule generation
-> stale sweep enters deleteKnownUserRemoval with expectedStatus=null
-> current row and linked durable state are deleted by the old observation.

Explicit user-directed current-ID deletion remains a different contract and is not reclassified by this
finding.

Disposition:
BUG-DOWNLOAD-DELETE-SNAPSHOT-01 = VERIFIED_OPEN P2.

Correction boundary:
- status/discovery-owned deletion must pass the expected captured status/generation;
- inside the same transaction that terminalizes linked state and deletes the row, reload current D and
  require exact sufficient snapshot identity (at least expected status, operationId, executionId and
  schedule authority where relevant);
- stale targets are refused item-locally;
- preserve explicit current-ID deletion as a separate intentional API.

## BUG-OBSERVE-HANDOFF-01 — VERIFIED_CLOSED

Historical P0 root at ee7eea00:
ordinary Observe workers carried only source ID, edits/STOP/delete did not durably revoke a worker
generation, retained worker state could overwrite newer configuration/runtime state, and stale workers
could publish Downloads/destructive effects/successors.

Current eda6a758 has a coherent ordinary configuration-generation fence:

Producer/carrier:
- enqueueObservation() requires the current ACTIVE configurationGeneration;
- WorkRequest input contains INPUT_SOURCE_ID + INPUT_CONFIGURATION_GENERATION;
- request tags include the exact configuration generation.

Worker admission:
- ObserveSourceWorker rejects legacy generation-less requests through a reconciliation path;
- normal run loads expectedGeneration from input;
- current source row must be ACTIVE and configurationGeneration == expectedGeneration;
- recurrence handoff, when present, must independently match that same source generation.

Configuration/STOP/delete revocation:
- reconfigure uses advanceUserConfigurationIfGeneration and advances the durable generation;
- stopAndCancelWaitingIfGeneration and deleteAndCancelWaitingIfGeneration require the observed
  generation and revoke it durably before asynchronous WorkManager cancellation;
- stale workers therefore lose semantic authority even if transport cancellation is late.

Final effects:
- runtime publication is narrow updateRuntimeIfGeneration;
- Download admission is wrapped in withActiveGeneration(..., DOWNLOAD_ADMISSION);
- destructive/final publication paths use the same generation-fenced repository boundary;
- finishRunAndSchedule commits runtime state plus the recurring successor through
  commitObserveRunAndStageRecurrence under the current generation and durable handoff;
- retry-confirmation authority also carries configurationGeneration + config fingerprint.

The prior E1 old-config -> E2 edit/STOP/delete -> late E1 publication/revival sequence is therefore
revoked by durable generation, not by cancellation timing.

Disposition:
BUG-OBSERVE-HANDOFF-01 = VERIFIED_CLOSED.

Historical relationship:
BUG-OBSERVE-04 remains a historical alias/subcase of this generation-authority root as already recorded
by the prior Observe review lineage. Do not count it again.

## BUG-TERMINAL-11 — VERIFIED_CLOSED

Checkpoint-only distinct production root.

The historical deterministic closure at implementation 359ddbf9 records FIXED-CLOSED after:
- malformed provider metadata is fail-closed;
- malformed generation-2 rows are superseded and excluded from runnable Terminal dispatch;
- valid siblings remain independently dispatchable;
- late/stale requests are revalidated at worker admission;
- deterministic bounded quiescence covers detached handoff attempt/retry children.

No later production change from the known-good baseline to eda6a758 touches Terminal provider/dispatch
semantics.

Disposition:
BUG-TERMINAL-11 = VERIFIED_CLOSED.

## BUG-UPDATER-04 — VERIFIED_CLOSED

Checkpoint-only distinct production root.

The historical completion review at ee75b757 records FIXED/CLOSED:
anonymous mutation-classified requests receive mutation-scoped native identity before execution, allowing
the durable mutation recovery selector to rediscover and converge retained generations while preserving
reader fencing and exact-token recovery.

The later final known-good review included this closure, and db29f63..eda6a758 changes only scheduler
production files.

Disposition:
BUG-UPDATER-04 = VERIFIED_CLOSED.

## Non-finding filename reconciliation

The filename token backup09 is NOT a distinct BUG-BACKUP-09 root.
2026-09-13__90afaec1__backup09-historical-mode-gate-reconciliation.md is explicitly an F9 /
BUG-BACKUP-07 workflow-eligibility reconciliation. BUG-BACKUP-07 is already audited separately.

## Checkpoint-only progress

new distinct production roots classified here:
- BUG-DOWNLOAD-DELETE-SNAPSHOT-01 — OPEN P2
- BUG-OBSERVE-HANDOFF-01 — CLOSED
- BUG-TERMINAL-11 — CLOSED
- BUG-UPDATER-04 — CLOSED

tooling/governance roots intentionally excluded here:
- BUG-TOOLING-01
- BUG-TOOLING-02

Current narrow download canonical remains P0=0 / P1=0 / P2=8.

No production source, implementation prompt, active implementation scope, Master Plan or ledger was
changed.
