# CLEAN-basis exploratory review — BUG-PAUSE-03 current-basis revalidation

checkpoint_kind: EXPLORATORY_REVIEW
review_mode: implementation_diff_frozen_clean_basis
review_parent_sha: `19b74086437397f5773eab3e225e3acb04ae6d2d`

reviewed_implementation_sha: `74f57e695db30b701ad429af311c39a763bfe086`
clean_review_basis: `74f57e695db30b701ad429af311c39a763bfe086`

current_remote_implementation_at_prewrite:
`dcad84ac30aa6dac7096961e408b48971c2f0376`

implementation_agent_currently_working: YES
active_implementation_start_head:
`dcad84ac30aa6dac7096961e408b48971c2f0376`
active_implementation_scope:
`P2 BUG-TERMINAL-11 TEST-ONLY DETERMINISTIC QUIESCENCE CLOSURE`

Active-wave commits/diffs were not inspected.

governing_ledger: `b98d315006fa19fc6f22b017f43a91899db5fb81`
governing_checklist: v7
governing_checklist_blob: `e758358ff6d8952470ef3b07f5b18fb26ed4c05c`
lens_policy_blob: `49600871d632fd8612bbabec80dfaa996afb54d3`
protocol_blob: `b9ff2c984a1655f0f214ab0ec89d3ccb18c0f913`

prior_pause03_current_basis_checkpoint:
`review-runs/checkpoints/2026-09-13__90afaec1__pause03-current-basis-revalidation.md`

## Independent verdict

Overall workflow remains **NOT_CLEAN**.

Existing canonical P2 BUG-PAUSE-03 remains **OPEN** at exact independently CLEAN basis
`74f57e695db30b701ad429af311c39a763bfe086`.

No new finding ID and no canonical count change.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

## Exact production sequence

### 1. Pause All forms a fixed semantic target snapshot

At exact 74f57e69, `DownloadViewModel.pauseAllDownloads()` obtains:

`getActiveAndPostProcessingDownloads()`

inside `withDownloadWorkerExecutionLock`, then releases that global lock before processing the
saved list.

For every member of that saved list it:
- acquires the exact per-Download side-effect lease;
- revalidates the execution ID;
- persists `DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE`;
- converges the exact user-stop semantic state;
- attempts exact native/execution quiescence.

That is strong semantic authority for the rows actually in the snapshot.

### 2. The final transport cancellation is still broader than the semantic target set

After the per-item loop finishes, Pause All still performs:

`WorkManager.getInstance(application).cancelAllWorkByTag("download")`

This call is outside the snapshot's global execution lock and is not parameterized by the exact
Download/execution identities that acquired durable USER_PAUSE authority.

No Pause-All operation generation, admission epoch, durable global barrier, or exact final target
set is published across the snapshot-to-tag-cancel interval.

### 3. A queued sibling B can validly claim after the snapshot

The production scheduler/claim path remains independently live after the Pause-All snapshot lock is
released.

`admitQueuedDownloadsThroughProductionPath()` performs selection under the short global execution
lock, then invokes the per-candidate claim path.

`claimDownloadThroughProductionAdmission()`:
- acquires the candidate's per-Download lease;
- reacquires the short global execution lock;
- revalidates native/process ownership, recovery debt, primary-success authority, capacity,
  cancellation and History authority;
- generates a fresh execution ID;
- CAS-claims the queued row through `claimDownloadForWorkerAndRead()`;
- publishes the process-local execution owner.

It does not inspect or join a Pause-All operation.

Therefore a queued/scheduled sibling B that was absent from the initial Active/PostProcessing
snapshot can legitimately become Active/E2 before Pause All reaches the final broad tag
cancellation.

### 4. Broad tag cancellation stops B without Pause authority

When Pause All later calls `cancelAllWorkByTag("download")`, B's WorkManager execution shares that
tag even though B never received a USER_PAUSE carrier or Paused semantic transition from this
operation.

The transport side effect has therefore expanded beyond the durable semantic decision that was
supposed to authorize it.

### 5. Stopped-worker cleanup can generically requeue B

For stopped running executions, `cleanupStoppedDownloadExecution()` first checks whether the exact
execution carries USER_CANCEL/USER_PAUSE recovery responsibility.

B has none from the reproduced sequence, so the path falls through to
`DownloadRepository.requeueRunningDownload()`.

For a still-owned Active/PostProcessing exact execution, that repository path performs the exact
execution CAS to `Status.Queued`.

Thus the late sibling can become:

`Queued/E2`

rather than durably Paused.

This is not recovery of Pause-All authority; it is generic unexpected-stop recovery after an
unauthorized broad transport cancellation.

### 6. Resume All does not repair the semantic mismatch

