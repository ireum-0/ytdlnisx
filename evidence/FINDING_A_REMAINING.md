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

Only production-reachable, independently verified Finding A defects on the frozen review target belong here. Old findings are re-evaluated against the new implementation rather than carried forward mechanically. Closely related residuals are consolidated. Do not derive the next implementation prompt until the completion criteria at the end of this file are satisfied.

## Confirmed remaining blockers

### A1. P2 — `LOWQUALITY-NO-CANDIDATE-CANCEL-RACE-01`

`LowQualityRedownloadWorker.scan()` can reach `finishNoCandidates()` after its last cancellation check. `finishNoCandidates()` does not reject `cancelRequested=true`, and `LowQualityRedownloadDao.finishOperation()` only requires `state='RUNNING'`.

Race: `last ensureRunning passes -> requestCancellation commits cancelRequested=true -> finishNoCandidates -> COMPLETED/FAILED -> cancellation phase 2 sees terminal parent and cannot finish cancellation`.

`failCoordinator()` does honor `cancelRequested`; this residual is specifically the no-candidate/non-cancellation terminal boundary.

Required: once cancellation commits first, no ordinary terminalizer may close the operation as non-cancellation terminal. Enforce the winner transactionally/at the DAO terminal CAS.

### A2. P2 — `WORKER-CLEANUP-SIBLING-FAULT-ISOLATION-01`

`DownloadWorker.cleanupStoppedWorker()` still performs durable cleanup for all active IDs inside one outer `try`/`forEach`. If A throws, B/C later siblings are skipped. External process cleanup may already have run, yet the bookkeeping pass releases worker execution owners from the snapshot even for siblings not proved durably non-running.

This can leave B as `Active/PostProcessing EB`, native process gone, process-local owner released.

Startup `recoverAbandonedDownloadExecutions()` has the same batch-abort shape: one unrecoverable abandoned row prevents later unrelated rows from being recovered.

Required: isolate cleanup/recovery faults per Download and release an exact owner only after that row is durably non-running, superseded, or represented by explicit durable recovery debt. Preserve the original worker failure while aggregating cleanup failures.

### A3. P2 — `CROSS-DOMAIN-DOWNLOAD-CANCEL-TERMINAL-ROW-01`

`CancelDownloadNotificationReceiver` now validates exact Download execution authority, but after successful Download cancellation it still calls `terminalDao.delete(downloadId)`.

Download IDs and Terminal IDs are independent domains. Cancelling Download N can delete unrelated Terminal row N. Terminal process identity is separately namespaced, so the unrelated Terminal process may remain running after its DB row disappears.

`CancelTerminalNotificationReceiver` is correctly domain-separated; this residual is the stray Terminal mutation in the Download-domain receiver.

Required: a Download-domain cancellation capability may mutate only Download-domain state/resources. Terminal row deletion belongs only to the Terminal-domain cancellation path.

### A4. P2 — `WORKER-OWNERSHIP-HISTORY-CLEANUP-LEASE-01`

`HistoryReferenceMutationCoordinator` substantially fixes retained-reference serialization, but `DownloadWorker.deleteValidatedReplacementPaths()` only performs one exact Download execution point-check before retained-reference validation and physical filesystem deletion. It does not hold the per-Download execution side-effect lease across the whole destructive interval.

Race: `E1 check passes -> Pause/Cancel E1 -> Resume/E2 claims -> stale E1 continues rejected/replaced-media deletion`.

Required: exact execution ownership must cover the entire destructive cleanup interval. Compose the History reference lock and Download execution lease with one canonical lock order. Stale E1 must never delete E2 output or retained History media.

### A5. P2 — `WORKER-EXECUTION-LOCK-LEASE-ORDER-DEADLOCK-01`

The execution-side-effect lease and global worker execution lock do not have one safe lifetime/order contract.

Canonical worker long-side-effect code enters `withOwnedExecutionLease()` as:

`per-Download side-effect lease -> global execution lock -> ownership check -> long external side effect`.

Several cancellation/bulk/scheduled paths have used or still use the reverse family of ordering, `global execution lock -> side-effect lease`, creating a real AB/BA deadlock. In addition, the current `withOwnedExecutionLease()` keeps the global execution lock while the long file move/HardSub/native or filesystem side effect itself runs. That unnecessarily serializes unrelated Download claims, pause/cancel, and recovery behind one Download's potentially long external work.

