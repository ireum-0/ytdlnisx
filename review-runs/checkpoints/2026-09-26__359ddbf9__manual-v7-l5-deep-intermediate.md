# Manual v7 review — 359ddbf9 — L5 DEEP intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 6952437ed0bfe9589c5c497d838d92a667adc1e5

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
review_parent_sha: 6952437ed0bfe9589c5c497d838d92a667adc1e5
implementation_sha: 359ddbf9bf534009be095ad1bffea8ec45c899e4

## Pinned governance

- Master Plan: fada33a7eed86b1fa2c07065af66f14bf4d24714
- plan/remediation tip: 2145847a1054da28398b730b9be0ca728668f967
- protocol blob: b9ff2c984a1655f0f214ab0ec89d3ccb18c0f913
- checklist v7 adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
- checklist v7 blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
- lens policy adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
- lens policy blob: 49600871d632fd8612bbabec80dfaa996afb54d3
- ledger/remediation: b98d315006fa19fc6f22b017f43a91899db5fb81

Governance is fixed for this manual run.

## Independent verdict

**NOT_CLEAN.**

This is a new manual run on the unchanged exact implementation
`359ddbf9bf534009be095ad1bffea8ec45c899e4`, started after the separate exploratory L4 checkpoint
at `6952437ed0bfe9589c5c497d838d92a667adc1e5`.

A fresh L1-L6 baseline and trigger recomputation were performed. Existing canonical blockers remain:
- BUG-BACKUP-11 — OPEN P2;
- BUG-PAUSE-03 — OPEN P2;
- BUG-CANCEL-02 Terminal publication/effect-quiescence subcase — OPEN under the existing P2 root.

BUG-TERMINAL-11 remains FIXED-CLOSED.

No new P0/P1/P2 finding ID has been confirmed.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

Independent execution: NOT EXECUTED.

## Primary DEEP lens

primary_deep_lens: L5 Platform contract closure

primary_deep_selection_reason:
- R1: BUG-CANCEL-02 currently depends on the semantic boundary between WorkManager cancellation,
  CoroutineWorker stoppage, app-owned execution witness convergence, and irreversible publication.
- R2: Module B remains OPEN/FAIL for this root because scheduler cancellation and actual
  effect-owner completion are distinct facts.
- R3: no production implementation changed; this same-SHA turn is a required remaining-lens
  promotion after L4 became DEEP.

## L5 DEEP — BUG-CANCEL-02 Terminal platform contract

### Project platform/dependency basis

Exact implementation configuration:
- minSdk = 24;
- targetSdk = 36;
- compileSdk = 36;
- `androidx.work:work-runtime-ktx:2.11.0`.

### App cancellation contract

`TerminalCancellationCoordinator.cancel()` deliberately separates:
1. durable dispatch supersession;
2. WorkManager cancellation acknowledgement;
3. app execution/native convergence.

However `rowDeletionAuthorized` is:
`dispatchSuperseded && executionConverged`.

It does **not** require `workManagerCancellationAcknowledged`.

Therefore the safety of Terminal row deletion and live-owner retirement cannot depend on
WorkManager cancellation having completed strongly. Even a timeout/failure of that external
acknowledgement can coexist with row-deletion authorization if app execution convergence returns
true.

### App WorkManager observation

`WorkManagerHandoffRecovery.cancelTerminalDispatch()`:
- supersedes the exact durable TERMINAL_DISPATCH carrier first;
- then invokes `cancelWorkByIdAndAwait()` or `cancelUniqueWorkAndAwait()`;
- those helpers block on `Operation.result.get(5_000ms)`;
- success becomes only `workManagerCancellationAcknowledged=true`.

The acknowledgement is returned to the coordinator but is not part of row-deletion authority.

This means the exact strength of the WorkManager Operation contract cannot repair the app root:
the app explicitly permits deletion/convergence without consuming that fact.

### App execution-witness contract

`TerminalExecutionRecovery.markNativeFinished()` records NATIVE_FINISHED after native yt-dlp
returns.

For a STOPPED outcome, `markTerminalFailure()` can transition NATIVE_FINISHED directly to
TERMINAL_STOPPED.

`TerminalExecutionRecovery.convergeTerminal()` requires destructive native-process quiescence for
NATIVE_STARTED/NATIVE_QUIESCENCE_PENDING, but treats NATIVE_FINISHED as already native-quiescent.

That is valid for the native process and insufficient for the later app-owned publication effect.

`TerminalExecutionRegistry.cancel()` then removes the process-local active token when
`convergeTerminal(... STOPPED)` returns true.

So the app's current `executionConverged` means:
- native generation is no longer running;
- execution witness is terminalized;

but it does **not** prove:
- the CoroutineWorker has exited;
- publication IO has stopped;
- FileUtil/provider callbacks are quiescent;
- the staging root no longer has a live effect owner.

### Worker final-effect contract

The actual worker sequence remains:

native execution
-> `markNativeFinished()`
-> publication journal begin/PUBLISHING
-> `FileUtil.moveFile(...)`
-> exact publication recording
-> publication journal COMMITTING
-> log/notification work
-> delay
-> `TerminalExecutionRecovery.markCommitting()`
-> DAO row delete
-> semantic commit/COMMITTED.

There is no durable STOPPED/cancellation-authority recheck immediately before
`FileUtil.moveFile()`.

A concurrent cancellation can therefore:
- observe NATIVE_FINISHED;
- terminalize the execution witness as STOPPED;
- remove the active execution token;
- authorize row deletion;

while the already-running worker is between NATIVE_FINISHED and the irreversible provider/file
publication boundary.

The worker's own outer `finally` contains strong NonCancellable cleanup when WorkManager
cancellation actually unwinds the CoroutineWorker. That does not serialize against the separate
external `TerminalExecutionRegistry.cancel()` completing first.

### WorkManager contract corroboration

Official AndroidX GitHub source was inspected as corroborating platform evidence:
- cancellation marks WorkSpecs cancelled, asks Processor to stop/cancel work, and cancels schedulers;
- Processor's stop/cancel path invokes WorkerWrapper interruption;
- WorkerWrapper interruption cancels its worker coroutine Job.

The current upstream implementation makes cancellation request/interrupt and worker future
completion distinct.

The exact Git commit corresponding to the published WorkManager 2.11.0 source artifact was not
resolved from GitHub in this run, so version-exact implementation identity is **NOT_VERIFIED**.

This does not weaken the BUG-CANCEL-02 conclusion because YTDLnisX does not require the WorkManager
acknowledgement for row deletion at all. The root is independently proven from the app's own
authority graph.

### Platform truth table

Supported API band: API 24 through target API 36.

Cell A — no admitted Terminal worker:
- dispatch supersession + no durable execution witness can converge without external effect;
- no BUG-CANCEL-02 effect window.

Cell B — worker before/during native:
- durable witness requires exact native quiescence before STOPPED;
- native process barrier protects the native-generation interval.

Cell C — worker at NATIVE_FINISHED, before publication:
- native is quiescent;
- app cancel may terminalize STOPPED and retire active token;
- worker may still own publication;
- row deletion can be authorized without WorkManager acknowledgement.
Result: FAIL.

Cell D — publication has durably committed:
- committed publication/DAO semantic commit is stronger and later cleanup must not reinterpret it.
This preserved-success direction is conceptually correct, but it does not close Cell C.

Cell E — process death with publication journal:
- TerminalPublicationRecovery preserves/reconciles exact publication state and blocks duplicate
  admission;
- this protects restart/re-entry, but does not serialize an in-process cancel against a still-live
  worker before process death.
Result: recovery does not close Cell C.

### L5 conclusion

BUG-CANCEL-02 remains OPEN.

Required contract closure is app-owned:
- durable post-native publication/effect ownership;
- final cancellation-authority check immediately before irreversible publication;
- row deletion and live-token retirement only after effect quiescence or stronger committed
  publication;
- cancellation/process-death recovery must preserve that distinction.

No new finding ID.

## Module B — external scheduler handoff/observation

BUG-CANCEL-02: FAIL/OPEN.

Exact facts that remain distinct:
- durable dispatch supersession;
- WorkManager cancellation request/Operation acknowledgement;
- worker coroutine actual completion;
- native quiescence;
- app publication-effect quiescence;
- final row/live-owner retirement.

The current coordinator collapses the last two app responsibilities too early at NATIVE_FINISHED.

BUG-PAUSE-03 also remains FAIL:
- Pause All snapshots Active/PostProcessing rows;
- a queued sibling can claim afterward;
- later tag-wide cancellation can stop it without USER_PAUSE authority;
- generic cleanup can requeue it to Queued.

BUG-TERMINAL-11 remains PASS for its scheduler/enqueue observation cell.

## Module I — maintenance vs live-owner namespace

BUG-CANCEL-02 remains FAIL/OPEN.

`TerminalCacheOwnership.isLiveOwnedRoot()` requires an ownership marker whose token is currently
present in `TerminalExecutionRegistry.isActiveNow()`.

After external cancellation terminalizes the NATIVE_FINISHED witness, that token can be removed
before the still-running worker has finished publication/unwind.

`AppCacheManager` relies on `TerminalCacheOwnership.isLiveOwnedRoot()` when deciding whether an
entry remains protected during maintenance.

Thus the same premature effect-owner retirement can remove the live-maintenance fence. This is a
same-root destructive consequence, not a separate cache finding.

## Module A — platform capability/pre-semantic admission

BUG-BACKUP-11: FAIL/OPEN.

The normal command-path picker obtains a persistable SAF read/write grant before storing the
provider URI.

Backup/restore carries the provider locator string but not destination-side capability provenance.
Across the supported API band, the relevant truth table is:

- destination has matching grant -> provider publication may be admitted;
- destination lacks matching grant -> restored locator alone is not authority;
- generic restore does not request a fresh picker grant;
- later executable consumers may still bind that locator.

Therefore platform permission admission remains incomplete at restore.

## Module C — external representation/authority projection

BUG-BACKUP-11: FAIL/OPEN.

`command_path` remains portable while `cache_path` is explicitly non-portable. Backup serializes
the command provider URI as a String; merge/reset restore replay it as a String.

The omitted authority dimension is the destination installation's SAF grant.

## Module H — persisted executable configuration fan-out

BUG-BACKUP-11: FAIL/OPEN.

Restored `command_path` can later be consumed by Terminal creation/planning and command-type
download defaults. The safe HistoryFileDeletion consumer independently requires persisted write
permission, so it does not create a second root.

## Module F — persisted schema-generation compatibility

No new production representation or schema change exists on 359ddbf9.

BUG-TERMINAL-11's current-format/generation-2 direct production cell remains FIXED-CLOSED from the
same exact implementation; no new collision evidence was found in this run.

Status: PASS for the prior Terminal generation root; otherwise not newly triggered.

## Thread-affinity candidate

Candidate:
`cancelWorkByIdAndAwait()` uses blocking `Operation.result.get(5s)`.

Reviewed production Terminal cancellation entry points:
- Terminal UI -> `TerminalViewModel.cancelTerminalDownload()` -> `viewModelScope.launch(Dispatchers.IO)`;
- notification receiver -> `CoroutineScope(Dispatchers.IO).launch`.

No reachable UI/main-thread use of this blocking helper was confirmed.

`TerminalViewModel.delete(id)` is a public suspend wrapper that itself does not switch to IO before
the coordinator call, but no production callsite for it was confirmed in the reviewed Terminal UI
or receiver graph.

Disposition:
- no new thread-affinity finding;
- repository-wide absence of every hypothetical caller is NOT_VERIFIED, so this is a rejected
  candidate, not affirmative proof that the helper is universally safe.

## Process-death/recovery baseline

`TerminalPublicationRecovery`:
- discovers exact publication journals;
- blocks admission on opaque/unresolved state;
- preserves unknown provider reservations;
- requires semantic-commit evidence before committed retirement;
- keeps quarantine/recovery carriers rather than treating them as ordinary publication authority.

This is strong restart/re-entry protection.

It cannot close the in-process BUG-CANCEL-02 interval because the race is:
live worker exists
-> external cancel terminalizes NATIVE_FINISHED/active token
-> worker has not yet crossed or left publication
-> publication may still execute.

Recovery after process death is not an in-process effect barrier.

## L1-L6 fresh baseline and accumulated depth

Fresh baseline performed:
- L1 Durability & recovery: existing exact carriers/recovery reviewed; BUG-CANCEL-02 effect ownership
  remains incomplete before publication.
- L2 Identity & provenance: BUG-BACKUP-11 remains locator-vs-capability provenance failure.
- L3 Concurrency & authority: BUG-PAUSE-03 and BUG-CANCEL-02 races remain current.
- L4 Destructive ownership: prior exploratory L4 DEEP remains supported by fresh baseline.
- L5 Platform contract closure: **DEEP in this manual run**.
- L6 Cross-feature semantic propagation: baseline re-traced across cancellation -> worker ->
  publication -> cache maintenance and backup -> restore -> executable consumers.

Accumulated current-SHA depth:
- L1 DEEP
- L2 DEEP
- L3 DEEP
- L4 DEEP
- L5 **DEEP**
- L6 BASELINE

remaining_not_yet_deep:
- L6

next_not_yet_deep_lens: L6

next_lens_selection_reason:
R1/R4 — with BUG-CANCEL-02 still open, L6 is the final not-yet-DEEP lens and must trace the same
cancel/effect authority through every cross-feature consumer plus BUG-BACKUP-11 and BUG-PAUSE-03
fan-out before all lenses can be declared DEEP.

## Root/alias reconciliation

- BUG-CANCEL-02 Terminal publication/cache consequences remain one root: cancellation authority can
  retire the effect owner before irreversible effect quiescence.
- WorkManager acknowledgement insufficiency is not a new scheduler finding; it is one platform
  manifestation of the same root.
- cache-maintenance exposure is not a new cache finding; it is caused by the same early active-token
  retirement.
- BUG-PAUSE-03 remains separate because its origin is operation-set expansion after a fixed Pause-All
  snapshot.
- BUG-BACKUP-11 remains separate because its origin is imported provider capability provenance.
- BUG-TERMINAL-11 remains FIXED-CLOSED.

## Remaining scope before FINAL

- final trigger/module recount;
- terminal/cross-attempt/live-owner matrix recount;
- checklist-evolution decision;
- fresh implementation/review/private/ledger reconciliation;
- append and verify FINAL checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
