# Manual v7 review — 359ddbf9 — L2 DEEP intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: a56a43c62d26396b1c1a6383ece4a5285294ddbe

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
review_parent_sha: a56a43c62d26396b1c1a6383ece4a5285294ddbe
implementation_sha: 359ddbf9bf534009be095ad1bffea8ec45c899e4

## Pinned governance

- Master Plan: fada33a7eed86b1fa2c07065af66f14bf4d24714
- plan/remediation tip at run start: 2145847a1054da28398b730b9be0ca728668f967
- protocol blob: b9ff2c984a1655f0f214ab0ec89d3ccb18c0f913
- checklist v7 adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
- checklist v7 blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
- lens-policy adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
- lens-policy blob: 49600871d632fd8612bbabec80dfaa996afb54d3
- ledger/remediation at run start: b98d315006fa19fc6f22b017f43a91899db5fb81

Governance is fixed for this manual run.

## Independent verdict

**NOT_CLEAN.**

The exact implementation is a one-commit test-only descendant of dcad84ac:
`dcad84ac30aa6dac7096961e408b48971c2f0376..359ddbf9bf534009be095ad1bffea8ec45c899e4`.

The current test-only deterministic-quiescence correction is independently source-reviewed and
BUG-TERMINAL-11 remains FIXED-CLOSED. Production source is unchanged by 359ddbf9.

Three already-canonical P2 roots remain independently open on the exact current implementation:
- BUG-BACKUP-11;
- BUG-PAUSE-03;
- BUG-CANCEL-02 Terminal cancellation/publication/live-cache subcase.

No new P0/P1/P2 finding ID has been created.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

CLEAN_REVIEW_BASIS remains:
`74f57e695db30b701ad429af311c39a763bfe086`.

Independent execution: NOT EXECUTED.

## BUG-TERMINAL-11 closure re-proof

The 359ddbf9 test-only change replaces the prior elapsed-time negative-observation settle for the
generation-2 malformed-provider cell with a bounded drain of reconciliation-created work.

Exact source:
- the fixture reads the existing private `WorkManagerHandoffRecovery.convergenceScope` root Job
  through test-only reflection;
- it snapshots preexisting children immediately before the real `reconcile()`;
- it waits for the full positive sibling enqueue count;
- it repeatedly enumerates newly created root children and `joinAll()`s them inside a five-second
  `withTimeout` until no new relevant child appears;
- only then does it assert malformed non-enqueue.

Exact production composition:
- `reconcileTerminalDispatches()` classifies/stages rows transactionally and then calls
  `dispatchTerminalHandoff()` for every runnable handoff;
- each dispatch is a `convergenceScope.launch` and awaits the exact enqueue attempt;
- retry work is also installed in the same root convergence scope;
- child creation occurs before the creator can complete, so a join followed by another child
  enumeration observes any later sibling spawned by the joined work unless it already completed;
- completed work cannot later create a hidden enqueue, and any enqueue performed before completion is
  retained in the fixture's enqueue observations.

The negative proof is therefore a bounded completion/drain boundary, not a delay heuristic.

Disposition:
- BUG-TERMINAL-11: FIXED-CLOSED.
- Module B: PASS for this root.
- Module C: PASS for malformed-provider authority for this root.
- Module F: PASS for the exact generation-2 persisted cell.
- Module H: PASS for this root.
- no same-root residual found.

Implementation-agent reported exact-SHA execution remains evidence, not independent execution:
79/79 scoped connected tests, 68 JVM tests, both Kotlin compile gates and diff-check PASS.

## Existing P2 BUG-BACKUP-11 — current exact-source confirmation

Status: OPEN / CONFIRMED on 359ddbf9.

### Producer and capability acquisition

The normal settings UI uses `ACTION_OPEN_DOCUMENT_TREE`, requests read/write/persistable flags, and
calls `takePersistableUriPermission()` before storing the exact selected provider URI into
`command_path`.

