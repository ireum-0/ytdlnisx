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

Only production-reachable, independently verified Finding A defects on the frozen review target belong in the confirmed blocker list. Old findings are not carried forward mechanically: every item is re-evaluated against the new implementation. Closely related residuals are consolidated rather than double-counted.

## Confirmed remaining blockers

### A1. P2 — `LOWQUALITY-NO-CANDIDATE-CANCEL-RACE-01` — STILL OPEN

`LowQualityRedownloadWorker.scan()` can reach the no-candidate terminal boundary after its last cancellation check. `finishNoCandidates()` does not reject `cancelRequested=true`, and `LowQualityRedownloadDao.finishOperation()` still requires only `state='RUNNING'`.

Reachable ordering:

`last ensureRunning passes -> requestCancellation commits cancelRequested=true -> finishNoCandidates -> finishOperation COMPLETED/FAILED -> cancellation phase 2 observes a terminal parent and cannot finish cancellation`.

Required invariant: once `cancelRequested=true` commits first, no non-cancellation operation terminalizer may subsequently close that RUNNING operation as COMPLETED/FAILED/PARTIAL_FAILURE. The winning condition must be enforced transactionally/at the DAO terminal CAS, not only by another worker-side precheck.

### A2. P2 — `WORKER-CLEANUP-SIBLING-FAULT-ISOLATION-01` — STILL OPEN

`DownloadWorker.cleanupStoppedWorker()` still performs durable requeue/refusal convergence for all active IDs inside one outer `try`/`forEach`. If A throws, later siblings B/C are never durably processed.

The earlier process-cleanup loop may already have destroyed B/C external execution. The later bookkeeping loop nevertheless removes/releases every exact execution present in the worker snapshot, not only rows proven durably non-running.

An unrelated B can therefore be left as:

`Download B = Active/PostProcessing EB`, native process gone, process-local execution owner released.

The production log itself reports that ownership may be released without a durable non-running guarantee.

`recoverAbandonedDownloadExecutions()` has the same batch failure shape during startup: one unrecoverable abandoned row throws and aborts later unrelated recoverable rows.

Required invariant: cleanup/recovery faults are isolated per Download. An exact execution owner is released only after that exact row is durably non-running, superseded by a newer owner, or represented by explicit durable recovery debt.

### A3. P2 — `CROSS-DOMAIN-DOWNLOAD-CANCEL-TERMINAL-ROW-01` — NEW RESIDUAL

Process and notification domains were largely separated, and Terminal now has its own cancellation receiver. However `CancelDownloadNotificationReceiver`, after an exact successful Download cancellation, still executes `terminalDao.delete(downloadId)`.

`DownloadItem.id` and `TerminalItem.id` are independent identity domains. Cancelling Download N can therefore delete an unrelated Terminal row N even though the Download capability contains no Terminal authority. Because Terminal process identity is now namespaced separately, this can leave the Terminal process running after its durable Terminal row was deleted.

Required invariant: a Download-domain cancellation capability may mutate only Download-domain state/resources. Terminal cancellation and Terminal row deletion must remain exclusively in the Terminal-domain path.

### A4. P2 — `WORKER-OWNERSHIP-HISTORY-CLEANUP-LEASE-01` — STILL OPEN

The new `HistoryReferenceMutationCoordinator` substantially closes the retained-History-reference TOCTOU by serializing important History reference writers with the physical deletion boundary.

A separate execution-ownership gap remains in `DownloadWorker.deleteValidatedReplacementPaths()`.

The helper holds the History reference coordinator, checks the current Download execution token/status once, then snapshots retained references and performs physical deletion. It does **not** hold the per-Download `DownloadWorkerExecutionSideEffectLease` across that destructive filesystem interval.

Reachable ordering:

`E1 exact execution point-check passes -> Pause/Cancel E1 commits -> Resume/requeue -> E2 claims same Download/resources -> stale E1 continues History/rejected-output filesystem deletion`.

Callers include both normal replaced-media cleanup and rejected quality-replacement output cleanup. The latter can delete files while a newer attempt is reusing the same output namespace.

Required invariant: exact execution ownership must remain valid for the whole destructive deletion interval, not only at one pre-delete read. The final solution must compose the History reference lock and Download execution lease with one consistent lock order.

### A5. P2 — `WORKER-EXECUTION-LOCK-LEASE-ORDER-DEADLOCK-01` — NEW

The new side-effect lease is useful, but lock acquisition order is inconsistent.

Canonical worker long-side-effect code uses:

`per-Download side-effect lease -> global Download execution lock -> exact revalidation -> release global lock -> long side effect under lease`.

Several cancellation paths instead use:

`global Download execution lock -> per-Download side-effect lease`,

including `pauseAllDownloads()`, `cancelAllDownloadsImpl()`, and `CancelScheduledDownloadWorker`.

This creates a real AB/BA deadlock window:

`T1 acquires lease -> T2 acquires global lock and waits for lease -> T1 attempts global lock and waits for T2`.

Because the second lock is suspending, the interleaving does not require either side to hold a lock for the full yt-dlp lifetime. Once the global execution lock is deadlocked, unrelated claim/cancel/recovery paths can also stall.

