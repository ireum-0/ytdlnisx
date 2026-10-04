# BUG-UPDATER-02 — 58e6313 independent completion review

checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO

review_parent_sha: 85588284d7567428b35c60acfb4862d3fafaee33
implementation_parent_sha: fe81ec54804f032c61cb8fb884039fe5dff16429
implementation_sha: 58e631394b3f5a868307fba8e9f8382436023949
canonical_count_change: 0

overall_verdict: PARTIAL_SOURCE_ACCEPTED_SAME_ROOT_RESIDUAL
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3

## Accepted checkpoint scope

58e6313 is exactly one normal-forward commit from fe81ec5 with merge base fe81ec5.

Changed production/test paths are limited to:
- App.kt
- MainActivity.kt
- StartupYtdlpUpdateOwner.kt
- UpdateUtil.kt
- UpdateUtilProductionWiringTest.kt
- YtdlpRuntimeAuthorityProductionWiringTest.kt

The source correction materially improves the startup-convergence sub-scope:
- process-lifetime Application ownership replaces MainActivity's one-shot updater;
- startup responsibility survives runtime readiness/recovery blockers;
- stale Active recovery is rechecked and a Queued snapshot is no longer treated as durable mutation authority;
- same-generation wakeups are conflated/coalesced;
- null SharedPreferences clear callbacks are handled;
- Android versions that emit no clear callback receive a bounded idle recheck;
- startup old-generation results require exact pending intent and exact desired source at commit;
- Restore-retired startup intent returns SUPERSEDED instead of becoming durable proof.

The recorded bulk preference-clear concern is source-resolved by the null-callback plus bounded idle-recheck path and the deterministic production-wiring regression.

Reported verification:
- production and AndroidTest compilation PASS;
- JVM AppStartupReconciliationTest: 6 PASS / 0 FAIL / 0 skipped;
- runtime/device execution NOT_VERIFIED.

The independent reviewer did not rerun execution.

## Same-root residual 1 — foreign updater provenance remains portable

BackupSettingsUtil still treats the following destination runtime-authority carriers as portable because they are absent from nonPortablePreferenceKeys and no updater-specific predicate excludes them:
- ytdlp_source_generation
- ytdlp_committed_source_generation
- ytdlp_committed_source
- ytdlp_committed_result
- ytdlp_pending_source_generation
- ytdlp_pending_source

Backup restore therefore can still import source-device generation/pending/committed proof.

This preserves the canonical BUG-UPDATER-02 provenance failure: numeric/source equality imported from another installation can satisfy destination committedMatches() without proving the destination runtime.

This is not a new root.

## Same-root residual 2 — non-startup updater result can still commit after restored desired source changes

58e6313 added requireDesiredAtCommit only to updateStartupGeneration().

Manual/worker updateYoutubeDL() still calls update(... requireDesiredAtCommit=false).

Concrete production ordering remains possible:
1. updater A observes desired source/generation A and publishes pending A under ordinary mutation admission;
2. it releases RestoreMutationAdmission and enters long native work under YtdlpRuntimeAuthority;
3. Merge restore acquires the short ordinary mutation boundary and changes portable desired source/generation to B while pending A remains present if the Merge payload does not replace those keys;
4. updater A completes native work;
5. persistCommittedResult(A, ..., requireDesiredAtCommit=false) accepts matching pending A even though current desired state is B;
6. A can become durable committed proof after restored desired state changed.

Reset often retires/overwrites pending carriers, but correctness cannot rely on that incidental payload shape, and Merge is an admitted production restore path.

The root invariant requires an old updater result never to become proof for a newer restored desired generation. This must apply to all updater entry paths, not only startup.

This is the existing updater-first/Restore-second BUG-UPDATER-02 cell, not a new root.

## Required next correction

Close the remaining BUG-UPDATER-02 source contract before runtime/device closure:

1. Make updater generation/committed/pending authority destination-local and nonportable while keeping ytdlp_source and ytdlp_source_label portable user intent.
2. At shared restore application, preserve same-source destination-local committed proof, but when restored desired source changes:
   - allocate/advance a destination-local desired generation;
   - invalidate incompatible local committed/pending proof;
   - never accept imported generation/committed/pending carriers.
3. Apply exact desired-source/generation validation at durable commit to every updater entry path so manual/worker/startup old results cannot commit after restore changed desired state.
4. Preserve SUPERSEDED behavior, manual update semantics, BUG-UPDATER-04 runtime authority, and the new process-lifetime startup owner.
5. Add focused Merge and Reset regressions for foreign provenance and old-result commit rejection.

## Disposition

- 58e6313 publication is accepted as a coherent forward BUG-UPDATER-02 checkpoint.
- Startup-convergence/bulk-clear sub-scope: SOURCE_FIXED.
- BUG-UPDATER-02 root: OPEN P2.
- Runtime/device closure: NOT_VERIFIED and not yet the next gate because source residuals remain.
- No new finding/root and no canonical count change.

INDEPENDENT EXECUTION: NOT EXECUTED
