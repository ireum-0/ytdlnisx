# Manual v7 review — 359ddbf9 — L6 DEEP intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 1b014856b5402e19e0f4b9167de0e563b30f6cec

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
review_parent_sha: 1b014856b5402e19e0f4b9167de0e563b30f6cec
implementation_sha: 359ddbf9bf534009be095ad1bffea8ec45c899e4

## Pinned governance

- Master Plan: fada33a7eed86b1fa2c07065af66f14bf4d24714
- plan/remediation tip: 2145847a1054da28398b730b9be0ca728668f967
- protocol blob: 11caa19a94b3a299a1b2f451464a14ef991bc10c
- checklist v7 adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
- checklist v7 blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
- lens policy adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
- lens policy blob: 49600871d632fd8612bbabec80dfaa996afb54d3
- ledger/remediation: b98d315006fa19fc6f22b017f43a91899db5fb81

Governance is fixed for this manual run.

The current protocol's mandatory role routing is applicable:
- review/checkpoint/finding classification remains reviewer-owned;
- manual review lifecycle is independent of implementation-agent activity;
- implementation agent was NO at pin time.

## Independent verdict

**NOT_CLEAN.**

Exact GitHub-authoritative implementation:
`359ddbf9bf534009be095ad1bffea8ec45c899e4`.

Existing canonical blockers remain open:
- BUG-BACKUP-11 — OPEN P2;
- BUG-PAUSE-03 — OPEN P2;
- BUG-CANCEL-02 Terminal publication/effect-quiescence — OPEN under the existing P2 root.

BUG-TERMINAL-11 remains FIXED-CLOSED.

New P0/P1/P2 finding IDs confirmed in this run: **0**.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

The preserved local-only candidate
`b811724047f6ddfbd7c9c534f876e7f36e5f27d8`
was not used as source authority. Its reported focused row-convergence failure remains implementation-agent evidence with cause NOT_VERIFIED.

INDEPENDENT EXECUTION: NOT EXECUTED.

## Primary DEEP lens

primary_deep_lens: L6 Cross-feature semantic propagation

primary_deep_selection_reason:
- R1: current open roots cross multiple feature boundaries rather than terminating at their first producer;
- R4: the current fixes rely on preserved closure in Terminal dispatch/recovery, SAF consumers, scheduler ownership, and cache maintenance;
- deterministic rotation: L6 was the only remaining not-yet-DEEP lens on this exact SHA.

## Fresh L1-L6 baseline

A fresh full-checklist baseline was performed before the L6 promotion.

- L1 Durability & recovery: existing durable Terminal dispatch/execution/publication carriers remain present; BUG-CANCEL-02 still lacks post-native effect-quiescence ownership in remote source.
- L2 Identity & provenance: BUG-BACKUP-11 remains a locator-vs-destination-capability provenance defect.
- L3 Concurrency & authority: BUG-PAUSE-03 and BUG-CANCEL-02 concurrency windows remain source-reachable.
- L4 Destructive ownership: row deletion/cache maintenance still consume the same premature Terminal convergence fact.
- L5 Platform contract closure: persisted SAF permission and WorkManager facts remain separate from the app-owned semantic authority.
- L6 Cross-feature semantic propagation: **DEEP in this run**.

## L6 DEEP — BUG-CANCEL-02 propagation

### Producer / authority

`TerminalCancellationCoordinator.cancel()` produces:
- durable dispatch supersession;
- WorkManager cancellation acknowledgement;
- Terminal execution convergence.

`rowDeletionAuthorized` is only:
`dispatchSuperseded && executionConverged`.

### Durable carrier

`TerminalExecutionRecovery` carries:
ADMITTED -> NATIVE_STARTED -> NATIVE_FINISHED -> COMMITTING -> COMMITTED,
plus failure/stop states.

Remote source still treats NATIVE_FINISHED as native-quiescent and allows cancellation convergence from that state.

### Worker consumer / final effect

The exact worker can still proceed after NATIVE_FINISHED through:
- publication-journal setup;
- provider/file publication;
- `FileUtil.moveFile()`;
- publication commit recording;
- only later `markCommitting()`;
- row deletion;
- COMMITTED.

There is no final durable STOPPED/cancellation authority recheck immediately before the first irreversible publication effect.

### Process-local consumer

