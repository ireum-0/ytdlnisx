# Manual v7 review — 359ddbf9 — L5 DEEP final

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 6952437ed0bfe9589c5c497d838d92a667adc1e5

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
review_parent_sha: cc1d406cef8c6272a8972b8dbc83f8cfe3b084d0
intermediate_checkpoint:
`review-runs/checkpoints/2026-09-26__359ddbf9__manual-v7-l5-deep-intermediate.md`
intermediate_commit: `cc1d406cef8c6272a8972b8dbc83f8cfe3b084d0`

implementation_sha: `359ddbf9bf534009be095ad1bffea8ec45c899e4`

## Pinned governance

- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- plan/remediation: `2145847a1054da28398b730b9be0ca728668f967`
- protocol blob: `b9ff2c984a1655f0f214ab0ec89d3ccb18c0f913`
- checklist v7 adoption: `b98d315006fa19fc6f22b017f43a91899db5fb81`
- checklist v7 blob: `e758358ff6d8952470ef3b07f5b18fb26ed4c05c`
- lens-policy adoption: `822ffe6a9cd45b951550fcb559557f0cf0798610`
- lens-policy blob: `49600871d632fd8612bbabec80dfaa996afb54d3`
- ledger/remediation: `b98d315006fa19fc6f22b017f43a91899db5fb81`

Governance remained fixed for this manual run.

## Independent verdict

**NOT_CLEAN.**

Exact implementation `359ddbf9bf534009be095ad1bffea8ec45c899e4` received a fresh full-checklist
review. No implementation movement occurred during this run.

Existing canonical P2 roots confirmed open:
- BUG-BACKUP-11;
- BUG-PAUSE-03;
- BUG-CANCEL-02 Terminal publication/effect-quiescence subcase.

BUG-TERMINAL-11 remains FIXED-CLOSED.

New finding IDs: **0**.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

CLEAN_REVIEW_BASIS remains:
`74f57e695db30b701ad429af311c39a763bfe086`.

INDEPENDENT EXECUTION: NOT EXECUTED.

## Primary L5 DEEP result

primary_deep_lens: L5 Platform contract closure

The active BUG-CANCEL-02 root remains open because the production code distinguishes native
quiescence from WorkManager transport state, but still fails to carry that distinction through the
post-native publication/effect interval.

### App-owned contract proof

`TerminalCancellationCoordinator` exposes three separate facts:
- exact dispatch superseded;
- WorkManager cancellation acknowledged;
- Terminal execution converged.

Yet row deletion authority is only:

`dispatchSuperseded && executionConverged`.

WorkManager acknowledgement is not required.

`TerminalExecutionRecovery` treats NATIVE_FINISHED as native-quiescent and allows STOPPED to
terminalize it. `TerminalExecutionRegistry.cancel()` then removes the exact process-local active
token after that convergence succeeds.

But the worker's production sequence after NATIVE_FINISHED still includes:
- publication journal creation;
- PUBLISHING;
- `FileUtil.moveFile()`;
- external/provider publication recording;
- publication journal COMMITTING;
- only later `TerminalExecutionRecovery.markCommitting()`;
- row deletion and semantic COMMITTED state.

There is no exact durable STOPPED/cancellation revalidation immediately before the first
irreversible publication effect.

Therefore a cancellation can durably win native/execution semantics and authorize row/live-token
retirement while the exact worker still owns post-native publication.

### WorkManager boundary

The project uses `androidx.work:work-runtime-ktx:2.11.0`.

The YTDLnisX wrapper waits up to five seconds for `Operation.result`, but that result is only
reported as `workManagerCancellationAcknowledged`, which row deletion does not consume.

Official AndroidX GitHub source was reviewed as corroborating platform evidence and separates
cancellation/interrupt signalling from worker coroutine completion.

The exact upstream Git commit corresponding to the published 2.11.0 source artifact was not
resolved in GitHub during this run; that exact source identity remains **NOT_VERIFIED**.

This uncertainty is non-dispositive: even a stronger WorkManager acknowledgement cannot close the
root because YTDLnisX explicitly authorizes row deletion without requiring that acknowledgement.

### Supported-platform truth table

Project platform range:
- minSdk 24;
- targetSdk 36;
- compileSdk 36.

A. no admitted worker:
- no post-native effect owner;
- cancellation can converge without this race.

B. native still active:
- exact native quiescence is required before STOPPED;
- native-generation fence remains effective.