Required:

- establish one canonical lock acquisition order everywhere;
- never hold the global execution lock while waiting for a side-effect lease;
- keep the global execution lock limited to the short exact-ownership/DB decision, not the long external side-effect lifetime;
- retain the per-Download lease across the external operation so Pause/Cancel/E2 cannot overlap shared resources;
- multi-item lease acquisition must remain deterministic.

### A6. P2 — `LOWQUALITY-CANCELLATION-PHASE2-CONVERGENCE-DEBT-01`

`LowQualityRedownloadManager.cancel()` durably commits phase 1, then runs phase 2 once in the same manager coroutine. If `completePersistedCancellationWithPublications()` fails transiently, the coroutine ends; no same-process retry/debt owns the already-durable `cancelRequested / CANCELLATION_REQUESTED` state.

Startup/reconnect can repair it later, but same-process completion currently depends on an unrelated reconnect/restart. This differs from Download-terminal ledger convergence, which now has an explicit live retry loop.

Required: durable cancellation phase 1 itself must create idempotent convergence responsibility until phase 2 reaches durable cancellation terminal state.

### A7. P2 — `LOWQUALITY-TERMINAL-DEBT-RETRY-RACE-01`

The new live terminal-convergence loop treats the durable Download terminal row as its debt but re-derives child terminal state from the current mutable Download status on every retry.

Race:

1. worker durably writes `Download=Error`;
2. linked `FAILED` transition throws, leaving child ACTIVE/QUEUED/WAITING;
3. asynchronous convergence is scheduled;
4. Retry/Reconfigure runs first;
5. because the child and parent are still nonterminal/coherent, the retry path is not blocked by a terminal ledger fact;
6. Error snapshot CAS moves the Download to Queued/Processing;
7. convergence rereads the new state and can no longer derive the original Error -> FAILED observation.

For retryable issues such as network failures, SAME_SETTINGS retry is allowed; reconfigure may also be allowed.

Required: make the convergence debt itself durable/authoritative, or block every state-changing retry/reconfigure path until that exact debt has converged. Do not derive an authoritative terminal decision solely from mutable status that UI transitions may replace.

### A8. P2 — `HISTORY-STALE-FULLROW-REFERENCE-WRITERS-01`

`HistoryReferenceMutationCoordinator` serializes the moment a History full-row write executes, but it does not make a snapshot captured before the lock authoritative. Two concrete production stale full-row paths remain.

#### A8a. Folder migration

`FolderSettingsFragment.migrateDefaultVideoFolderInternal()`:

- snapshots History rows with `historyDao.getAll()`;
- performs potentially long external file moves;
- later calls `historyDao.update(item.copy(downloadPath = updatedPaths))` using the old full-row snapshot;
- relies only on a one-time `activeDownloadCount == 0` precheck.

A queued History replacement can start and commit while migration is running. The stale migration write can then overwrite replacement-owned `downloadId`, source/metadata fields, paths, and other semantic state. Its physical move also occurs outside the History reference coordinator.

#### A8b. VideoPlayer custom-thumbnail save

`VideoPlayerActivity` still takes a playback-queue `HistoryItem` snapshot, saves a thumbnail, creates `item.copy(thumb = newPath)`, and calls `historyDao.update(updated)`. `HistoryDao.update()` acquires the coordinator only after that stale snapshot already exists, then performs a full-row `updateRaw` while merely preserving materialized keywords.

Race: `old player snapshot -> replacement commits -> player thumbnail write acquires coordinator -> stale full-row overwrites replacement-owned downloadId/path/metadata while changing only thumb`.

This can also destroy the durable History semantic-commit detector (`History.downloadId == replacement Download.id`).

Required: every stale-capable UI/background History mutation must reread the current authoritative row inside the canonical synchronization boundary and update only the fields it owns. A pre-lock full-row snapshot must never overwrite replacement-owned identity, reference, source/type, or semantic-commit state. Folder file movement must participate in the same reference-mutation serialization as replacement/deletion.

### A9. P2 — `HISTORY-POSTCOMMIT-FINALIZATION-DEBT-01`

