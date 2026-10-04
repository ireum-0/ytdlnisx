# BUG-UPDATER-02 frozen-basis L1 startup ownership refinement

checkpoint_kind: ACTIVE_WAVE_FROZEN_BASIS_EXPLORATORY
manual_review_run: NO
implementation_agent_currently_working: YES
active_wave_scope: DEBUG_TEST_PACKAGE_ISOLATION_PREPARATION
active_wave_diff_inspected: NO
clean_review_basis: ee75b75786b8b6182dfc31946b6325b20294e73b
implementation_sha_reviewed: ee75b75786b8b6182dfc31946b6325b20294e73b
review_parent_sha: a0b667faa1ae5008afda9d8c1f9b36114c65885a

governing_protocol_blob: a3d864e29ad471eff4994446c0fb5c43a8905bf1
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610

active_root: BUG-UPDATER-02
severity: P2
root_status: OPEN_CONFIRMED_SAME_ROOT_RESIDUALS
canonical_count_change: 0
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3
review_depth: L1_DURABILITY_AND_RECOVERY_DEEP
review_result: FAIL
new_finding_ids: NONE
independent_execution: NOT_EXECUTED

## Why this checkpoint exists

A debug/test package-isolation implementation wave is active. Protocol freeze
forbids starting a competing manual-3 run or inspecting that in-progress wave.
Review is therefore limited to the already-clean basis ee75b757.

The current source still supports BUG-UPDATER-02 and exposes two additional
startup-ownership details that make its remediation boundary more precise.
They are not new roots and do not change canonical counts.

## Existing same-root cells reconfirmed

The remediation-ready BUG-UPDATER-02 checkpoint remains correct:

1. updater generation/committed/pending carriers are still portable through
   BackupSettingsUtil;
2. Restore-first startup can reject ordinary updater mutation and MainActivity
   swallows the one-shot failure;
3. Active/Queued startup gating skips updateOnStartup() without a durable/live
   deferred owner;
4. updater-first / Restore-second can interleave after beginMutation() releases
   the short Restore admission boundary but before native update/persistence
   completes.

ee75b757's BUG-UPDATER-04 runtime authority closes updater-vs-live-yt-dlp
consumer mutation races, but it does not create startup/retry ownership for
BUG-UPDATER-02.

## New same-root detail A — runtime-readiness race

App.onCreate() creates runtimeReadiness as an asynchronous Dispatchers.IO task.
That task awaits scheduler/Restore startup ordering and only then calls
initLibraries(), which initializes the youtubedl-android YoutubeDL singleton.

MainActivity independently launches its updater startup coroutine on
Dispatchers.IO and does not await App.runtimeReadiness.

For a built-in desired source (stable/nightly/master), UpdateUtil eventually
calls YoutubeDL.updateYoutubeDL(). The currently pinned app dependency is
io.github.junkfood02.youtubedl-android:library:0.18.1; its update path requires
the process-local YoutubeDL singleton to have completed init().

Therefore a valid cold-start interleaving is:

1. durable desired/committed mismatch or pending updater debt exists;
2. App.runtimeReadiness has not yet completed initLibraries();
3. MainActivity observes zero Active/Queued rows and enters updateOnStartup();
4. beginMutation() durably publishes the desired pending generation;
5. updater acquires shared runtime mutation authority before App.initLibraries();
6. built-in YoutubeDL.updateYoutubeDL() observes an uninitialized library and
   throws;
7. MainActivity's outer kotlin.runCatching consumes the failure;
8. App.initLibraries() may succeed later, but no same-process updater retry
   owner is created.

The durable pending preference keeps restart evidence, but liveness still
depends on another MainActivity startup/manual action. This violates the same
BUG-UPDATER-02 invariant that a transient startup blocker must not consume the
only convergence attempt.

This is not BUG-UPDATER-04: mutual exclusion works. The missing authority is
startup sequencing/retry responsibility.

## New same-root detail B — stale Active recovery ordering

App starts DownloadExecutionRecovery asynchronously after Restore recovery.
MainActivity does not await that reconciler before evaluating:

getDownloadsCountByStatus(listOf("Active", "Queued")) == 0

A stale Active row from an interrupted prior Download can therefore be observed
by MainActivity before DownloadExecutionRecovery converges that exact execution.
The updater startup path then skips without creating any updater owner.

DownloadExecutionRecovery can later quiesce/requeue/converge the stale Download,
and its own retryScope retains Download recovery responsibility. That recovery
owner does not own updater convergence.

