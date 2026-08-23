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

`failCoordinator()` does honor `cancelRequested`; this residual is specifically the no-candidate/non-cancellation terminal boundary.

Required: once cancellation commits first, no ordinary terminalizer may close the operation as non-cancellation terminal. Enforce the winner transactionally/at the DAO terminal CAS.

### A2. P2 — `WORKER-CLEANUP-SIBLING-FAULT-ISOLATION-01`

`DownloadWorker.cleanupStoppedWorker()` still performs durable cleanup for all active IDs inside one outer `try`/`forEach`. If A throws, B/C later siblings are skipped. External process cleanup may already have run, yet the bookkeeping pass releases every exact execution in the worker snapshot, including siblings not proved durably non-running.

This can leave B as `Active/PostProcessing EB`, native process gone, process-local owner released.

Startup `recoverAbandonedDownloadExecutions()` has the same batch-abort shape: one unrecoverable abandoned row prevents later unrelated rows from being recovered.

Required: isolate cleanup/recovery faults per Download and release an exact owner only after that row is durably non-running, superseded, or represented by explicit durable recovery debt.

### A3. P2 — `CROSS-DOMAIN-DOWNLOAD-CANCEL-TERMINAL-ROW-01`

`CancelDownloadNotificationReceiver` now validates exact Download execution authority, but after successful Download cancellation it still calls `terminalDao.delete(downloadId)`.

Download IDs and Terminal IDs are independent domains. Cancelling Download N can delete unrelated Terminal row N. Terminal process identity is separately namespaced, so the unrelated Terminal process may remain running after its DB row disappears.

Required: a Download-domain cancellation capability may mutate only Download-domain state/resources. Terminal row deletion belongs only to the Terminal-domain cancellation path.

### A4. P2 — `WORKER-OWNERSHIP-HISTORY-CLEANUP-LEASE-01`

`HistoryReferenceMutationCoordinator` substantially fixes retained-reference serialization, but `DownloadWorker.deleteValidatedReplacementPaths()` only performs one exact Download execution point-check before retained-reference validation and physical filesystem deletion. It does not hold the per-Download execution side-effect lease across the whole destructive interval.

Race: `E1 check passes -> Pause/Cancel E1 -> Resume/E2 claims -> stale E1 continues rejected/replaced-media deletion`.

Required: exact execution ownership must cover the entire destructive cleanup interval. Compose the History reference lock and Download execution lease with one canonical lock order.

### A5. P2 — `WORKER-EXECUTION-LOCK-LEASE-ORDER-DEADLOCK-01`

Canonical worker long-side-effect code acquires `side-effect lease -> global execution lock`. `LowQualityRedownloadManager` follows this same order. Several other cancellation paths reverse it to `global execution lock -> side-effect lease`, including `pauseAllDownloads()`, `cancelAllDownloadsImpl()`, and `CancelScheduledDownloadWorker`.

This creates a real AB/BA deadlock: worker E1 holds its lease and waits for the global lock while bulk/scheduled cancellation holds the global lock and waits for E1's lease. The global lock then blocks unrelated claims/cancellations/recovery too.

Required: one canonical acquisition order everywhere. Never hold the global execution lock while waiting for a side-effect lease. Multi-item lease acquisition must be deterministic.

### A6. P2 — `LOWQUALITY-CANCELLATION-PHASE2-CONVERGENCE-DEBT-01`

`LowQualityRedownloadManager.cancel()` durably commits phase 1, then runs phase 2 once in the same manager coroutine. If `completePersistedCancellationWithPublications()` fails transiently, the coroutine ends; no same-process retry/debt owns the already-durable `cancelRequested / CANCELLATION_REQUESTED` state.

Startup/reconnect can repair it later, but same-process completion currently depends on an unrelated reconnect/restart. This differs from Download-terminal ledger convergence, which now has an explicit live retry loop.

Required: durable cancellation phase 1 itself must create idempotent convergence responsibility until phase 2 reaches durable cancellation terminal state.

### A7. P2 — `LOWQUALITY-TERMINAL-DEBT-RETRY-RACE-01`

The new live terminal-convergence loop treats the durable Download terminal row as its debt but re-derives child terminal state from the **current mutable Download status** on every retry.

