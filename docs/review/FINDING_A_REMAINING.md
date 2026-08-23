# Finding A Remaining Review Ledger

## Frozen review target

- Repository: `ireum-0/ytdlnisx`
- Formal pushed review HEAD: `805722d7167f59d82eb9ea1bba33e515dd18c463`
- Semantic implementation commit: `9ef648b7443beaa8e7143424dbbef8b890698dcf`
- `805722d7` is a documentation-only merge preserving intervening `docs/codex/TASKS.md` commits.
- Starting pre-remediation checkpoint: `a578607580f8ec97cc660126bdd663403971ec13`.
- This ledger lives only on `review/finding-a-consolidation`; it is not an implementation commit.
- Finding B remains out of scope.

## Review rule

Only production-reachable, independently verified Finding A defects on the frozen review target belong here. Old findings are re-evaluated against the new implementation rather than carried forward mechanically. Closely related residuals are consolidated.

## Confirmed remaining blockers

### A1. P2 — `LOWQUALITY-NO-CANDIDATE-CANCEL-RACE-01`

`LowQualityRedownloadWorker.scan()` can reach `finishNoCandidates()` after its last cancellation check. `finishNoCandidates()` does not reject `cancelRequested=true`, and `LowQualityRedownloadDao.finishOperation()` only requires `state='RUNNING'`.

Race: `last ensureRunning passes -> requestCancellation commits cancelRequested=true -> finishNoCandidates -> COMPLETED/FAILED -> cancellation phase 2 sees terminal parent and cannot finish cancellation`.

Required: once cancellation commits first, no ordinary terminalizer may close the operation as non-cancellation terminal. Enforce the winner transactionally/at the DAO terminal CAS.

### A2. P2 — `WORKER-CLEANUP-SIBLING-FAULT-ISOLATION-01`

`DownloadWorker.cleanupStoppedWorker()` still performs durable cleanup for all active IDs inside one outer `try`/`forEach`. If A throws, B/C later siblings are skipped. External process cleanup may already have run, yet the bookkeeping pass releases every exact execution in the worker snapshot, including siblings not proved durably non-running.

This can leave B as `Active/PostProcessing EB`, native process gone, process-local owner released.

Startup `recoverAbandonedDownloadExecutions()` has the same batch-abort shape: one unrecoverable abandoned row prevents later unrelated rows from being recovered.

Required: isolate cleanup/recovery faults per Download and release an exact owner only after that row is durably non-running, superseded, or represented by explicit durable recovery debt.

### A3. P2 — `CROSS-DOMAIN-DOWNLOAD-CANCEL-TERMINAL-ROW-01`

`CancelDownloadNotificationReceiver` now validates exact Download execution authority, but after successful Download cancellation it still calls `terminalDao.delete(downloadId)`.

Download IDs and Terminal IDs are independent domains. Cancelling Download N can delete unrelated Terminal row N. Terminal process identity is now separately namespaced, so the unrelated Terminal process may remain running after its DB row disappears.

Required: a Download-domain cancellation capability may mutate only Download-domain state/resources. Terminal row deletion belongs only to the Terminal-domain cancellation path.

### A4. P2 — `WORKER-OWNERSHIP-HISTORY-CLEANUP-LEASE-01`

`HistoryReferenceMutationCoordinator` substantially fixes retained-reference serialization, but `DownloadWorker.deleteValidatedReplacementPaths()` only performs one exact Download execution point-check before retained-reference validation and physical filesystem deletion. It does not hold the per-Download execution side-effect lease across the whole destructive interval.

Race: `E1 check passes -> Pause/Cancel E1 -> Resume/E2 claims -> stale E1 continues rejected/replaced-media deletion`.

Required: exact execution ownership must cover the entire destructive cleanup interval. Compose the History reference lock and Download execution lease with one canonical lock order.

### A5. P2 — `WORKER-EXECUTION-LOCK-LEASE-ORDER-DEADLOCK-01`

Canonical worker long-side-effect code acquires `side-effect lease -> global execution lock`. Several cancellation paths reverse this to `global execution lock -> side-effect lease`, including `pauseAllDownloads()`, `cancelAllDownloadsImpl()`, and `CancelScheduledDownloadWorker`.

This creates a real AB/BA deadlock: worker E1 holds its lease and waits for the global lock while bulk/scheduled cancellation holds the global lock and waits for E1's lease. The global lock then blocks unrelated claims/cancellations/recovery too.

Required: one canonical acquisition order everywhere. Never hold the global execution lock while waiting for a side-effect lease. Multi-item lease acquisition must be deterministic.

### A6. P2 — `LOWQUALITY-CANCELLATION-PHASE2-CONVERGENCE-DEBT-01`