`resumeAllDownloads()` obtains only `dao.getPausedDownloadsList()` and explicitly resumes that
set.

A late sibling B that was generically requeued to Queued is not in the Paused set. Manual/new queue
activity may later start B, but that is new authority and does not repair the earlier Pause-All
semantic mismatch.

## Root reconciliation

This is the same existing P2 BUG-PAUSE-03 root already revalidated at
`90afaec157607669ea32fa41877e7f0efcdcca86`.

The current 74f57e69 source retains the same decisive invariant violation:
a point-in-time exact semantic target set is followed by a later tag-wide transport cancellation
whose target set may have grown.

Do not merge with:
- BUG-PAUSE-01: the victim B receives no first Paused write at all;
- BUG-PAUSE-02: no successful Paused write followed by quiescence failure is required for B;
- BUG-CANCEL-02: the semantic authority here is Pause-All, not user Cancel;
- BUG-QUEUE-05: B is a newly admitted Active execution, not an already-Paused row omitted from queue
  cleanup.

Attribution remains pre-existing baseline debt. Exact introduction is not re-investigated here.

## Trigger map

### Module B — External scheduler handoff
**TRIGGERED / FAIL**

The semantic Pause target and the later WorkManager transport target are not the same exact set.

### Sibling isolation
**TRIGGERED / FAIL**

Independent B can be stopped solely because it acquired the shared tag after Pause-All's semantic
snapshot.

### Unresolved-state / recovery ownership
**TRIGGERED / NOT_CLOSED**

Generic stopped-worker recovery can preserve liveness by requeueing B, but that does not create the
missing Pause authority or an exact successor carrier attributable to Pause All.

### Thread-affinity
**NOT_APPLICABLE to the root**

The defect does not require a new or widened synchronous bridge, lock hold across slow work, or
main-thread wait. It is stale operation authority across separately bounded locks.

## L1-L6 review

### L1 — Durability & recovery
**BASELINE**

Selected A receives durable USER_PAUSE responsibility. Late B does not. Generic recovery may retain
B as Queued, which preserves some liveness but not Pause semantics.

### L2 — Identity & provenance
**BASELINE**

Per-Download execution identities are exact. The failure is operation-set provenance: membership in
the later WorkManager tag set is incorrectly treated as if it carried membership in the earlier
Pause-All semantic decision.

### L3 — Concurrency & authority
**DEEP**

Primary DEEP lens.

The exact race is:
Pause-All snapshot lock release
-> normal B admission/claim
-> later broad tag cancellation.

The implementation has strong item-local CAS and lease authority but no operation-wide barrier that
connects the initial target decision to the final transport target.

Selection reason:
R1 — the defect is a concurrency/authority expansion across two separately synchronized boundaries.
R2 — scheduler handoff and sibling isolation are directly triggered.

### L4 — Destructive ownership
**BASELINE**

The destructive effect is termination of B's valid execution carrier. No separate filesystem
deletion/data-loss root is required to confirm BUG-PAUSE-03.

### L5 — Platform contract closure
**BASELINE**

WorkManager tag cancellation behaves as the coarse transport effect supplied by production. The
defect is application-level authority selection, not an unverified platform guarantee.

### L6 — Cross-feature semantic propagation
**BASELINE**

The invariant crosses ViewModel batch semantics, scheduler admission, WorkManager transport,
stopped-worker recovery and Resume-All re-entry. No second cross-feature root was found.

## Review retrospective

The earlier 90afaec1 review correctly identified the target-set mismatch.

The 74f57e69 source contains stronger exact per-item user-stop recovery, ownership leases, admission
checks and generic stopped-worker recovery. None of those close the batch-authority gap because they
operate on exact individual executions after the Pause-All target set has already been frozen.

The stronger recovery code actually makes the residual easier to classify:
late B has no USER_PAUSE disposition, so generic stopped cleanup is explicitly allowed to requeue
it. That is evidence of missing semantic enrollment rather than evidence that Pause-All correctly
owned B.

No active BUG-TERMINAL-11 wave source was inspected.

## Checklist evolution

No checklist or lens-policy change is proposed.

Checklist v7 already contains the applicable requirements:
- exact scheduler handoff authority;
- sibling isolation;
- concurrency/authority review;
- producer/consumer/final-effect closure;
- unresolved-state convergence ownership.

The miss is implementation semantics, not governance coverage.

## Checkpoint summary

- basis: `74f57e695db30b701ad429af311c39a763bfe086`;
- active deterministic-quiescence implementation wave remained frozen from inspection;
- BUG-PAUSE-03 remains OPEN P2 at the exact CLEAN basis;
- no new finding ID;
- no canonical count change;
- active BUG-TERMINAL-11 status unchanged;
- primary exploratory lens: L3 DEEP;
- independent execution: NOT EXECUTED.