Race:
1. worker durably writes `Download=Error`;
2. linked `FAILED` transition throws, leaving child ACTIVE/QUEUED/WAITING;
3. asynchronous convergence is scheduled;
4. Retry/Reconfigure runs first;
5. because the child and parent are still nonterminal/coherent, `hasTerminalHistoryReplacementLedger()` does not block the retry;
6. Error snapshot CAS moves the Download to Queued/Processing;
7. convergence rereads the new state and can no longer derive the original Error -> FAILED observation.

For retryable issues such as network failures, SAME_SETTINGS retry is allowed; reconfigure may also be allowed.

Required: make the convergence debt itself durable/authoritative, or block every state-changing retry/reconfigure path until that exact debt has converged. Do not derive an authoritative terminal decision solely from mutable status that UI transitions may replace.

### A8. P2 — `HISTORY-FOLDER-MIGRATION-REPLACEMENT-RACE-01`

The previously suspected VideoPlayer custom-thumbnail path has been re-evaluated as closed: `HistoryViewModel.updateWithKeywordNotice()` rereads the current History row and merges only allowed metadata fields before writing.

A concrete stale full-row/reference mutation path remains in `FolderSettingsFragment.migrateDefaultVideoFolderInternal()`:

- it snapshots History rows with `historyDao.getAll()`;
- it performs potentially long external file moves;
- it later calls `historyDao.update(item.copy(downloadPath = updatedPaths))` using the old full-row snapshot;
- the only precondition is a one-time `activeDownloadCount == 0` check before migration.

A queued History replacement may start and commit while migration is running. The subsequent stale migration write is serialized by `HistoryDao.update()` but is not merged against the current row, so it can overwrite replacement-owned `downloadId`, metadata, URL/type fields, etc. Reverting `downloadId` can also destroy the durable semantic-commit detector (`History.downloadId == replacement Download.id`). The physical move itself occurs outside `HistoryReferenceMutationCoordinator`.

Required: serialize the file-reference migration with replacement/deletion and update only the intended path/reference columns from a current authoritative row. A stale pre-replacement History snapshot must never overwrite replacement-owned identity or semantic-commit state.

### A9. P2 — `HISTORY-POSTCOMMIT-FINALIZATION-DEBT-01`

Core post-History-commit replay semantics are much better: the committed History row identifies the replacement, quality child success is recorded in the same semantic transaction, and startup recovery finalizes committed work instead of replaying it.

A residual remains when final Download cleanup itself fails. If `completeAndDelete()` fails after semantic commit, worker failure enters `cleanupStoppedWorker()`. That cleanup treats an issue-free committed replacement like ordinary Active work and calls exact `requeueActiveDownload()`.

For a quality replacement, the child is already `SUCCEEDED` from the semantic commit. The singular exact-token requeue has no low-quality runnable guard, so it can create `Download=Queued + child=SUCCEEDED`. Normal queue selectors/claim then correctly reject the terminal child, leaving a permanently non-runnable Queued carrier that cannot enter the committed-finalization fast path.

For a regular replacement the row may eventually be observed by later work, but there is still no explicit same-process finalization responsibility after final cleanup failure.

Required: a committed History replacement must have guaranteed idempotent finalization debt. Stopped-worker cleanup must recognize the committed semantic fact and finalize/retry finalization rather than converting it to an ordinary queue carrier.

### A10. P2 — `HISTORY-REFERENCE-LOCK-ROOM-ORDER-DEADLOCK-01`

`HistoryReferenceMutationCoordinator` is a process-global non-reentrant `Mutex`.

History replacement/reference mutation paths such as `HistoryKeywordAssignmentRepository.replaceHistoryPreservingAssignmentsAuthorized()` use:

`HistoryReferenceMutationCoordinator -> Room transaction`.

But production metadata persistence in `HistoryViewModel.updateWithKeywordNotice()` uses:

`Room transaction -> HistoryRepository.update() -> HistoryDao.update() -> HistoryReferenceMutationCoordinator`.

This creates an AB/BA deadlock:

1. metadata edit opens/holds a Room transaction, then waits for the History reference mutex;
2. replacement holds the History reference mutex, then waits to enter the Room transaction;
3. neither can progress.

