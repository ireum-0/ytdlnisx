# Same-SHA correctness review — 359ddbf9 — BUG-CANCEL-02 L4 DEEP

checkpoint_kind: EXPLORATORY_REVIEW
review_mode: current_exact_sha_followup
review_parent_sha: `94db2e8d7eceba3f2da30e050c4b262c22c593e1`

reviewed_implementation_sha:
`359ddbf9bf534009be095ad1bffea8ec45c899e4`

governing_protocol_blob:
`b9ff2c984a1655f0f214ab0ec89d3ccb18c0f913`

governing_checklist_v7_blob:
`e758358ff6d8952470ef3b07f5b18fb26ed4c05c`

prior_manual_final:
`94db2e8d7eceba3f2da30e050c4b262c22c593e1`

## Independent verdict

Overall workflow remains **NOT_CLEAN**.

Existing canonical P2 **BUG-CANCEL-02** remains **OPEN** on exact current implementation
`359ddbf9bf534009be095ad1bffea8ec45c899e4`.

This review promotes **L4 Destructive ownership** to DEEP for the current SHA.

No new finding ID is created.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

BUG-TERMINAL-11 remains FIXED-CLOSED.

CLEAN_REVIEW_BASIS remains:
`74f57e695db30b701ad429af311c39a763bfe086`.

## Exact production sequence

### 1. User cancellation wins durable dispatch supersession first

`TerminalCancellationCoordinator.cancel()` first calls
`WorkManagerHandoffRecovery.cancelTerminalDispatch()`.

That path separates:
- durable exact dispatch supersession;
- external WorkManager cancellation acknowledgement.

The coordinator then independently calls `TerminalExecutionRegistry.cancel()`.

### 2. Row-deletion authority does not require WorkManager cancellation acknowledgement

`TerminalCancellationResult.rowDeletionAuthorized` is:

`dispatchSuperseded && executionConverged`

It deliberately does not require
`workManagerCancellationAcknowledged`.

That design is safe only if `executionConverged` proves all effect ownership that could still mutate
or publish for this Terminal execution.

It currently does not.

### 3. NATIVE_FINISHED is not publication quiescence

After the native command returns, `TerminalDownloadWorker` persists:

`TerminalExecutionRecovery.Phase.NATIVE_FINISHED`.

At that point:
- native process execution is finished;
- Terminal publication may not yet have begun;
- provider/file publication may still occur;
- the staging/cache root may still be live-owned by this worker;
- the semantic Terminal row has not yet been deleted.

The worker then proceeds into its output/publication path.

### 4. Cancellation can terminalize NATIVE_FINISHED immediately

For a `NATIVE_FINISHED` witness,
`TerminalExecutionRecovery.convergeTerminal(..., STOPPED)` ultimately permits
`markTerminalFailure(..., STOPPED)` to persist `TERMINAL_STOPPED` immediately.

No post-native publication/effect quiescence is proven.

### 5. Registry cancellation removes the process-local active token

If `convergeTerminal()` returns true,
`TerminalExecutionRegistry.cancel()` removes the exact token from `activeTokens`.

That token is also the process-local liveness proof used by Terminal cache ownership.

### 6. The worker can still cross irreversible publication after that durable STOPPED result

The worker's current order is:

`markNativeFinished()`
-> staging/output verification
-> publication journal creation
-> `FileUtil.moveFile(...)`
-> publication journal COMMITTING
-> later `TerminalExecutionRecovery.markCommitting()`
-> DAO row deletion
-> COMMITTED.

There is no durable STOPPED/cancellation authority recheck immediately before
`FileUtil.moveFile()` or equivalent first irreversible provider/file publication.

Therefore cancellation can persist STOPPED and remove the active token while the same worker is
still able to publish external output.

A later failure of `markCommitting()` cannot undo a provider/file effect already created.

### 7. WorkManager cancellation acknowledgement is not effect quiescence

Even when external cancellation is acknowledged, the current production contract does not prove
that the worker has fully unwound its post-native publication path before
`TerminalExecutionRegistry.cancel()` reports execution convergence.

Conversely, when acknowledgement is false or delayed, row deletion can still be authorized because
the coordinator ignores that field for `rowDeletionAuthorized`.

The root therefore cannot be closed by merely adding
`workManagerCancellationAcknowledged` to the row-deletion predicate.

### 8. Cache-maintenance live ownership is also retired too early

`TerminalCacheOwnership.isLiveOwnedRoot()` accepts a valid Terminal marker as live only when its
task token is still present in `TerminalExecutionRegistry.isActiveNow()`.

After cancellation removes that token, `AppCacheManager.isLiveOwnedEntry()` can stop recognizing
the worker's Terminal staging root as live-owned.

`CacheMaintenanceAuthority` serializes only the short admission/maintenance window. It does not
span the execution/publication lifetime.

Therefore cache maintenance can race a worker that still owns staging/publication after
cancellation has prematurely retired the process-local token.

### 9. Publication recovery does not make the race safe

Publication journals and recovery carriers correctly preserve partial/unknown publication evidence
after a worker failure or process death.

They do not authorize a cancellation path to publish after durable STOPPED has already won.

Recovery of an already-created external effect is not equivalent to preventing that forbidden
effect after cancellation authority.

## Root reconciliation