`LowQualityRedownloadManager.cancel()` durably commits phase 1, then runs phase 2 in the same coroutine. If `completePersistedCancellationWithPublications()` fails transiently, the coroutine ends; no same-process retry/debt owns the already-durable `cancelRequested / CANCELLATION_REQUESTED` state.

Startup/reconnect can repair it later, but same-process completion currently depends on an unrelated reconnect/restart.

Required: durable cancellation phase 1 itself must create idempotent convergence responsibility until phase 2 reaches durable cancellation terminal state.

### A7. P2 — `LOWQUALITY-TERMINAL-DEBT-RETRY-RACE-01`

The new live terminal-convergence loop derives child terminal state from the **current mutable Download status**.

Race:
1. worker durably writes `Download=Error`;
2. linked `FAILED` transition throws, leaving child ACTIVE/QUEUED/WAITING;
3. asynchronous convergence is scheduled;
4. Retry/Reconfigure runs first;
5. because the child and parent are still nonterminal/coherent, `hasTerminalHistoryReplacementLedger()` does not block the retry;
6. Error snapshot CAS moves the Download to Queued/Processing;
7. convergence rereads the new state and can no longer derive the original Error -> FAILED observation.

For retryable issues such as network failures, SAME_SETTINGS retry is allowed; reconfigure may also be allowed.

Required: make the convergence debt itself durable/authoritative, or block every state-changing retry/reconfigure path until that exact debt has converged. Do not derive an authoritative terminal decision solely from a mutable status that UI transitions may replace.

### A8. P2 — `HISTORY-STALE-FULLROW-WRITER-REPLACEMENT-01`

`HistoryDao.update()` now participates in `HistoryReferenceMutationCoordinator`, which serializes it with replacement/deletion. However serialization alone does not prevent a stale full-row write **after** a replacement commits.

Concrete production path: VideoPlayer holds an old `HistoryItem`; a regular/quality replacement commits new `downloadPath`, `downloadId` and replacement metadata; custom-thumbnail persistence later runs `item.copy(thumb=...)` and `historyDao.update(updated)`. The DAO rereads/preserves only materialized `keywords`, then writes all other stale fields.

The stale UI write can restore the old `downloadPath`, old `downloadId`, URL/metadata, etc. Reverting `downloadId` also destroys the durable semantic-commit detector (`History.downloadId == replacement Download.id`) and can make an already committed replacement appear uncommitted/replayable.

Required: metadata/thumbnail UI writes must update only intended columns or merge against the current History row. A stale pre-replacement `HistoryItem` must never overwrite replacement-owned path/download identity or semantic-commit state.

### A9. P2 — `HISTORY-POSTCOMMIT-FINALIZATION-DEBT-01`

Core post-History-commit replay semantics are much better: the committed History row identifies the replacement, quality child success is recorded in the same semantic transaction, and startup recovery finalizes committed work instead of replaying it.

A residual remains when final Download cleanup itself fails. If `completeAndDelete()` repeatedly fails after semantic commit, worker failure enters `cleanupStoppedWorker()`. That cleanup treats an issue-free committed replacement like ordinary Active work and calls exact `requeueActiveDownload()`, which can set it to Queued without checking low-quality terminal/runnable authority.

For a quality replacement, the child is already `SUCCEEDED` from the semantic commit. Normal queue selectors/claim correctly reject terminal low-quality children, so the resulting `Download=Queued + child=SUCCEEDED` carrier is non-runnable and can remain indefinitely. It cannot self-enter the next worker to execute the committed-finalization fast path.

For a regular replacement the row may eventually be picked by a later unrelated worker, but there is still no explicit same-process terminal-convergence responsibility after finalization failure.

Required: a committed History replacement must have guaranteed idempotent finalization debt. Stopped-worker cleanup must recognize the committed semantic fact and finalize/retry finalization rather than converting it to an ordinary queue carrier. Never create a non-runnable Queued quality ghost after semantic commit.

## Source-level findings re-evaluated as closed or substantially closed

### Low-quality runnable cancellation authority — source-level closed

Queue/select/claim predicates now reject child `CANCELLATION_REQUESTED` and parent `cancelRequested=1`.

### Cancellation-pending child terminal race — source-level closed

Generic linked writes use explicit source-state allowlists; cancellation-pending/parent-cancelled state converges to CANCELLED rather than FAILED.

### Bulk Error deletion — source-level closed

`deleteErrored()` now uses the safe linked-child user-removal path and returns affected operation IDs for refresh.

### Undo terminal/cancelling parent restore — source-level closed

Restore checks parent live authority, `cancelRequested=false`, and exact pending-token ownership before recreating runnable state.

### Undo live-token owner lifetime — source-level substantially closed

Pending snapshots are process-level and owner-tagged; ViewModel destruction abandons owned snapshots, unregisters live tokens, and best-effort commits so later reconciliation can recover.