`TerminalExecutionRegistry.cancel()` removes the exact active token when execution convergence succeeds.

### Cache-maintenance consumer

`TerminalCacheOwnership.isLiveOwnedRoot()` recognizes a root as live only when the marker token is still present in `TerminalExecutionRegistry.isActiveNow()`.

`AppCacheManager` consumes that predicate before destructive maintenance.

Thus the same premature cancellation convergence can propagate as:
execution STOPPED
-> active token removed
-> cache root no longer positive-live-owned
-> maintenance may treat still-worker-owned staging as ordinary cleanup candidate.

This remains one BUG-CANCEL-02 semantic root, not a separate cache root.

### Recovery consumer

Terminal execution/publication recovery protects process-death re-entry and duplicate publication.

It does not act as a same-process serialization barrier between external cancellation and an already-running worker before publication quiescence.

Result: BUG-CANCEL-02 remains OPEN.

## L6 DEEP — BUG-BACKUP-11 propagation

### Authorized producer

Normal command-directory selection:
`ACTION_OPEN_DOCUMENT_TREE`
-> read/write/persistable flags
-> `takePersistableUriPermission()`
-> exact provider URI stored as `command_path`.

The normal configuration producer therefore creates locator + destination-local capability.

### Backup carrier

`BackupSettingsUtil.nonPortablePreferenceKeys` contains `cache_path` but not `command_path`.

`backupSettings()` serializes portable String preferences, so `command_path` is exported as locator bytes only.

### Restore consumers

Both restore paths replay the imported portable String:
- SettingsViewModel merge path;
- RestoreTransactionCoordinator reset path.

Neither re-enters the picker authorization protocol nor proves destination-side persisted write authority before committing the setting.

### Terminal consumer

`TerminalViewModel.insert()` reads the configured destination and
`TerminalCommandIntentMaterializer` can bind a provider command_path into exact durable Terminal command metadata before row + dispatch carrier staging.

This strengthens identity of the selected locator but does not synthesize the missing destination SAF grant.

### Command-download consumers

`DownloadViewModel` also consumes `command_path` when:
- creating command DownloadItems from results;
- switching an item to DownloadType.command;
- creating command redownloads from History.

Those consumers can therefore carry the same imported locator into executable download state.

### Publication consumer

`FileUtil.moveFile()` eventually needs a real writable raw/provider destination and can fail when no writable destination can be resolved.

That is late capability discovery, not restore-time authorization closure.

### Safe sibling consumer

`AndroidHistoryFileDeletionGateway` does not blindly trust the configured provider locator:
its SAF document authorization checks persisted write grants.

This is a safe sibling consumer and does not close the unsafe restore -> executable-consumer root.

Result: BUG-BACKUP-11 remains OPEN; the additional command-download and Terminal consumers are propagation of the same upstream restore authority defect, not new roots.

## L6 DEEP — BUG-PAUSE-03 propagation

### Producer

Pause All snapshots the exact Active/PostProcessing rows, and only those snapshot members receive durable USER_PAUSE recovery authority.

### Scheduler fan-out

After per-snapshot convergence, Pause All invokes:
`WorkManager.cancelAllWorkByTag("download")`.

Ordinary/scheduled DownloadWorker requests share the `download` tag.

A valid queued sibling can therefore be admitted/claimed after the fixed snapshot but before/because of the broad tag cancellation.

### Downstream consumer

That sibling has no USER_PAUSE durable disposition because it was outside the original semantic target set.

Its worker/recovery path can therefore interpret the stop as generic scheduler interruption and requeue it to Queued rather than Paused.

### Resume consumer

Resume All enumerates Paused rows. The late sibling was never semantically paused, so Resume All does not own it.

This is one operation-set-expansion root:
fixed semantic snapshot
-> broader scheduler cancellation namespace
-> sibling without USER_PAUSE carrier
-> generic requeue
-> missing Resume-All membership.

Result: BUG-PAUSE-03 remains OPEN.

## Preserved closed root

BUG-TERMINAL-11 remains FIXED-CLOSED.

No new production schema/generation change exists on the exact remote SHA. The current-format generation and strict provider-metadata admission closure are not contradicted by the L6 propagation review.

Module F remains PASS for this closed root.

## Trigger map

