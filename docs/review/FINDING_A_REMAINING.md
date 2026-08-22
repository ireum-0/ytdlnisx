# Finding A Remaining Review Ledger

## Frozen review baseline

- Repository: `ireum-0/ytdlnisx`
- Frozen pushed baseline: `a578607580f8ec97cc660126bdd663403971ec13`
- Semantic production code is inherited from the code under `e314d16ce947aa7bccf8265ce2c9af7d6c03c69b` / `60f356b102c5f511d6279c3442be51fdd39cd28c`; `a5786075` itself changes only `AGENTS.md`.
- This file is maintained on a separate review branch and must not be treated as an implementation commit.
- Finding B is out of scope.

## Review rule

Only production-reachable, independently verified Finding A defects belong in the confirmed blocker list. Closely related residuals are consolidated under one lifecycle blocker rather than counted repeatedly. Candidates remain separate until their production impact is proved.

## Confirmed remaining blockers

### A1. Low-quality cancellation does not revoke runnable admission everywhere

Quality replacement runnable/select/claim/reschedule predicates do not consistently reject:

- child `CANCELLATION_REQUESTED`;
- parent `cancelRequested=true`.

Final destructive authorization rejects revoked authority, but a newly cancelled privileged Download can still be selected or claimed before that boundary.

Required invariant: once durable cancellation authority exists, the linked quality replacement must not newly enter runnable execution.

Includes queue observation, scheduled/priority selectors, direct claim, Paused/Processing/requeue/reset-schedule transitions, and recovery paths that reuse runnable admission.

### A2. Low-quality cancellation can lose terminal ordering

Several terminal paths treat `CANCELLATION_REQUESTED` as an ordinary nonterminal state or ignore `parent.cancelRequested`.

Confirmed races include:

- phase-1 cancellation commits, then a stale worker failure changes child `CANCELLATION_REQUESTED -> FAILED`;
- cancellation commits after the scan's last `ensureRunning()` but before `finishNoCandidates()`, after which `finishOperation()` can still close the parent as COMPLETED/FAILED because its SQL only requires `state='RUNNING'`.

Required invariant: cancellation committed first must win. Generic SUCCESS/FAILED/WAITING callbacks and non-cancellation operation terminalizers must not overwrite a durable cancellation request.

### A3. Durable low-quality cancellation/terminal convergence is not guaranteed in the same process

Two deliberate non-atomic boundaries can strand durable state:

1. Download terminal Error/refusal persists, then linked-ledger transition fails and is intentionally swallowed.
2. cancellation phase 1 persists `cancelRequested/CANCELLATION_REQUESTED`, then phase-2 completion fails transiently.

Recovery exists at startup/reconnect, but the live process does not always retain guaranteed convergence responsibility.

Reachable state includes:

- `Download=Error`, child `ACTIVE/QUEUED/WAITING`, parent `RUNNING`;
- `cancelRequested=true`, child `CANCELLATION_REQUESTED`, with phase 2 unfinished.

Required invariant: durable primary state creates idempotent convergence debt until child and parent reach coherent terminal state without needing process restart or unrelated user action.

### A4. Undo restore can reopen revoked authority or create non-runnable ghosts

`restoreRemovalSnapshot()` can restore the Download before resolving the linked parent/child.

Confirmed residuals:

- terminal parent can leave a restored `Queued` Download with a terminal child;
- `parent.state=RUNNING` plus `parent.cancelRequested=true` can still be treated as restorable and reopen the child toward QUEUED;
- this combines with missing runnable cancellation guards to resurrect privileged work.

Required invariant: exact Undo restores runnable state only while the linked parent still has live authority. Terminal/cancelling parent state must not produce a runnable or fake-runnable Download and must not reopen the child or parent.

### A5. Live Undo token lifetime can exceed the owner that can resolve it

Pending-removal state is split across:

- process-global live token registration;
- repository-instance snapshot ownership;
- UI lifecycle callbacks that restore/commit the token.