C. NATIVE_FINISHED, publication not committed:
- native is quiescent;
- external cancel can terminalize STOPPED and retire active token;
- worker can still be alive and reach publication;
- row deletion does not require WorkManager acknowledgement.
Result: **FAIL**.

D. semantic publication already committed:
- committed result remains stronger than later cancellation;
- preserved-success direction is correct.

E. process death with publication journal:
- exact journal/recovery carriers fence duplicate native replay and reconcile publication;
- restart protection does not serialize an in-process cancel against a still-running worker.
Result: does not close C.

## Triggered modules

### Module A — Platform capability / pre-semantic admission

BUG-BACKUP-11: **FAIL / OPEN**.

Normal SAF picker acquires a persistable grant before storing `command_path`. Generic backup/restore
transfers only the URI locator. Destination-side grant authority is not reconstructed or freshly
authorized before executable consumers can use the restored setting.

### Module B — External scheduler handoff / observation

- BUG-CANCEL-02: **FAIL / OPEN**.
- BUG-PAUSE-03: **FAIL / OPEN**.
- BUG-TERMINAL-11: **PASS** for its closed scheduler/enqueue cell.

For BUG-CANCEL-02, exact dispatch supersession, WorkManager cancellation acknowledgement, worker
coroutine completion, native quiescence, publication-effect quiescence, and final row/live-owner
retirement remain distinct facts.

For BUG-PAUSE-03, the fixed Pause-All snapshot can still be followed by a new valid sibling claim,
then coarse tag-wide cancellation without USER_PAUSE authority, then generic requeue.

### Module C — External representation / authority projection

BUG-BACKUP-11: **FAIL / OPEN**.

The serialized `command_path` preserves provider locator bytes but omits destination-side SAF
authorization provenance.

### Module F — Persisted schema-generation compatibility

No new production schema/generation change exists on 359ddbf9.

BUG-TERMINAL-11's direct current-generation production cell remains closed.

Status: PASS for the prior Terminal generation root; no newly triggered schema delta.

### Module H — Persisted executable configuration fan-out

BUG-BACKUP-11: **FAIL / OPEN**.

The restored locator can flow into Terminal command materialization/planning and command-type
download configuration without a fresh provider grant proof.

### Module I — Maintenance vs live-owner namespace

BUG-CANCEL-02 Terminal subcase: **FAIL / OPEN**.

`TerminalCacheOwnership.isLiveOwnedRoot()` requires the execution token to remain active in
`TerminalExecutionRegistry`. Cancellation can retire that token at NATIVE_FINISHED before worker
publication/unwind completes, weakening the maintenance live-owner fence.

This is a same-root consequence, not a new cache finding.

## L1-L6 fresh review and depth

Fresh BASELINE review was performed for L1-L6.

Accumulated current-SHA depth:
- L1 Durability & recovery: DEEP;
- L2 Identity & provenance: DEEP;
- L3 Concurrency & authority: DEEP;
- L4 Destructive ownership: DEEP;
- L5 Platform contract closure: **DEEP — promoted by this run**;
- L6 Cross-feature semantic propagation: BASELINE.

remaining_not_yet_deep:
- L6

next_not_yet_deep_lens: L6

next_lens_selection_reason:
R1/R4 plus deterministic rotation — L6 is the final not-yet-DEEP lens. If the same implementation
SHA is reviewed again before a remediation lands, trace every current open root through complete
cross-feature producer/carrier/consumer/recovery/final-effect propagation rather than revisiting an
already-DEEP lens without new evidence.

## Terminal fault / cross-attempt / live-owner recount

### BUG-CANCEL-02

Fault cell:
- worker reaches NATIVE_FINISHED;
- cancel wins durable dispatch/execution state;
- execution token is retired;
- worker has not yet completed publication;
- stale post-cancel publication can still cross the irreversible effect boundary.

Live-owner cell:
- maintenance protection depends on the same active token;
- early token retirement can make still-owned staging appear non-live.

Process-death cell:
- publication journals/recovery carriers block duplicate replay and preserve unresolved publication;
- they do not act as a same-process publication lock.

Result: OPEN.

### BUG-PAUSE-03

Cross-attempt/batch cell:
- A belongs to Pause-All snapshot;
- B is queued outside the snapshot;
- B claims after snapshot;
- later tag cancellation stops B;
- B has no USER_PAUSE durable disposition;
- generic cleanup may requeue B;
- Resume All only enumerates Paused rows.

Result: OPEN.

