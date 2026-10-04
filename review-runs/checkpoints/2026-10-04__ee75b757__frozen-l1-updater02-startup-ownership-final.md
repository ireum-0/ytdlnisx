# Frozen-basis correctness continuation — ee75b757 — L1 durability/recovery (final)

checkpoint_kind: ACTIVE_WAVE_FROZEN_BASIS_EXPLORATORY_FINAL
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_reason: NEW_MANUAL_3_RUN_PROHIBITED_WHILE_IMPLEMENTATION_AGENT_ACTIVE
implementation_agent_currently_working: YES
active_wave_scope: DEBUG_TEST_PACKAGE_ISOLATION_PREPARATION
active_wave_diff_inspected: NO

review_parent_sha: 851d33e67c1498673b9a6c61a0616cca88424510
supporting_checkpoint: review-runs/checkpoints/2026-10-04__ee75b757__frozen-l1-updater02-startup-ownership.md
clean_review_basis: ee75b75786b8b6182dfc31946b6325b20294e73b
implementation_sha_reviewed: ee75b75786b8b6182dfc31946b6325b20294e73b

master_plan_commit: fada33a7eed86b1fa2c07065af66f14bf4d24714
master_plan_sha256: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
ledger_reference: 899328bc91e4008e39a658387396a0106c8666ec
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
protocol_blob: a3d864e29ad471eff4994446c0fb5c43a8905bf1

overall_verdict: NOT_CLEAN
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3
canonical_open_roots: BUG-UPDATER-02,BUG-UPDATER-03,BUG-HISTORY-05
new_finding_ids: NONE
count_change: 0

review_lens: L1 Durability & recovery
review_depth: DEEP
review_result: FAIL
manual_run_coverage_mutated: NO

## Independent source result

BUG-UPDATER-02 remains OPEN P2 on ee75b757.

The previously canonical four cells remain:
- foreign updater generation/committed/pending provenance is portable;
- Restore-first startup rejection has no same-process updater retry owner;
- Active/Queued startup deferral has no updater retry owner;
- updater-first / Restore-second can overlap after the short pending-publication admission boundary.

Two additional same-root production details are now directly established.

### Runtime-readiness one-shot loss

App.runtimeReadiness is asynchronous and only initializes the youtubedl-android
YoutubeDL singleton inside initLibraries() after Restore/scheduler startup work.

MainActivity independently launches startup updater work and does not await that
readiness.

For stable/nightly/master, UpdateUtil.performYoutubeDLUpdate() calls
YoutubeDL.updateYoutubeDL(). With the pinned 0.18.1 dependency, that path
requires process-local YoutubeDL initialization.

Therefore an updater with durable mismatch/pending debt can acquire runtime
mutation authority before initLibraries(), fail because the library is not yet
initialized, and have the exception consumed by MainActivity.runCatching.
Later successful library initialization does not install a replacement updater
attempt.

Pending preferences preserve restart evidence but not same-process liveness.

### Stale Active recovery can consume updater startup

DownloadExecutionRecovery is asynchronous after Restore recovery. MainActivity
does not await it before its one-time Active/Queued row-count check.

A stale Active row from an interrupted prior execution can therefore suppress
updater startup before Download recovery converges it. Download recovery owns
only Download debt. It does not own updater desired-generation convergence.

If the recovered row becomes Queued, the same startup predicate would continue
to block. If it later becomes non-runnable, there is no production listener or
retry owner that invokes updater reconciliation in that same process.

## Cross-root interaction after BUG-UPDATER-04

BUG-UPDATER-04 remains CLOSED.

Its YtdlpRuntimeAuthority is now the canonical safety barrier for actual live
yt-dlp runtime consumers. Therefore BUG-UPDATER-02 should not retain the
Active/Queued database snapshot as mutation-safety authority.

A corrected startup owner can wait on the real runtime authority while retaining
exact desired-generation responsibility, rather than dropping responsibility
because a row happened to be Active or Queued at one observation.

This is a refinement of BUG-UPDATER-02, not a reopen of BUG-UPDATER-04.

## Restore overlap reconfirmation

UpdateUtil still performs:

pending publication under RestoreMutationAdmission
-> release Restore admission
-> long native mutation under YtdlpRuntimeAuthority
-> later committed publication under a new Restore admission

Restore preference publication uses RestoreMutationAdmission but does not acquire
YtdlpRuntimeAuthority.

Thus updater A can publish pending A, Reset can become authoritative and publish
restored desired state B, and A can still finish native mutation before its
committed publication is rejected by active Restore.

This exact interleaving was already part of the remediation-ready
BUG-UPDATER-02 contract and remains valid at ee75b757.

## Required remediation refinement

One generation-bound startup convergence owner must survive all transient
prerequisites:

- active Restore recovery;
- App runtime initialization required by built-in updater sources;
- stale Active Download recovery;
- queued/live runtime use;
- updater-first / Restore-second supersession.

The owner must:
- never require another app launch to consume a blocker that clears in-process;
- coalesce duplicate wakeups by exact desired generation;
- preserve SUPERSEDED semantics;
- reuse YtdlpRuntimeAuthority for actual runtime-use exclusion;
- prevent old updater results from becoming proof for a restored newer desired
  generation;
- avoid holding RestoreMutationAdmission across unbounded network/native work.

## Test gap

Current UpdateUtilProductionWiringTest directly invokes updateOnStartup() and
injects updaterForTesting. It proves helper recovery only after an invocation
exists.

It does not exercise:
- MainActivity's one-shot Active/Queued gate;
- App.runtimeReadiness versus MainActivity ordering;
- real YoutubeDL singleton initialization;
- stale Active DownloadExecutionRecovery ordering;
- same-process retry responsibility after any of those blockers clear.

No independent tests were executed by this review.

## Lens/accounting disposition

This frozen-basis continuation supplies DEEP L1 evidence for ee75b757 but does
not create or resume a manual-3 run and does not rewrite the already-FINAL
manual L3 checkpoint.

Future manual review may use these canonical source decisions, but it must obey
the normal run/resume rules and must not pretend this checkpoint was a
manual_review_run.

Canonical count remains P0=0, P1=0, P2=3.

independent_execution: NOT_EXECUTED