### Notification builder/action attempt isolation — source-level substantially closed

Builders are per-call, Download running action identity includes execution identity, blank Download Cancel fails closed, stale Pause issues Resume only after exact transition success, and retry/reconfigure carries operation/attempt identity. A3 is a separate DB-domain residual.

### Retry stale-transition authority — source-level substantially closed

Retry validates operation/attempt and uses snapshot CAS. A7 is a separate convergence-debt race.

### yt-dlp retry/preparation stale side effects — source-level closed

Retry cache deletion, notification/log/request preparation and process startup are exact-execution guarded.

### Native process start publication gap — source-level closed

The per-Download side-effect lease covers final ownership validation through actual native process registration.

### Download/Terminal process identity — source-level substantially closed

Download process IDs are execution-scoped and Terminal process IDs are Terminal-domain namespaced. A3 remains at the DB receiver layer.

### History retained-reference deletion TOCTOU — source-level substantially closed

`HistoryReferenceMutationCoordinator` serializes prepared deletion/replacement cleanup with History insert/restore/replacement and `HistoryDao.update()` full-row writes. Direct raw reference writers inspected so far are used under the coordinator. Continue raw-writer inventory before final closure. A4 is a distinct Download execution-lease issue.

### History semantic commit / destructive replay — source-level substantially closed

Regular and quality replacement commit detection is durable in the History row, quality linked success is committed with replacement, ancillary post-commit failures become warnings, and startup abandoned recovery finalizes committed replacements rather than replaying them. A8 and A9 are external overwrite/finalization residuals.

### Cancellation registry rollback publication — source-level substantially closed

Repository cancellation paths collect publications inside Room transactions and publish only after successful commit. A6 is operation-level phase-2 responsibility, not the old registry rollback defect.

## Remaining candidates under review

### C1. Forced-stop process-before-DB ordering

`CancelScheduledDownloadWorker` can destroy an exact process before durable requeue/convergence. Determine whether a subsequent DB failure is fully recovered by DownloadWorker cleanup or produces a distinct single-item stranded state. Fold into A2 if it is only another cleanup-convergence instance.

### C2. Remaining History raw reference writers

Finish inventory of direct `HistoryDao.insertRaw/insertAndGetIdRaw/updateRaw` callsites and any writer capable of changing `downloadPath/localTreeUri/localTreePath`. Fold any bypass into retained-reference correctness rather than creating duplicates.

### C3. Cross-domain Android notification integer collision

Download running notification IDs and Terminal foreground notification IDs occupy arithmetic ranges that can overlap for sufficiently large IDs. Actions are now domain-scoped; current proven impact is notification overwrite/removal. Promote only if a Finding A terminal/authority consequence is demonstrated.

### C4. Cross-domain WorkerProgress/EventBus identity

Download and Terminal progress still use the same numeric `WorkerProgress.downloadItemID` namespace. Same-number rows can display each other's progress/output. Current proven impact is UI attribution only; keep non-blocking unless stronger Finding A impact is found.

### C5. Backup/restore revalidation

Re-run the Finding A backup/restore fail-closed audit after the broad 9ef changes. No schema/backup-format change was reported, but final review still needs to prove refusal barriers, quality authority revocation and restored queue admission remain coherent.

## Verification evidence gap

Reported on `805722d7`:

- `git diff --check` — PASS
- `:app:kspDebugKotlin` — PASS
- focused JVM tests — ATTEMPTED, NOT COMPLETED
- `:app:compileDebugKotlin -x lint` — ATTEMPTED, NOT COMPLETED
- `:app:compileDebugAndroidTestKotlin` — ATTEMPTED, NOT COMPLETED
- instrumentation — NOT EXECUTED

No GitHub check evidence is registered for the formal review HEAD.

Therefore Finding A cannot be declared CLEAN from the current evidence even after source residuals are fixed.

## Review completion criteria

Do not derive the implementation prompt until:

1. the full Finding A lifecycle has been re-reviewed on `805722d7`: admission -> claim -> execution -> external/destructive side effects -> semantic commit -> terminalization -> retry/requeue -> cancellation -> Undo -> stopped cleanup -> process death/startup/restore -> notification;
2. every old blocker is explicitly reclassified against the new code;
3. all History reference-changing raw/full-row writers are inventoried;
4. Download/Terminal process, notification, DB, UI capability and synchronization domains are checked for concrete Finding A impact;
5. backup/restore remains fail-closed;
6. a final independent source pass yields no additional P1/P2 Finding A defect beyond this ledger;
7. remaining non-blocking candidates are documented and deliberately excluded.

## Current status

`NOT_READY_FOR_IMPLEMENTATION_PROMPT`