If the view/UI owner disappears while an indefinite Undo is outstanding, the process-global token can remain marked live while no reachable owner can resolve the exact snapshot. Routine reconciliation then skips the durable pending-removal child indefinitely.

Required invariant: a token is live iff an actual owner capable of resolving that exact snapshot remains live, or ownership has been transferred to a longer-lived resolver. Owner disposal vs Undo/commit must resolve exactly once.

### A6. Explicit bulk/direct deletion can strand linked low-quality children

`deleteErrored()` deletes Error Download rows and barriers without first terminalizing a still-nonterminal linked low-quality child and without guaranteed immediate reconciliation.

`Error + nonterminal child` is production-reachable when the Download Error write succeeds but the linked-ledger transition fails.

`CleanUpLeftoverDownloads` also uses the direct Error-delete path.

Required invariant: explicit user/cleanup deletion must converge linked child and parent in the same semantic flow; it must not rely on a future generic missing-Download startup repair.

### A7. History replacement lacks a durable irreversible semantic-commit protocol

After the History replacement transaction returns Updated, ancillary work still runs inside broader worker failure handling.

Confirmed contradictions include:

- keyword or previous-media cleanup failure after History commit can retroactively produce Download Error / HISTORY_WRITE_FAILED-like semantics;
- notification/DownloadOutcome can disagree with the persisted Download state;
- `completeAndDelete()` checks only broadly nonterminal linked state and can ignore cancellation ordering;
- cancellation after History commit but before linked completion can rewrite or reinterpret the terminal outcome.

Required invariant:

- cancellation before semantic commit wins and prevents History mutation;
- once History commit wins, later ancillary failure/cancellation cannot reinterpret that committed History mutation as pre-commit failure/cancellation;
- terminal Download, linked child, parent, outcome, retryability, notification, and cleanup must agree.

### A8. A committed History replacement can be destructively replayed

The semantic-commit gap survives both process death and live execution handoff.

Confirmed paths:

- History commit by E1 -> process death before linked success / Download deletion -> startup abandoned-execution recovery requeues Active/PostProcessing row -> E2 can replay replacement;
- History commit by E1 -> Pause/Cancel before final completion -> Resume/Undo/requeue -> E2 can replay the already committed replacement.

Required invariant: durable recovery must distinguish pre-commit abandoned execution from post-History-commit execution and converge the latter exactly once without destructive replay.

### A9. Retained History media deletion authorization is TOCTOU

History file deletion and DownloadWorker replacement cleanup authorize against a retained-reference snapshot and later perform filesystem deletion.

A History writer can establish a new reference to the same canonical file between the final retained-reference decision and physical delete. A selected row can also mutate during deletion, survive record deletion, and newly retain a file that was already authorized for deletion.

Affected production paths include:

- History prepared deletion / HistoryFileDeletionEngine;
- `DownloadWorker.deleteValidatedReplacementPaths()` for rejected/replaced media cleanup.

Required invariant: the destructive filesystem boundary must be serialized against every History mutation capable of creating/changing a retained media reference, or use an equivalent atomic authority protocol. Another unsynchronized pre-delete query is insufficient.

### A10. Resume/retry/requeue authority is not bound to an exact state generation

Several transitions can mutate by numeric Download ID or broad source status rather than exact generation/authority.

Confirmed cases include:

- stale Pause notification can reject E1 but still mint a Resume notification;
- Resume capability carries only `itemID` and broad requeue can clear a newer E2 execution;
- direct UI resume and broad requeue/reset-schedule/exit paths can erase newer execution ownership;
- concurrent or stale Retry/Reconfigure can read an old Error snapshot and later rewrite a newer Active E2;
- `status='Error'` or `status='Paused'` alone is not sufficient because the same row can enter a later Error/Paused episode.

Required invariant: Resume/Retry/Reconfigure authority must be bound to the exact paused/failure generation and use atomic allowed-source-state/ownership CAS. Delayed or duplicated stale UI capability must have zero effect on a newer execution/episode.

