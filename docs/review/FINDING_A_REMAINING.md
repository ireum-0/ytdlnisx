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

`DownloadWorker.cleanupStoppedWorker()` still performs durable requeue/refusal convergence for all active IDs inside one outer `try`/`forEach`. If A throws, later siblings B/C are never durably processed. The function subsequently releases every entry in `snapshot.workerExecutionIds`, not only rows proved durably non-running.

Because process cancellation is attempted in the earlier loop, an unrelated B can be left as:

`Download B = Active/PostProcessing EB`, native process gone, process-local execution owner released.

The log path itself acknowledges ownership may be released without a durable non-running guarantee.

`recoverAbandonedDownloadExecutions()` has the same batch failure shape during startup: one unrecoverable abandoned row throws and aborts later unrelated recoverable rows.

Required invariant: cleanup/recovery faults are isolated per Download. An exact execution owner is released only after that exact row is durably non-running, superseded by a newer owner, or represented by explicit durable recovery debt.

### A3. P2 — `CROSS-DOMAIN-DOWNLOAD-CANCEL-TERMINAL-ROW-01` — NEW RESIDUAL

Process and notification domains were largely separated, and Terminal now has its own cancellation receiver. However `CancelDownloadNotificationReceiver`, after an exact successful Download cancellation, still executes `terminalDao.delete(downloadId)`.

`DownloadItem.id` and `TerminalItem.id` are independent identity domains. Therefore cancelling Download N can delete an unrelated Terminal row N even though the Download capability contains no Terminal authority. The Terminal process is now namespaced separately, so this can also leave that Terminal process running after its durable Terminal row was deleted.

Required invariant: a Download-domain cancellation capability may mutate only Download-domain state/resources. Remove the Terminal DAO mutation from the Download receiver; Terminal cancellation must remain exclusively in the Terminal-domain path.

### A4. P2 — `HISTORY-CLEANUP-RETAINED-REFERENCE-TOCTOU-01` — PARTIALLY FIXED, STILL OPEN

The new `HistoryReferenceMutationCoordinator` correctly serializes several important History reference writers with the physical deletion boundary, including History insert/restore/replacement and prepared History deletion.

The coverage is incomplete. `HistoryViewModel.update(item)` calls `HistoryRepository.update(item)`, which performs a full-row `HistoryDao.update(item)` outside `HistoryReferenceMutationCoordinator`. A full `HistoryItem` contains `downloadPath`/local-tree identity, so this remains a production writer capable of creating, restoring, or changing a retained file reference while another thread holds the deletion coordinator and acts on its retained-reference snapshot.

Required invariant: every production write capable of changing retained media identity must participate in the same reference-mutation protocol, or must be narrowed so it cannot mutate those fields. The deletion coordinator cannot be considered authoritative while a full-row writer bypasses it.

## Source-level findings re-evaluated as closed or substantially closed

The following previous blockers are not currently in the remaining list unless later review finds a new concrete residual.

### Low-quality runnable cancellation authority — source-level closed

The canonical runnable predicate and duplicated queue selectors now reject both child `CANCELLATION_REQUESTED` and parent `cancelRequested=1`.

### Cancellation-pending child terminal race — source-level closed

Generic linked state writes now use explicit source-state allowlists, while cancellation convergence has an explicit `markCancelledByDownloadId` path. Repository logic treats parent `cancelRequested` / child `CANCELLATION_REQUESTED` as cancellation instead of generic failure.

### Download ↔ low-quality terminal convergence debt — source-level substantially closed

Terminal persistence can schedule idempotent linked-ledger convergence, and bulk Error deletion now uses the safe linked-child user-removal path. This still needs execution evidence.

### Undo terminal/cancelling parent restore — source-level closed

Undo now checks parent nonterminal plus `cancelRequested=false` and exact pending-token ownership before inserting/rebinding runnable state. Revoked/terminal authority returns no restored runnable Download.

### Undo live-token owner lifetime — source-level substantially closed

Pending snapshots are now process-level and owner-tagged; `DownloadViewModel.onCleared()` abandons its owned snapshots, unregisters their live tokens, and best-effort commits them so reconciliation can recover a failed commit. Execution evidence is still missing.