Normal configuration therefore has two distinct facts:
1. locator: the `content://...` tree URI string;
2. capability/provenance: destination-side persisted SAF permission.

### Backup carrier

`BackupSettingsUtil.nonPortablePreferenceKeys` excludes `cache_path` but not `command_path`.

Therefore `command_path` is treated as a portable preference and
`backupSettings()` serializes the exact String value. The backup carries the locator only. It does
not carry a transferable destination-side SAF grant.

### Import/restore validation

`BackupRestoreParser` retains settings for which `isPortablePreferenceKey()` is true and validates
their representation/type. It does not reconstruct or independently prove destination-side provider
authorization.

Merge restore in `SettingsViewModel` writes portable String settings through
`putString(key, prefValue)` and commits them durably.

Reset restore in `RestoreTransactionCoordinator` preserves only destination-local/non-portable
settings and then replays portable settings through `putPortable()`; String values again become
`putString(item.key, item.value)`.

Neither restore path obtains a fresh tree grant or validates that the imported provider URI is
authorized on the destination installation.

### Executable consumer

`TerminalViewModel.insert()` reads the configured destination and passes it to
`TerminalCommandIntentMaterializer.materialize()` before the Terminal row and exact dispatch
carrier are committed.

The materializer can therefore freeze the imported provider locator into the durable command.

`TerminalCommandPlanFactory` later reads `command_path` and classifies a nonempty
`content://<authority>...` value as `TerminalDestinationAuthority.ProviderTree`.

That classification is syntactic. It does not prove a persisted URI grant.

The planner correctly refuses to reinterpret a provider tree as native writability and forces
app-owned staging, but the final provider destination remains the imported URI.

### Final effect

`TerminalDownloadWorker` publishes staged output through `FileUtil.moveFile(..., destDir =
downloadLocation)`.

Provider publication can then discover that the restored installation lacks provider authority and
fail at the final effect boundary. Runtime failure is not restore-time authorization.

The semantic mismatch is therefore:

portable locator
-> restored String
-> syntax-only ProviderTree classification
-> durable executable Terminal provider metadata
-> provider publication requiring a capability that restore never established.

### L2 identity/provenance conclusion

A provider locator is not provider capability provenance.

The restore path preserves location identity bytes but not the external authority that made those
bytes executable on the source installation. Current Terminal materialization upgrades the locator
into durable executable intent without a destination-side authorization proof.

BUG-BACKUP-11 remains one existing P2 root. No duplicate is created.

### Fan-out inventory

Additional `command_path` consumers were checked.

`DownloadViewModel` uses the setting when constructing/reconfiguring DownloadType.command paths and
history redownload defaults. These consumers inherit the restored locator but do not independently
supply the missing SAF grant.

`HistoryFileDeletion` also reads `command_path`, but its provider destructive authorization is
not locator-only: its configured-tree path additionally requires a matching persisted write
permission. This consumer fails closed and does not create a second finding root.

## Existing P2 BUG-PAUSE-03 — current baseline re-proof

Status: OPEN / CONFIRMED on 359ddbf9.

`pauseAllDownloads()`:
1. snapshots the current Active/PostProcessing rows under the short global execution lock;
2. releases that lock;
3. gives only saved snapshot members exact USER_PAUSE durable semantics and exact quiescence;
4. later calls `cancelAllWorkByTag("download")` without an operation-wide Pause-All generation or
   exact target set.

The normal scheduler can validly claim a queued sibling B after the initial snapshot. Its claim path
revalidates exact per-item ownership/capacity and publishes a fresh execution ID, but it does not
join any Pause-All generation.

The later tag-wide WorkManager cancellation can therefore stop B even though B never received
USER_PAUSE authority.

Stopped-worker recovery sees no USER_PAUSE/USER_CANCEL disposition for B and may fall through to
`requeueRunningDownload()`, which can reclassify exact Active/PostProcessing B to Queued.

Resume All explicitly enumerates Paused rows, so generic Queued B is not repaired as a member of the
original Pause-All operation.

No new ID; this is the established BUG-PAUSE-03 root.