### A11. Retry/preparation performs stale-execution side effects before exact ownership revalidation

The yt-dlp retry loop can observe only numeric Download status and continue after E1 lost ownership to E2.

Before the existing owned temp-reset guard it can perform side effects such as:

- deleting source-shared `--load-info-json` cache;
- updating running notification with E1 authority;
- request/probe/log/cache preparation.

Initial preparation has similar side effects before its later exact DB ownership write.

Required invariant: exact execution ownership and cancellation must be revalidated before any retry/probe/request-building/shared-cache/log/notification/temp/process/filesystem side effect.

### A12. Native process start has an authorization-to-publication gap

E1 can validate exact ownership and publish process owner while holding the execution lock, release the lock, then create/register the actual native process later inside `YoutubeDLCompat.execute()`.

Race:

- E1 authorization passes;
- Pause/Cancel commits while no native process exists yet;
- cancellation finds nothing to kill;
- stale E1 starts the native process after durable cancellation.

Required invariant: final exact execution authorization and native process start/publication must form a cancellation-safe protocol. A durable Pause/Cancel must prevent a not-yet-started stale E1 from starting later.

### A13. Long destructive post-processing/file side effects outlive execution ownership handoff

Point-in-time `shouldStopForUserRequest()` or execution checks do not protect long-running destructive work.

Confirmed affected work includes:

- `FileUtil.moveFile` / SAF / MediaStore move-copy-delete;
- HardSub ffmpeg and AV merge;
- original media delete + rename replacement;
- subtitle sidecar deletion;
- converter/probe subprocesses;
- replacement cleanup through `deleteValidatedReplacementPaths()`.

Pause/Cancel can commit after the check, Resume/E2 can claim, while stale E1 continues mutating overlapping output/temp/media resources.

Required invariant: execution-scoped destructive leases/quiescence must cover the full external side-effect lifetime; E2 may not acquire overlapping destructive resources while E1 remains active.

### A14. Process-local cancellation authority is published before durable commit

`DownloadCancellationRegistry.record()` is process-local state but is called from transactions that may still roll back. Worker cancellation classification can then trust a phantom registry entry even though DB cancellation did not commit.

The complementary bug exists in notification/scheduled cleanup paths that can kill exact processes even if the durable DB transition fails.

Affected audit scope includes:

- single and multi-item pause/cancel;
- low-quality cancellation;
- notification Cancel;
- pause-all/cancel-all;
- `CancelScheduledDownloadWorker`;
- stopped-worker cleanup.

Required ordering where possible:

`durable DB transition -> exact process-local cancellation publication -> exact external cancellation`.

If forced system-stop cleanup cannot commit first, it must retain explicit recovery responsibility and must not release ownership as if cleanup succeeded while the DB row remains durably running.

### A15. Notification action authority can cross items, attempts, and identity domains

There are multiple independent notification-authority defects:

1. one `NotificationUtil` can share mutable `NotificationCompat.Builder` instances across concurrently launched Download children, allowing A's notification to be built with B's actions/state;
2. E1/E2 PendingIntents reuse numeric Download ID with `FLAG_UPDATE_CURRENT` while execution ID exists only in extras, so attempt capabilities alias;
3. blank execution token in `CancelDownloadNotificationReceiver` behaves as an ID-only wildcard instead of failing closed for a running Download capability;
4. Terminal notification cancellation uses Download-domain receiver/ID semantics, allowing same-number Terminal and Download rows to address the wrong entity.

Required invariant: each visible notification is built from immutable per-call state and every action capability is namespaced to the exact entity domain and, where applicable, exact execution/generation. Blank running-execution authority fails closed.

### A16. Download and Terminal process IDs share a global numeric namespace

`DownloadItem.id` and `TerminalItem.id` are independent auto-generated primary keys, but DownloadWorker and TerminalDownloadWorker use bare decimal numeric IDs in the same global YoutubeDL/YoutubeDLCompat process registry.

Therefore Download N and Terminal N can kill, block, replace, or reject each other's process lifecycle. Notification IDs/actions have related cross-domain aliasing.