Core post-History-commit replay semantics are much better: the committed History row identifies the replacement, quality child success is recorded in the same semantic transaction, and startup recovery finalizes committed work instead of replaying it.

A residual remains when final Download cleanup itself fails. If `completeAndDelete()` fails after semantic commit, worker failure enters stopped/error cleanup. Issue-free committed replacement rows can still be treated like ordinary running work and requeued rather than retained as committed-finalization debt.

For a quality replacement, the child is already `SUCCEEDED` from the semantic commit. An ordinary exact-token requeue can create `Download=Queued + child=SUCCEEDED`; normal runnable predicates then reject it forever. For a regular replacement there is also no explicit same-process finalization responsibility after final cleanup failure.

Required: a committed History replacement must have guaranteed idempotent finalization responsibility. Cleanup/recovery must recognize the committed semantic fact and finalize/retry finalization rather than converting it into an ordinary queue carrier.

### A10. P2 — `HISTORY-REFERENCE-LOCK-ROOM-ORDER-DEADLOCK-01`

`HistoryReferenceMutationCoordinator` is a process-global non-reentrant `Mutex`.

History replacement/reference mutation paths such as `HistoryKeywordAssignmentRepository.replaceHistoryPreservingAssignmentsAuthorized()` use:

`HistoryReferenceMutationCoordinator -> Room transaction`.

But production metadata persistence in `HistoryViewModel.updateWithKeywordNotice()` uses:

`Room transaction -> HistoryRepository.update() -> HistoryDao.update() -> HistoryReferenceMutationCoordinator`.

This creates an AB/BA deadlock:

1. metadata edit opens/holds a Room transaction, then waits for the History reference mutex;
2. replacement/deletion holds the History reference mutex, then waits to enter the Room transaction;
3. neither can progress.

Required: establish one canonical History-reference/Room transaction acquisition order. Do not acquire the process mutex from a DAO compatibility write while already inside a Room transaction that can contend with a `mutex -> transaction` path.

### A11. P2 — `LOWQUALITY-COORDINATOR-FAILURE-CLAIM-RACE-01`

Final coordinator failure does not first establish a durable revoke state before snapshotting execution ownership.

`failCoordinatorWithPublications()` currently:

1. reads nonterminal linked Download IDs;
2. snapshots execution IDs only for rows that are already executing;
3. acquires leases only for those snapshot tokens;
4. later enters the terminalization transaction while the operation was still RUNNING and not cancelRequested during the snapshot window.

A queued linked Download can therefore be claimed after the snapshot but before terminalization. `cancelLinkedDownloadsAndCollectOwnership()` then rereads the now-Active row and skips it because its nonblank execution token does not equal the missing/stale expected snapshot token. The same transaction can still mark the linked child FAILED and parent FAILED.

Race: `Queued child -> coordinator final-failure snapshot sees blank execution -> DownloadWorker claims E1 -> failCoordinator rereads Active E1 but skips exact Download cancellation -> child FAILED + parent FAILED -> E1 remains Active and continues work under terminal authority`.

The final History boundary is fail-closed, but privileged network/external execution has already escaped the coordinator-failure revoke point.

Required: coordinator failure must serialize with new claims and establish durable revoke authority before/with execution ownership capture. It must either prevent the claim from succeeding or cancel/converge the exact execution that won. Never leave an Active execution attached to a terminal failed child/parent.

### A12. P2 — `HISTORY-POSTCOMMIT-LATE-STOP-RECLASSIFICATION-01`

History semantic commit is durable, but user Pause/Cancel repository paths do not treat that commit as an irreversible boundary.

After `replaceHistoryPreservingAssignmentsAuthorized...` returns Updated, the History row already points to the replacement Download and, for quality replacement, the linked child is already `SUCCEEDED` in the same semantic transaction. Before final `completeAndDelete()`, however, `DownloadRepository.cancelByUser()` and generic pause status transitions can still change the exact E1 Download row to `Cancelled` or `Paused` without first checking the committed History fact. Cancellation publication can then stop the execution carrier.