## Existing P2 BUG-CANCEL-02 — Terminal subcase current baseline re-proof

Status: OPEN / CONFIRMED on 359ddbf9.

Terminal cancellation still separates:
- durable dispatch supersession;
- WorkManager cancellation acknowledgement;
- native/execution convergence.

`rowDeletionAuthorized` requires dispatch supersession plus execution convergence, not WorkManager
acknowledgement.

For a NATIVE_FINISHED witness, Terminal execution convergence can mark STOPPED and remove the
process-local active token even though post-native provider publication has not been proven
quiescent.

The worker still crosses from `markNativeFinished()` into publication and `FileUtil.moveFile()`
without a durable STOPPED authority recheck immediately before the irreversible external effect.
`markCommitting()` occurs after publication may already exist.

The same early active-token retirement weakens `TerminalCacheOwnership.isLiveOwnedRoot()`;
`AppCacheManager` can consequently lose its live-owner signal while the worker is still unwinding
publication.

This remains the existing BUG-CANCEL-02 final-effect/live-owner subcase, not a new cache finding.

## Trigger map

### Module B — external scheduler handoff

- BUG-TERMINAL-11: PASS.
- BUG-PAUSE-03: FAIL — exact semantic target snapshot can diverge from the later tag-wide transport
  target.
- BUG-CANCEL-02 Terminal: NOT_CLOSED — dispatch/WorkManager cancellation and exact publication-effect
  quiescence remain different facts.

### Module C — external representation / authority projection

- BUG-TERMINAL-11 malformed-provider representation: PASS.
- BUG-BACKUP-11: FAIL — portable URI locator does not carry destination-side SAF authorization.

### Module F — persisted schema-generation compatibility

- BUG-TERMINAL-11: PASS for the direct generation-2 persisted production cell and deterministic
  observation.
- 359ddbf9 introduces no production representation/schema change.

### Module H — persisted executable configuration fan-out

- BUG-TERMINAL-11: PASS.
- BUG-BACKUP-11: FAIL — imported `command_path` can later become durable executable provider
  metadata without a fresh grant proof.

### Module I — maintenance vs live-owner namespace

- BUG-CANCEL-02 Terminal subcase: FAIL — process-local Terminal liveness can disappear before
  post-native publication/recovery ownership is quiescent.

### Thread-affinity rule

Not newly triggered by the 359ddbf9 test-only change. No production mutex/lease/wait boundary was
introduced or widened by this commit.

### Invalid identity-transformation propagation

No new proven-invalid normalization/equality transformation arose from 359ddbf9. NOT_TRIGGERED.

## L1-L6 coverage

All six lenses received a fresh BASELINE pass for exact implementation 359ddbf9.

Accumulated current-SHA depth after this checkpoint:
- L1 Durability & recovery: DEEP — inherited from prior exact-current-SHA source review; baseline
  rechecked here.
- L2 Identity & provenance: **DEEP — primary in this manual run**.
- L3 Concurrency & authority: DEEP — inherited from prior exact-current-SHA review; baseline
  rechecked here.
- L4 Destructive ownership: BASELINE.
- L5 Platform contract closure: BASELINE.
- L6 Cross-feature semantic propagation: BASELINE.

primary_deep_lens: L2

primary_deep_selection_reason:
- R1: BUG-BACKUP-11 is directly an identity/provenance failure: a locator is promoted without the
  capability provenance required to execute it.
- R2: Module C and Module H remain triggered and fail on exactly that missing provenance.

remaining_not_yet_deep:
- L4
- L5
- L6

next_not_yet_deep_lens: L4

next_lens_selection_reason:
R1 — existing BUG-CANCEL-02 directly owns an irreversible publication/cache-live-owner destructive
boundary, making L4 the most direct remaining unresolved lens.

## Remaining scope before FINAL

- explicit root/candidate rejection reconciliation;
- final blocker/count recount;
- checklist-evolution decision;
- fresh implementation/governance/review reconciliation;
- append and verify FINAL checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
