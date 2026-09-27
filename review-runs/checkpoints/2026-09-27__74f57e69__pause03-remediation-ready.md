# BUG-PAUSE-03 clean-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 0c607479a59554275f967823c665d9f7f80863e3
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 18
primary_lens: L3 Concurrency & authority DEEP
supporting_lenses:
- L1 Durability & recovery
- L4 Destructive ownership
- L6 Cross-feature semantic propagation
independent_execution: NOT EXECUTED

## Existing hardening to preserve

The exact-target portion of Pause All is substantially hardened and should remain intact.

`pauseAllDownloads()` snapshots current Active/PostProcessing rows under `withDownloadWorkerExecutionLock`. For each snapshotted exact execution it later acquires the per-Download side-effect lease, re-reads the row under the global execution lock, persists a `DownloadExecutionRecovery` record with `USER_PAUSE / SEMANTIC_STOP_PENDING`, converges the exact user-stop semantic state, and only then calls `cancelDownloadOnlyOwned(... USER_PAUSE)` to obtain exact transport/native quiescence.

That ordering preserves the separately owned BUG-PAUSE-01/02 contracts: failure to durably publish Pause semantics does not authorize native termination, and a committed Pause whose quiescence remains unresolved retains exact recovery responsibility.

The residual is not inside that per-item protocol.

## Same-root residual

After the fixed snapshot has been processed, `pauseAllDownloads()` still executes:

`WorkManager.getInstance(application).cancelAllWorkByTag("download")`

with no operation-wide Pause-All admission barrier and no exact target list supplied to this final transport mutation.

A sibling B that was Queued/Scheduled during the initial snapshot can therefore be admitted after that snapshot and before the final tag cancellation. The ordinary production admission path can give B a fresh exact execution identity because the short global lock used to create the Pause-All snapshot has already been released and no Pause-All generation/epoch is checked by later Download admission.

B never received this Pause-All operation's `USER_PAUSE` recovery record or Paused semantic transition. Nevertheless the final shared-tag cancellation can cancel B's WorkManager carrier. Generic stopped-worker cleanup/recovery may then reclassify B as interrupted/Queued; that liveness response cannot retroactively prove that B was an authorized Pause-All target.

This is the established BUG-PAUSE-03 root: semantic authority is formed over one exact set and later transport revocation expands to a newer/coarser set.

## Exact invariant

One Pause-All operation may revoke transport/native authority only for exact Download execution generations that have durably acquired that operation's Pause semantics.

For every execution E affected by the operation, one of these must be true at the destructive stop boundary:

1. E is an exact operation target whose current identity still matches and whose durable USER_PAUSE transition/recovery responsibility is established; or
2. E was admitted after the operation's target boundary and is outside that Pause-All operation, in which case this operation must not stop E.

If product semantics intentionally require "pause every Download that becomes active until Pause All finishes", those later executions must be explicitly enrolled into the same operation and durably transitioned to USER_PAUSE before their carrier is revoked. Tag membership alone is never semantic authority.

## Narrow implementation boundary

Preferred narrow correction: remove the final broad `cancelAllWorkByTag("download")` from Pause All and make the per-item exact cancellation/quiescence path the complete transport effect for that operation.

If a residual WorkManager cleanup step is still required, carry exact work/execution identity for the successfully paused targets and cancel only those exact carriers after revalidating current ownership. Do not use the shared `download` tag as the final target set.

An operation-wide admission barrier/generation is an acceptable alternative only if its semantics are explicit and durable enough to guarantee that every execution stopped by the operation is first enrolled in USER_PAUSE. A short process-local Boolean such as `isPausingResuming` is not sufficient authority unless all production admission paths consume it under the same race-free protocol and recovery semantics are defined.

Preserve:
- exact per-item side-effect leases and global execution-lock ordering;
- `DownloadExecutionRecovery.USER_PAUSE` identity/disposition;
- `USER_CANCEL` as a distinct stronger disposition;
- History-finalization precedence;
- low-quality replacement, scheduler capacity/priority, producer/native-generation, cache ownership, and primary-success fences;
- per-item failure isolation and retained recovery debt.

Do not change Resume All to compensate for the bug. Resume All correctly acts on durably Paused rows; the fix is to stop creating unpaused transport victims in the first place.

## Forbidden shortcuts

- relying on `isPausingResuming` only in UI code
- keeping `cancelAllWorkByTag("download")` and assuming stopped-worker recovery repairs its semantics
- treating WorkManager tag membership as proof of Pause-All target membership
- converting every newly interrupted B to Paused after the fact without an exact pre-stop durable Pause decision
- cancelling by Download ID while ignoring execution-generation replacement
- broadening Resume All to restart arbitrary Queued rows as compensation
- holding a global lock across long native quiescence in a way that creates an unbounded scheduler/worker deadlock risk
- weakening BUG-PAUSE-01/02 first-write and quiescence ordering

## Acceptance matrix

- A is Active in the initial snapshot; Pause All durably writes USER_PAUSE/Paused for A and exactly quiesces A
- B is Queued outside the snapshot; B claims a fresh Active/E2 after the snapshot but before A finishes; Pause All does not stop B
- B is admitted in the narrow instant immediately before the former final tag-cancel boundary; B remains running unless explicitly enrolled and durably paused
- snapshot A is replaced by a newer execution generation before its stop boundary; stale A authority cannot cancel the newer generation
- one target's first USER_PAUSE persistence fails; its native/WorkManager carrier is not stopped by a later bulk cleanup
- one target commits Paused but exact quiescence fails; recovery debt remains bound to that exact execution and siblings remain unaffected
- stronger USER_CANCEL wins for a target while Pause All is running; Cancel convergence remains authoritative
- committed History replacement wins; Pause All converges History finalization rather than cancelling the committed result
- process death after some targets are durably Paused and before others complete; restart converges only exact recorded targets and does not invent Pause semantics for siblings
- repeated Pause All remains idempotent over already-Paused/exactly converged targets
- Resume All resumes only durably Paused rows and does not need to discover or repair a B that Pause All should never have stopped
- normal queue admission remains functional for independent siblings after Pause All completes
- production-wiring regression forces the A-snapshot / B-late-admission interleaving using real Room + Download admission + WorkManager cancellation boundary; helper-only tests are insufficient

## Test gap

The bounded CLEAN-basis search found no focused unit or instrumented test that invokes `pauseAllDownloads()` and deterministically admits a sibling between the initial Active/PostProcessing snapshot and the final transport-stop boundary.

Prior Pause-All revalidation checkpoints established the same race at earlier CLEAN bases. The current exact source still retains the fixed snapshot plus final tag-wide cancellation, so the root remains open without creating a new finding ID.