### BUG-BACKUP-11

Authority cell:
- source installation: provider locator + grant;
- backup: locator only;
- destination restore: locator may exist without grant;
- later executable consumer can bind locator;
- provider publication discovers capability failure late.

Result: OPEN.

## Candidate rejection — thread affinity

Candidate:
`cancelWorkByIdAndAwait()` blocks on `Operation.result.get(5s)`.

Confirmed user-facing Terminal cancellation paths:
- Terminal UI -> `cancelTerminalDownload()` -> `viewModelScope.launch(Dispatchers.IO)`;
- notification receiver -> `CoroutineScope(Dispatchers.IO).launch`.

No main-thread production call into the blocking cancellation helper was confirmed.

`TerminalViewModel.delete(id)` itself does not switch dispatcher before cancellation, but no
production callsite was established in the reviewed UI/receiver graph.

Disposition:
- no new thread-affinity finding;
- repository-wide absence of every possible caller remains NOT_VERIFIED;
- candidate rejected as unsupported, not counted.

## Root / alias reconciliation

- WorkManager cancellation semantics are evidence for existing BUG-CANCEL-02, not a new root.
- Terminal cache exposure is a downstream consequence of premature retirement of the same effect
  owner, not a new cache root.
- BUG-PAUSE-03 remains distinct because its producer is Pause-All target-set expansion.
- BUG-BACKUP-11 remains distinct because its producer is restore-time provider capability
  provenance.
- BUG-TERMINAL-11 remains FIXED-CLOSED.

No duplicate semantic root was counted.

## Persisted remediation prompt

Existing prompt:
`ytdlnisx/prompts/2026-09-26_BUG_CANCEL_02_TERMINAL_PUBLICATION_QUIESCENCE.md`

Prompt blob:
`56f1aa8a71749ee1c229e8ce5e81075e7b20f617`.

The prompt was re-read after the L5 review.

It already requires:
- native vs post-native effect quiescence separation;
- final pre-effect durable cancellation check;
- no row deletion/live-token retirement before effect quiescence or committed publication;
- process-death coverage for new states;
- cache live-owner preservation;
- committed publication stronger than later cancel;
- no reliance on WorkManager acknowledgement, elapsed time, `isStopped` alone, or token absence.

Therefore:
- prompt remains compatible;
- no replacement or duplicate prompt is needed;
- no scope expansion is authorized.

## Review retrospective

The L5 pass did not discover a new root. It sharpened why the existing BUG-CANCEL-02 remediation
must be app-owned rather than delegated to WorkManager cancellation.

The key semantic separation is now explicit:

native process finished
!= worker finished
!= publication effect quiescent
!= WorkManager cancellation request acknowledged
!= semantic publication committed.

The existing production code has strong native-generation and restart-recovery machinery, but the
post-native live effect owner is retired too early when cancellation wins.

The same run rechecked the other current blockers and found no evidence that stronger recovery
machinery had implicitly closed BUG-BACKUP-11 or BUG-PAUSE-03.

## Checklist evolution

Checklist gap: **none confirmed**.

No checklist or lens-policy change is proposed.

v7 already contains the applicable rules:
- asynchronous request is not completion;
- platform capability/pre-semantic admission;
- external scheduler handoff;
- final-effect authority;
- positive live-owner preservation;
- maintenance vs live-owner namespace;
- persisted executable configuration fan-out;
- external authority projection;
- thread-affinity review.

The remaining gaps are production implementation gaps, not governance omissions.

## Final checkpoint summary

- manual_review_run: YES
- manual_review_run_status: FINAL
- manual_review_start_parent:
  `6952437ed0bfe9589c5c497d838d92a667adc1e5`
- pinned implementation:
  `359ddbf9bf534009be095ad1bffea8ec45c899e4`
- intermediate:
  `cc1d406cef8c6272a8972b8dbc83f8cfe3b084d0`
- verdict: NOT_CLEAN
- BUG-TERMINAL-11: FIXED-CLOSED
- BUG-BACKUP-11: OPEN P2
- BUG-PAUSE-03: OPEN P2
- BUG-CANCEL-02 Terminal subcase: OPEN under existing P2 root
- new finding IDs: 0
- canonical totals: P0=0 / P1=0 / P2=19
- L5 promoted DEEP
- remaining not-yet-DEEP lens: L6
- next same-SHA lens hint: L6
- existing BUG-CANCEL-02 remediation prompt remains valid
- independent execution: NOT EXECUTED