Required invariant: use one canonical acquisition order everywhere. Do not hold the global execution lock while waiting for one or more side-effect leases. Multi-item operations must acquire resource leases deterministically and keep global-lock sections brief.

### A6. P2 — `LOWQUALITY-CANCELLATION-PHASE2-CONVERGENCE-DEBT-01` — STILL OPEN

`LowQualityRedownloadManager.cancel()` durably commits phase 1 via `requestCancellation(operationId)`, then calls `completeCancellation(operationId)` in the same manager coroutine.

If `completePersistedCancellationWithPublications()` or its surrounding phase-2 path fails transiently, the coroutine ends. The `finally` block only refreshes notification/callback state; no same-process retry/debt is registered for the already-durable `cancelRequested / CANCELLATION_REQUESTED` state.

Startup/reconnect recovery can later notice `cancelRequested`, but that means same-process completion still depends on an unrelated reconnect or process restart.

The new `LowQualityRedownloadLedger.scheduleConvergence(downloadId)` solves a different debt: Download terminal write committed while linked child terminalization failed. It does not own operation-level cancellation phase 2.

Required invariant: durable cancellation phase 1 itself creates idempotent convergence responsibility until phase 2 reaches durable cancellation terminal state, in the same live process and across reconstruction.

### A7. P2 — `LOWQUALITY-TERMINAL-DEBT-RETRY-RACE-01` — NEW

The new live convergence loop derives a failed linked child from the **current mutable Download status**.

Reachable ordering:

1. primary worker failure durably writes `Download = Error`;
2. the independent linked-ledger `FAILED` transition throws, leaving child ACTIVE/QUEUED/WAITING;
3. `scheduleConvergence(downloadId)` starts asynchronously;
4. before convergence succeeds, the real Retry/Reconfigure path observes the Error row;
5. because the quality child and parent are still nonterminal/coherent, the History-replacement terminal-ledger guard does not block the retry;
6. snapshot CAS moves the same Download from Error to Queued/Processing;
7. `reconcileDownload()` can no longer derive the original failure from current status, because the linked-download policy maps Error -> FAILED but Queued/Processing -> no terminal state.

For a retryable primary issue such as `NETWORK_TIMEOUT`, SAME_SETTINGS retry is explicitly allowed; Reconfigure is also independently available when policy permits.

The original authoritative terminal observation can therefore be lost before its debt converges, and privileged work can become runnable again with a nonterminal child.

Required invariant: the terminal convergence fact must be durable or all state-changing retry/reconfigure paths must be blocked until that exact debt converges. Recovery cannot depend solely on a mutable Download status that the UI is allowed to change.

## Source-level findings re-evaluated as closed or substantially closed

The following previous blockers are not currently in the remaining list unless later review finds a new concrete residual.

### Low-quality runnable cancellation authority — source-level closed

The canonical runnable predicate and duplicated queue selectors now reject both child `CANCELLATION_REQUESTED` and parent `cancelRequested=1`.

### Cancellation-pending child terminal race — source-level closed

Generic linked state writes now use explicit source-state allowlists, while cancellation convergence has an explicit `markCancelledByDownloadId` path. Repository logic treats parent `cancelRequested` / child `CANCELLATION_REQUESTED` as cancellation instead of generic failure.

### Bulk Error deletion — source-level closed

`deleteErrored()` now uses the safe linked-child user-removal path and returns affected operation IDs for refresh. Saved/Processing direct deletion remains under reachability review but is not currently a confirmed blocker.

### Undo terminal/cancelling parent restore — source-level closed

Undo now checks parent nonterminal plus `cancelRequested=false` and exact pending-token ownership before inserting/rebinding runnable state. Revoked/terminal authority returns no restored runnable Download.

### Undo live-token owner lifetime — source-level substantially closed

Pending snapshots are now process-level and owner-tagged; `DownloadViewModel.onCleared()` abandons its owned snapshots, unregisters their live tokens, and best-effort commits them so reconciliation can recover a failed commit. Execution evidence is still missing.

### Notification builder/action attempt isolation — source-level substantially closed

Notification builders are per-call. Running Pause/Cancel/Resume PendingIntent identity includes entity/action/id/execution information. Blank Download notification Cancel authority fails closed. Stale Pause creates Resume only after the exact pause transition succeeds. Error Retry/Reconfigure carries operation/attempt capability identity.

A distinct cross-domain DB mutation residual remains as A3.

### Retry stale-transition authority — source-level substantially closed

Retry validates operation/attempt and uses snapshot-based queue CAS. Broad requeue excludes Active/PostProcessing rows and quality runnable guards include cancellation authority. A separate convergence-debt retry race remains as A7.

### yt-dlp retry/preparation stale side effects — source-level closed

Retry cache deletion, retry notification/log/request preparation and related side effects are protected by exact execution checks/ownership wrappers.

### Native process start publication gap — source-level closed

Download process identity is execution-scoped, and the per-Download execution side-effect lease covers final exact ownership validation through native process registration before cancellation can acquire the same lease.