If recovery yields Queued work, the current updater predicate remains false
anyway. If the Download later finishes or becomes non-runnable, no updater
callback/listener/reconciler is registered to consume the pending desired
generation in the same process.

Thus Active/Queued is not merely a weak safety check; it is a transient
observation of a separately recovering subsystem that can permanently consume
the updater's one startup opportunity.

## Updater-first / Restore-second durability trace

Current UpdateUtil ordering remains:

desired read
-> beginMutation() under RestoreMutationAdmission
-> release Restore ordinary-mutation admission
-> long native updater under YtdlpRuntimeAuthority
-> persistCommittedResult() under a new Restore ordinary-mutation admission

RestoreTransactionCoordinator does not acquire YtdlpRuntimeAuthority for its
preference publication path.

Consequently Reset may become authoritative after pending generation A is
published but before A's native update completes. Reset can publish restored
desired/provenance state while A is still mutating the runtime. A's later
persistCommittedResult() can be rejected by RestoreGate.

This overlap was already represented in the remediation-ready checkpoint and is
reconfirmed at ee75b757. The new shared runtime authority does not by itself
supply the Restore/updater semantic handoff.

## Refined correction boundary

BUG-UPDATER-04 now provides the canonical safety barrier against actual live
yt-dlp consumers. BUG-UPDATER-02 should therefore not preserve the
Active/Queued row-count snapshot as runtime safety authority.

The updater startup owner should instead satisfy all of these together:

- await or otherwise bind to Restore recovery completion;
- await the runtime initialization prerequisite required by built-in updater
  sources before consuming an updater attempt;
- invoke convergence even when Download rows are Active/Queued, allowing the
  shared YtdlpRuntimeAuthority to serialize actual runtime use, or establish an
  exact generation-bound deferred owner if another non-runtime prerequisite
  still blocks;
- survive transient Restore rejection, stale Active recovery, queued work, and
  runtime-readiness delay without requiring another app launch;
- coalesce duplicate wakeups by exact desired generation;
- preserve SUPERSEDED semantics when desired source changes;
- never allow an older updater result to become proof for a restored newer
  desired generation.

Do not solve this by holding RestoreMutationAdmission across unbounded
network/native work.

## Additional acceptance scenarios

### Runtime readiness

Seed a durable desired/committed mismatch or pending built-in source generation,
keep auto updates disabled, and hold App runtime initialization before
YoutubeDL.init completes.

Start the real MainActivity startup updater path.

Prove:
- the updater attempt is not consumed as a terminal failure merely because
  runtime initialization is not ready;
- after runtime readiness completes in the same process, exactly one updater
  reconciliation runs for the current generation;
- committed source/generation matches current desired state;
- pending state is retired only with matching committed publication.

### Stale Active recovery

Seed an abandoned Active Download execution plus updater mismatch/pending debt.

Start MainActivity before DownloadExecutionRecovery finishes.

Prove:
- the initial Active observation cannot permanently consume updater startup;
- Download recovery may converge/requeue its own execution without owning or
  deleting updater responsibility;
- after actual runtime ownership becomes available, exactly one updater
  reconciliation executes in the same process;
- no second app launch or manual settings action is required.

### Restore + runtime readiness composition

Hold active Reset recovery and runtime initialization independently.

Prove updater convergence waits for every required prerequisite without
replacing one transient blocker with another one-shot failure path.

## Test gap

UpdateUtilProductionWiringTest.startupReconcilesPersistedDesiredGenerationAfterCoordinatorRecreation()
directly calls updateOnStartup() with updaterForTesting and therefore does not
exercise:
- MainActivity's Active/Queued gate;
- App.runtimeReadiness ordering;
- real YoutubeDL singleton initialization;
- Restore-first rejection;
- stale Active Download recovery;
- same-process retry ownership after those blockers clear.

nativeFailureReleasesMutationOwnerForLaterRequest() likewise proves that a
second explicit invocation can succeed; it does not prove production owns that
second invocation.

No current production-wiring regression covers the runtime-readiness race or
stale-Active-before-recovery ordering above.

## Disposition

BUG-UPDATER-02 remains one OPEN P2 root.

The root is now remediation-ready with the additional requirement that startup
convergence be owned across Restore recovery, runtime initialization, and
Download recovery/real runtime-use transitions. No prompt was changed while the
separate debug/test package-isolation implementation agent is active.