Required: establish one canonical History-reference/Room transaction acquisition order. Do not acquire the process mutex from a DAO compatibility write while already inside a Room transaction that can contend with a `mutex -> transaction` path.

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

Pending snapshots are process-level and owner-tagged. `DownloadViewModel.onCleared()` launches abandonment from an independent `SupervisorJob + Dispatchers.IO` scope rather than the cancelled `viewModelScope`; abandonment unregisters live tokens and best-effort commits exact snapshots so later reconciliation can recover.

### Notification builder/action attempt isolation — source-level substantially closed

Builders are per-call, Download running action identity includes execution identity, blank Download Cancel fails closed, stale Pause issues Resume only after exact transition success, and retry/reconfigure carries operation/attempt identity. A3 is a separate DB-domain residual.

### Retry stale-transition authority — source-level substantially closed

Single retry/reconfigure validates operation/attempt and uses snapshot CAS. A7 is a separate convergence-debt race.

### yt-dlp retry/preparation stale side effects — source-level closed

Retry cache deletion, notification/log/request preparation and process startup are exact-execution guarded.

### Native process start publication gap — source-level closed

The per-Download side-effect lease covers final ownership validation through actual native process registration.

### Download/Terminal process identity — source-level substantially closed

Download process IDs are execution-scoped and Terminal process IDs are Terminal-domain namespaced. A3 remains at the DB receiver layer.

### History retained-reference deletion TOCTOU — source-level substantially closed

`HistoryReferenceMutationCoordinator` serializes prepared deletion/replacement cleanup with the audited normal History insert/restore/replacement/full-row paths. A4, A8 and A10 remain the concrete residuals: execution-lease coverage, folder-migration bypass/stale write, and lock-order deadlock.

### History semantic commit / destructive replay — source-level substantially closed

Regular and quality replacement commit detection is durable in the History row, quality linked success is committed with replacement, ancillary post-commit failures are treated as warnings, and startup abandoned recovery finalizes committed replacements rather than replaying them. A8 and A9 are the remaining semantic-commit integrity/finalization residuals.

### Cancellation registry rollback publication — source-level substantially closed

Repository cancellation paths collect publications inside Room transactions and publish only after successful commit. A6 is operation-level phase-2 responsibility, not the old registry rollback defect.

### Backup/restore Finding A fail-closed behavior — source-level substantially closed

Backup projects persisted History refusal barriers into the backed-up Download issue fields. Restore remaps History markers, reconstructs refusal barriers when identity is sufficient, fails closed when remap/identity is insufficient, and revokes orphan quality markers because the low-quality authority graph is not backed up. Whole-backup consistent-snapshot concerns belong to the dedicated backup findings, not this Finding A residual ledger.

## Remaining non-blocking candidates / deliberate exclusions

### C1. Forced-stop process-before-DB ordering — folded into A2/A5

`CancelScheduledDownloadWorker` still destroys the exact process before requeue/convergence and also uses the reversed `global lock -> side-effect lease` order. Do not create another blocker unless a distinct semantic outcome appears; address its lock ordering in A5 and its durable cleanup responsibility in A2.

### C2. History raw/reference writer inventory — substantially complete

Audited raw insert/update paths in `HistoryKeywordAssignmentRepository` participate in `HistoryReferenceMutationCoordinator`. The concrete remaining bypass is folder migration and is now A8. Continue spot-checking new raw/reference writers during the final independent pass.

### C3. Cross-domain Android notification integer collision — non-blocking for Finding A

Download progress IDs use `90000 + downloadId`; Terminal progress/foreground IDs use `99000 + terminalId`, so sufficiently different numeric IDs can collide. Actions are now domain-scoped with separate receivers/URIs. Current proven impact is notification overwrite/removal, not cross-domain destructive authority. Keep outside Finding A P1/P2 unless stronger impact is demonstrated.

### C4. Cross-domain WorkerProgress/EventBus identity — non-blocking

Download and Terminal progress still use the same numeric `WorkerProgress.downloadItemID` namespace. Same-number rows can display each other's progress/output. Current proven impact is UI attribution only.

### C5. Whole-backup snapshot consistency — separate finding territory

The Finding A-specific restore authority checks are fail-closed. Any broader backup snapshot-consistency issue belongs to the dedicated backup findings and should not expand Finding A.

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