The worker deliberately skips ordinary `shouldStopForUserRequest()` exits after `historyReplacementCommitted=true`, so a normal path may later finalize correctly. But if finalization or another late step fails, the process-local cancellation record/Paused-or-Cancelled row causes the outer terminal path to treat the committed operation as canceled, and the committed carrier can remain in a contradictory stop state. The row is also externally observable/reclassifiable during that window.

Required: once History semantic commit wins, later Pause/Cancel must not rewrite the committed primary result as a pre-commit stop. Such an action must either become a no-op for primary semantics while allowing idempotent finalization, or be represented as post-commit ancillary intent that cannot destroy the committed-success carrier. Cover single, notification, and bulk pause/cancel entrypoints consistently.

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

`HistoryReferenceMutationCoordinator` serializes prepared deletion/replacement cleanup with the audited canonical History insert/restore/replacement paths. A4, A8 and A10 remain the concrete residuals: execution-lease coverage, stale/bypassing full-row/reference mutations, and lock-order deadlock.

### History semantic commit / destructive replay — source-level substantially closed

Regular and quality replacement commit detection is durable in the History row, quality linked success is committed with replacement, ancillary post-commit failures are treated as warnings, and startup abandoned recovery finalizes committed replacements rather than replaying them. `downloads.id` is `INTEGER PRIMARY KEY AUTOINCREMENT`, so ordinary deleted Download IDs are not reused as a new semantic commit identity. A8, A9 and A12 remain the semantic-commit integrity/finalization/late-stop residuals.

### Cancellation registry rollback publication — source-level substantially closed

Repository cancellation paths collect publications inside Room transactions and publish only after successful commit. A6 is operation-level phase-2 responsibility, not the old registry rollback defect.

### Backup/restore Finding A fail-closed behavior — source-level substantially closed

Backup projects persisted History refusal barriers into backed-up Download issue fields. Restore remaps History markers, reconstructs refusal barriers when identity is sufficient, fails closed when remap/identity is insufficient, and revokes orphan quality markers because the low-quality authority graph is not backed up. Whole-backup consistent-snapshot concerns belong to dedicated backup findings, not this Finding A residual ledger.

## Remaining non-blocking candidates / deliberate exclusions

### C1. Forced-stop process-before-DB ordering — folded into A2/A5

`CancelScheduledDownloadWorker` remains part of the cleanup/lock-order audit. Do not create another blocker unless a distinct terminal semantic outcome appears; address its synchronization in A5 and durable cleanup responsibility in A2.

### C2. History raw/reference writer inventory — substantially complete

Audited raw insert/update replacement paths in `HistoryKeywordAssignmentRepository` participate in `HistoryReferenceMutationCoordinator`. Direct production stale full-row writes identified so far are the VideoPlayer custom-thumbnail path and Folder migration, both folded into A8. Column-only playback/last-watched/date/hard-sub flags do not overwrite History reference or semantic-commit identity. Continue spot-checking any newly discovered full-row writer during the final independent pass.

### C3. Cross-domain Android notification integer collision — non-blocking for Finding A

Download progress IDs and Terminal progress/foreground IDs can numerically collide for certain different row IDs. Actions are now domain-scoped with separate receivers/URIs. Current proven impact is notification overwrite/removal, not cross-domain destructive authority. Keep outside Finding A P1/P2 unless stronger impact is demonstrated.

### C4. Cross-domain WorkerProgress/EventBus identity — non-blocking

Download and Terminal progress still use the same numeric `WorkerProgress.downloadItemID` namespace. Same-number rows can display each other's progress/output. Current proven impact is UI attribution only.

### C5. Whole-backup snapshot consistency — separate finding territory

The Finding A-specific restore authority checks are fail-closed. Any broader backup snapshot-consistency issue belongs to the dedicated backup findings and should not expand Finding A.

### C6. Stale scan checkpoint after cancellation — folded into A6

A long scan inspection can finish after phase-1 cancellation and checkpoint a PROVISIONAL candidate while the parent is still RUNNING+cancelRequested. It does not create a linked runnable Download, and cancellation phase 2 terminalizes the nonterminal item; if phase 2 already finished, the checkpoint transaction fails/rolls back because the operation is no longer RUNNING. Treat the temporary drift as part of A6's guaranteed cancellation convergence responsibility rather than a separate blocker unless a stronger privilege path is demonstrated.

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