- Module A platform capability/pre-semantic admission:
  BUG-BACKUP-11 TRIGGERED / FAIL.
- Module B external scheduler handoff/observation:
  BUG-CANCEL-02 TRIGGERED / FAIL;
  BUG-PAUSE-03 TRIGGERED / FAIL;
  BUG-TERMINAL-11 preserved PASS.
- Module C external representation/authority projection:
  BUG-BACKUP-11 TRIGGERED / FAIL.
- Module F persisted schema-generation compatibility:
  prior Terminal generation root preserved PASS; no new schema delta.
- Module H persisted executable configuration fan-out:
  BUG-BACKUP-11 TRIGGERED / FAIL.
- Module I maintenance vs live-owner namespace:
  BUG-CANCEL-02 TRIGGERED / FAIL.

No blocker-relevant triggered module is silently deferred.

## Thread-affinity candidate

Exact remote source confirms `FileUtil.moveFile()` enters
`withContext(Dispatchers.Main)`.

Provider/MediaStore helper branches subsequently transfer their heavy work to IO, but the direct raw-file branch remains in the Main-dispatch block and can execute:
- directory walk/filter;
- `Files.move` on API >= 26;
- `copyTo` + delete on older APIs;
- cleanup and scan handoff.

Production workers often wrap the helper in `Dispatchers.IO`, but the helper's internal Main switch overrides that caller dispatcher for this region.

This is a real thread-affinity/hardening candidate.

Canonical reconciliation:
- historical M3 concerned detached Terminal publication and is a different, already-remediated ownership issue;
- historical MainActivity ANR/OOM concerned unbounded shared-text input and is a different root;
- no current canonical checkpoint for this FileUtil raw-publication thread-affinity issue was found.

Disposition:
- separate P1/P2 correctness-blocker severity: NOT_VERIFIED;
- no new finding ID;
- no canonical count change;
- retain as hardening candidate until concrete production liveness/correctness impact is independently established under current-source behavior.

## Root / alias reconciliation

- BUG-CANCEL-02 row-deletion, publication, recovery, and cache-maintenance consequences are one root: cancellation retires effect ownership before post-native effect quiescence.
- BUG-BACKUP-11 Terminal and command-download consumers are one root: imported provider locator is not destination capability provenance.
- BUG-PAUSE-03 scheduler and Resume-All effects are one root: scheduler cancellation namespace is broader than the semantic Pause-All target set.
- thread-affinity candidate is not counted.
- local candidate b8117240 failure is not a new source root because its exact local patch is not GitHub-authoritative and cause remains NOT_VERIFIED.

## Lens coverage

lens_coverage_current_sha:
- L1 Durability & recovery: DEEP
- L2 Identity & provenance: DEEP
- L3 Concurrency & authority: DEEP
- L4 Destructive ownership: DEEP
- L5 Platform contract closure: DEEP
- L6 Cross-feature semantic propagation: **DEEP**

remaining_not_yet_deep: NONE
next_not_yet_deep_lens: NONE

If this exact SHA is reviewed again without materially new evidence, do not create a fake rotation.
Move to the current governed action/root instead.

## Review retrospective

The final lens did not discover a new canonical P1/P2 root.

It did make each open root's cross-feature propagation explicit:
- BUG-CANCEL-02 reaches cache maintenance through active-token retirement;
- BUG-BACKUP-11 reaches both Terminal durable command identity and ordinary command DownloadItem construction;
- BUG-PAUSE-03 reaches scheduler, generic recovery/requeue, and Resume-All membership.

The current local-only b8117240 candidate remains intentionally outside source closure. The no-push diagnostic remains the next implementation-agent action unless later authoritative state changes.

## Checklist evolution

No checklist gap confirmed.

Checklist v7 already requires:
- full consumer/effect graph propagation;
- external capability projection;
- asynchronous request vs completion;
- positive live authority;
- scheduler handoff;
- thread affinity.

No checklist or lens-policy change is proposed.

## Remaining scope before FINAL

- final implementation/review/private/ledger fresh-check;
- verify no implementation-agent start occurred during this manual run;
- verify no review writer race;
- append FINAL checkpoint;
- update handoff only if compatible, preserving the existing no-push BUG-CANCEL-02 b811 diagnostic as next action.

INDEPENDENT EXECUTION: NOT EXECUTED