This is the already-canonical **BUG-CANCEL-02** Terminal cancellation/publication/cache-live-owner
subcase.

It is not a new Terminal finding and not a separate cache finding.

The same semantic root is:

**durable cancellation can retire execution/row/live-owner authority before the still-running
effect owner has been proven quiescent, allowing stale post-cancel publication or destructive
maintenance against still-owned staging.**

No duplicate P2 is created.

## Candidate rejection

The following are insufficient fixes:

1. **Require only WorkManager cancellation acknowledgement.**
   Acknowledgement is not proof that provider/file publication has fully unwound.

2. **Add a delay or poll `isStopped`.**
   Timing/process-local observation is not durable destructive authority.

3. **Check cancellation only after `FileUtil.moveFile()`.**
   The irreversible effect may already exist.

4. **Keep the Terminal row longer but still remove the active token.**
   Cache-maintenance liveness would remain unsound.

5. **Keep the active token but allow durable STOPPED before publication quiescence.**
   The durable execution result would still contradict a later worker publication.

6. **Rely on publication recovery to clean up afterward.**
   Recovery cannot retroactively justify an effect that occurred after durable cancellation won.

## Required correction

Production remediation must establish a durable post-native publication/effect ownership contract.

At minimum:

- distinguish native quiescence from post-native publication/effect quiescence;
- cancellation racing `NATIVE_FINISHED` must not become a terminal STOPPED result while the worker
  can still cross an irreversible publication boundary;
- the worker must consume the exact durable cancellation/stop authority at the final pre-effect
  boundary before provider/file publication;
- if publication/effect ownership has already begun, cancellation must not authorize Terminal row
  deletion or retire the process-local live-owner token until that exact effect owner reaches a
  durable quiescent/terminal state;
- the worker's finally/recovery path must be able to converge the exact cancellation outcome after
  publication work has actually stopped;
- process death in every new post-native phase must remain recoverable and must never replay native
  execution or silently convert cancellation into success;
- Terminal cache liveness must remain true for still-owned staging through the same publication
  ownership window;
- WorkManager acknowledgement may be evidence but must not be treated as the sole proof of effect
  quiescence;
- do not weaken existing dispatch-generation, execution-token, publication-journal, provider
  authority, or cache containment fences.

A new durable phase/state may be required, but the implementation is not required to use a specific
name. The semantic dimensions above are mandatory.

## Required production-wiring coverage

The correction must directly exercise the real worker/cancellation/cache composition.

Mandatory cells include:

1. cancel after `NATIVE_FINISHED` but before first publication effect:
   - durable cancellation wins;
   - no provider/file publication occurs;
   - row/live-owner retirement occurs only after effect quiescence.

2. cancellation while publication ownership has already begun:
   - no premature terminal STOPPED/deletion claim;
   - no active-token retirement while the effect owner is still live;
   - exact cleanup/recovery converges afterward.

3. cache maintenance during the cancellation/publication race:
   - still-owned Terminal staging remains protected.

4. process death/recovery for any newly introduced post-native nonterminal phase.

5. already-committed publication remains stronger than later cancellation and is not reopened.

## Trigger map

### Module B — External scheduler/cancellation handoff
**TRIGGERED / FAIL under existing BUG-CANCEL-02**

Dispatch supersession, WorkManager cancellation acknowledgement, and publication-effect quiescence
remain distinct facts. Current row deletion consumes only the first plus native/execution
convergence.

### Module I — Maintenance vs live-owner namespace
**TRIGGERED / FAIL under existing BUG-CANCEL-02**

The process-local Terminal token can disappear before post-native staging/publication ownership ends.

### L4 — Destructive ownership
**DEEP / FAIL**

Primary lens for this review.

Selection reason:
- R1: the open root crosses an irreversible provider/file publication boundary after a stronger
  durable cancellation result may already have won;
- R2: the same premature ownership retirement affects cache deletion authority over live Terminal
  staging.

### L1 — Durability & recovery
**BASELINE**

Current execution/publication journals are durable, but no durable state dimension proves
post-native effect quiescence before cancellation terminalizes the execution.

### L2 — Identity & provenance
**DEEP from prior manual run / rechecked**

Exact execution tokens and dispatch identities are strong; the missing dimension is effect-lifetime
authority, not identity collision.

### L3 — Concurrency & authority
**DEEP from prior current-SHA reviews / rechecked**

The concrete race is cancellation vs post-native publication and cache maintenance.

### L5 — Platform contract closure
**BASELINE**

WorkManager cancellation acknowledgement is not treated as a proven worker/effect completion
contract.

### L6 — Cross-feature semantic propagation
**BASELINE**

The root propagates across Terminal cancellation, execution recovery, publication recovery, and
cache maintenance; it remains one semantic root.

## Lens state after this checkpoint

Current-SHA accumulated depth:
- L1: DEEP
- L2: DEEP
- L3: DEEP
- L4: **DEEP**
- L5: BASELINE
- L6: BASELINE

remaining_not_yet_deep:
- L5
- L6

next_not_yet_deep_lens:
L5 Platform contract closure

No checklist evolution is required. Existing destructive-authority, live-owner, async-completion,
and production-wiring rules detect this root.

INDEPENDENT EXECUTION: NOT EXECUTED