### Notification builder/action attempt isolation — source-level substantially closed

Notification builders are per-call. Running Pause/Cancel/Resume PendingIntent identity includes entity/action/id/execution information. Blank Download notification Cancel authority fails closed. Stale Pause creates Resume only after the exact pause transition succeeds. Error Retry/Reconfigure carries operation/attempt capability identity.

### Retry stale-transition authority — source-level substantially closed

Retry validates operation/attempt and uses snapshot-based queue CAS. Broad requeue now excludes Active/PostProcessing rows and quality runnable guards include cancellation authority.

### yt-dlp retry/preparation stale side effects — source-level closed

Retry cache deletion, retry notification/log/request preparation and related side effects are protected by exact execution checks/ownership wrappers.

### Native process start publication gap — source-level closed

Download process identity is execution-scoped, and the per-Download execution side-effect lease covers final exact ownership validation through native process registration before cancellation can acquire the same lease.

### Download/Terminal process identity — source-level substantially closed

`YtdlpProcessIdentity` namespaces Download processes by Download ID + exact execution token and Terminal processes by Terminal domain. Terminal has a dedicated notification cancellation receiver. A separate residual remains as A3 because the Download receiver still deletes Terminal DB state.

### History semantic commit / replay — source-level substantially closed

History replacement records the durable committed relationship in the History transaction; quality replacement also records linked success. Startup abandoned-execution recovery detects a committed replacement and finalizes it instead of requeueing it. Worker post-commit exceptions are routed to warning/finalization rather than generic History failure. Regular-vs-quality and finalization-failure edges remain under review before this category is considered fully closed.

### Long destructive side-effect handoff — source-level substantially improved

A per-Download execution side-effect lease now guards major HardSub/move/start/cleanup paths and Pause/Cancel acquire the same lease. Remaining destructive-path coverage is still being audited; the retained-reference writer gap is tracked separately as A4.

### Cancellation registry rollback publication — source-level substantially closed

Repository cancellation APIs collect exact publication records inside Room transactions and publish only after the transaction completes. Remaining direct registry callsites and forced-stop ordering remain under audit.

## Candidates under active review

### C1. Cross-domain running notification integer collision

Running Download notification ID is `90000 + downloadId` while Terminal uses `99000 + terminalId`. These ranges are not mathematically disjoint for arbitrary auto-increment IDs; e.g. Download 9001 and Terminal 1 map to the same Android notification ID. Actions are now domain-scoped, so confirmed impact is currently notification overwrite/removal rather than wrong-domain DB mutation. Promote only if a Finding A authority/terminal correctness impact is proved.

### C2. Cross-domain WorkerProgress/EventBus identity

Download and Terminal still publish `DownloadWorker.WorkerProgress` keyed by a bare numeric row ID and both UIs consume numeric tags. Same-number rows can display each other's progress/output. Current verified impact is UI attribution corruption; keep non-blocking unless stronger Finding A impact is established.

### C3. Remaining History reference writers

Audit all direct `HistoryDao.update/updateRaw/insertRaw` callsites and any full-row History writes outside `HistoryReferenceMutationCoordinator`. A4 already proves one production bypass; additional bypasses should be folded into A4 rather than counted separately.

### C4. Regular History post-commit finalization

Verify the durable-commit detector and startup/live recovery are correct for ordinary History redownloads as well as low-quality replacements, including failure of `completeAndDelete()` after History commit.

### C5. Forced-stop process-before-DB ordering

`CancelScheduledDownloadWorker` and stopped-worker cleanup can still kill an exact process before durable requeue/convergence. Determine whether the new lease/recovery ownership protocol guarantees a retry/debt when the subsequent DB transition fails. A2 already covers one concrete stranded-sibling case; do not duplicate unless a distinct single-item production failure remains.

## Verification evidence gap

Reported on `805722d7`:

- `git diff --check` — PASS
- `:app:kspDebugKotlin` — PASS
- focused JVM tests — ATTEMPTED, NOT COMPLETED
- `:app:compileDebugKotlin -x lint` — ATTEMPTED, NOT COMPLETED
- `:app:compileDebugAndroidTestKotlin` — ATTEMPTED, NOT COMPLETED
- instrumentation — NOT EXECUTED

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