Required invariant: process identity must include entity/lifecycle domain and exact Download execution identity where needed. Terminal N must never address Download N resources and vice versa.

### A17. Worker cleanup lacks per-sibling durable fault isolation

Stopped-worker cleanup can process multiple active Downloads inside one outer failure domain.

If A's refusal convergence/requeue/verification throws, later siblings B/C may never be durably cleaned, while bookkeeping/owners for the broader active set are still released. Since process cancellation may already have run, B/C can remain Active/PostProcessing with dead execution and no process-local owner.

Startup abandoned-execution recovery has a similar batch fault domain where one unrecoverable row can prevent later unrelated recoverable rows from being processed.

Required invariant: durable cleanup/recovery failures are isolated per Download. Only the exact row that is durably non-running, superseded by a newer owner, or represented by explicit durable recovery debt may have its execution ownership released.

## Existing fixes that should be preserved

The following were previously verified source-level and should not be reopened without evidence:

- typed first-refusal persistence carrier preserves TargetMissing / SourceMismatch / TypeMismatch through first insert/read-back failure;
- CancellationException is not reclassified by that carrier;
- quality-authority loss distinguishes cancellation-origin from non-cancellation terminal failure;
- Download lineage ID and low-quality parent operation ID are separate domains;
- missing-ledger quality replacement admission is fail-closed;
- exact execution-token protection exists on several pause/cancel/notification/scheduled-cancel paths;
- claim and process-local execution owner publication are serialized under the shared worker execution lock;
- live Undo is protected from routine reconciliation while genuinely owned.

## Review candidates not promoted to Finding A blockers

### C1. Cross-domain WorkerProgress/EventBus identity

Download and Terminal workers both publish `DownloadWorker.WorkerProgress` keyed only by numeric ID, and both Active Downloads and Terminal UI consume numeric tags. Same-number rows can display each other's progress/output.

Current verified impact is UI attribution corruption; no destructive Finding A authority path has been proved from this alone. Keep as P3/candidate unless stronger impact is demonstrated.

### C2. Raw queue/count UI semantics

Some counts/flows may include non-runnable or policy-blocked rows. This is a UI/observability concern unless it produces a concrete Finding A authority or terminalization failure.

## Verification evidence gap

At the frozen baseline the known completed verification was limited:

- `git diff --check` — PASS
- `:app:kspDebugKotlin` — PASS
- focused JVM tests — ATTEMPTED, NOT COMPLETED
- `:app:compileDebugKotlin -x lint` — ATTEMPTED, NOT COMPLETED
- `:app:compileDebugAndroidTestKotlin` — ATTEMPTED, NOT COMPLETED
- `:app:connectedDebugAndroidTest` — ATTEMPTED, NOT COMPLETED
- instrumentation execution — NOT EXECUTED

Critical production-wiring concurrency/recovery paths therefore remain source-level only until executed.

## Review completion criteria

Do not call Finding A review-complete until all of the following are true on the frozen baseline review:

1. every confirmed blocker above has a production-reachable trace and no duplicate semantic category remains;
2. the complete Finding A lifecycle has been reviewed across admission -> claim -> execution -> destructive side effect -> History semantic commit -> terminalization -> retry/requeue -> cancellation -> Undo -> stopped-worker cleanup -> process death/startup/restore -> notification;
3. History deletion/reference mutation paths have been audited at the actual filesystem boundary;
4. process/notification identity domains have been audited across Download and Terminal surfaces that can affect Finding A;
5. no new P1/P2 Finding A defect is found after a final independent pass over the consolidated scope;
6. remaining candidates are explicitly documented as non-blocking or out of scope.

## Current status

`NOT_READY_FOR_IMPLEMENTATION_PROMPT`

This file is intentionally a living review ledger. It will be updated as the frozen-baseline review continues. No new implementation prompt should be derived from it until the status changes to `READY_FOR_IMPLEMENTATION_PROMPT`.