### Download/Terminal process identity — source-level substantially closed

`YtdlpProcessIdentity` namespaces Download processes by Download ID + exact execution token and Terminal processes by Terminal domain. Terminal has a dedicated notification cancellation receiver. A separate DB-domain residual remains as A3.

### History retained-reference deletion TOCTOU — source-level substantially closed

`HistoryReferenceMutationCoordinator` now serializes prepared History deletion and replacement cleanup with major History reference-changing writers. `HistoryDao.update()` itself acquires the coordinator before its full-row `updateRaw`, so `HistoryRepository.update()` is not a bypass.

No current retained-reference writer bypass is confirmed. Continue inventorying direct raw reference writers before final closure. A4 is a distinct Download execution-lease problem, not a retained-reference DB serialization failure.

### History semantic commit / replay — source-level substantially closed

History replacement records the durable committed relationship in the History transaction; quality replacement also records linked success. Startup abandoned-execution recovery detects a committed replacement and finalizes it instead of requeueing it. Worker post-commit exceptions are routed to warning/finalization rather than generic History failure. Finalization-debt edges remain under review.

### Long destructive side-effect handoff — source-level substantially improved

A per-Download execution side-effect lease now guards major HardSub/move/start paths and Pause/Cancel acquire the same lease. A4 tracks the confirmed History/rejected-output deletion path that still uses only a point-in-time execution check.

### Cancellation registry rollback publication — source-level substantially closed

Repository cancellation APIs collect exact publication records inside Room transactions and publish only after the transaction completes. Low-quality phase-2 cancellation does the same. Operation-level phase-2 retry responsibility remains separately tracked in A6.

## Candidates under active review

### C1. History post-commit finalization debt

The committed History fact prevents destructive replay, including startup recovery. Verify whether repeated `completeAndDelete()` failure in a live worker can still be turned by stopped-worker cleanup into an ordinary Queued carrier with no guaranteed same-process finalization responsibility. If so, promote as a convergence blocker rather than a replay blocker.

### C2. Forced-stop process-before-DB ordering

`CancelScheduledDownloadWorker` still destroys the exact process before refusal convergence/requeue. Determine whether a subsequent DB failure leaves one exact Active execution with a dead process and no durable recovery debt. Fold into A2 if it is only another cleanup-convergence instance; create a separate blocker only if the single-item lifecycle has an independent failure mode.

### C3. Remaining History reference writers

Inventory all direct `HistoryDao.insertRaw/insertAndGetIdRaw/updateRaw` callsites and any writer capable of changing `downloadPath/localTreeUri/localTreePath`. Fold any real bypass into the retained-reference category rather than creating duplicates.

### C4. Regular History post-commit finalization

Verify the durable-commit detector and live/startup recovery for ordinary History redownloads as well as low-quality replacements, especially failure of `completeAndDelete()` after semantic commit.

### C5. Cross-domain running notification integer collision

Running Download notification ID is `90000 + downloadId` while Terminal uses `99000 + terminalId`. These ranges are not mathematically disjoint for arbitrary auto-increment IDs. Actions are domain-scoped, so confirmed impact is currently notification overwrite/removal rather than wrong-domain DB mutation. Promote only if Finding A terminal/authority impact is proved.

### C6. Cross-domain WorkerProgress/EventBus identity

Download and Terminal still publish `DownloadWorker.WorkerProgress` keyed by a bare numeric row ID and both UIs consume numeric tags. Same-number rows can display each other's progress/output. Current verified impact is UI attribution corruption; keep non-blocking unless stronger Finding A impact is established.

## Verification evidence gap

Reported on `805722d7`:

- `git diff --check` — PASS
- `:app:kspDebugKotlin` — PASS
- focused JVM tests — ATTEMPTED, NOT COMPLETED
- `:app:compileDebugKotlin -x lint` — ATTEMPTED, NOT COMPLETED
- `:app:compileDebugAndroidTestKotlin` — ATTEMPTED, NOT COMPLETED
- instrumentation — NOT EXECUTED

GitHub has no registered commit status/check evidence for the formal review HEAD.

Therefore even a later source-level clean pass cannot be called fully verified CLEAN without resolving the production-wiring evidence gap required by Finding A.

## Review completion criteria

Do not derive the implementation prompt until:

1. the complete Finding A lifecycle has been re-reviewed on `805722d7` across admission -> claim -> execution -> destructive side effect -> semantic commit -> terminalization -> retry/requeue -> cancellation -> Undo -> stopped-worker cleanup -> process death/startup/restore -> notification;
2. every old blocker has been explicitly reclassified against the new code;
3. all History reference-changing writers have been inventoried against the physical deletion boundary;
4. Download/Terminal process, notification, DB, and UI capability identity domains have been checked for concrete Finding A impact;
5. a final independent source pass yields no additional P1/P2 Finding A defect beyond this ledger;
6. remaining non-blocking candidates are documented and deliberately excluded.

## Current status

`NOT_READY_FOR_IMPLEMENTATION_PROMPT`

Continue review against the frozen pushed HEAD and update this ledger before generating the consolidated remediation prompt.