# Independent correctness review — final checkpoint

- exact_implementation_sha: `a67cb4ff8a367f3a4261eb816d4abfe1290b8e4f`
- frozen_plan_sha: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen_review_start_sha: `04fc7190998e145b7a1f4d983047d4c4439dc676`
- frozen_ledger_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- review_parent_sha: `3012fca4ba569e03f762c6625548670653a6a92a`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`, blob `7b553328dfcd9941d783658f49ecb16c71b98c56`
- checkpoint_kind: `FINAL`; only this checkpoint's effectiveness values are eligible for cumulative statistics.

## Independent verdict

`NOT_CLEAN — no material root/status-count change.`

This run independently re-executed the v6 review against the same frozen implementation SHA rather than reusing the preceding verdict. The Master Plan F10 invariant remains unmet because the durable cleanup effect carrier cannot distinguish an unstarted D1, a partially committed D1, and a fully completed D1 whose final phase write was lost. Canonical counts remain `P0 2 / P1 0 / P2 22`. Exact-SHA execution evidence remains `NOT_VERIFIED` (0 commit status contexts, 0 associated workflow runs).

## Findings / disposition

### Existing `BUG-CLEANUP-01` / F10 — OPEN P2

- exact SHA: `a67cb4ff8a367f3a4261eb816d4abfe1290b8e4f`
- severity: `P2` (existing canonical severity)
- violated invariant: Master Plan F10 requires exactly one logical cleanup schedule preserving calendar cadence; under v6 durability/recovery closure, an occurrence's destructive responsibility must remain exact and recoverable across retry/restart/process death rather than being skipped or widened.
- production path: `CleanUpLeftoverDownloads.doWork()` -> `CleanupScheduleCoordinator.withCurrentDestructiveEffect()` -> `DownloadRepository.deleteCancelled()` / `LowQualityRedownloadLedger.refresh()` -> `DownloadRepository.deleteErrored()` / refresh -> active-count check -> `AppCacheManager.delete(DOWNLOAD_TEMP)` -> `scheduleSuccessor()` / retry/reconcile.
- concrete evidence A (confirmed existing residual): the coordinator commits `IN_PROGRESS` before invoking the body. Re-entry maps both `IN_PROGRESS` and `UNKNOWN` to `AlreadyConsumed(recoveryRequired=true)`. The worker treats that as consumed and proceeds to successor publication. Therefore process death immediately after the `IN_PROGRESS` commit but before `deleteCancelled()` can cause D1 to execute zero destructive subeffects and still advance to D2; death after a prefix of subeffects can abandon the unfinished suffix.
- concrete evidence B (confirmed existing residual): a normal `Exception` from the body is caught by `withCurrentDestructiveEffect()` and, if the phase write succeeds, resets the same occurrence to `ELIGIBLE`. Earlier subeffects may already have committed. WorkManager retry then re-enters and recomputes current cleanup targets; no frozen target/progress carrier constrains the second attempt to the original D1 responsibility.
- test evidence: `restartWithInProgressEffectPhaseSkipsBodyAndRecoversExactSuccessor()` explicitly seeds `in_progress`, expects zero cleanup runs, and waits for the exact successor; this demonstrates the unsafe ambiguity rather than closing it.
- affected files: `app/src/main/java/com/ireum/ytdl/work/CleanupScheduleCoordinator.kt`, `app/src/main/java/com/ireum/ytdl/work/CleanUpLeftoverDownloads.kt`, destructive repository/cache consumers and F10 production-wiring tests.
- disposition: `OPEN`; no root-count or severity change.
- primary_detecting_lens for this run's confirmed additional residual: `L1`; supporting_lenses: `[L4, L5]`.

### Existing stale Download-row/path authority root — OPEN/reachable, unchanged

Rechecked as part of L2/L3/L4/L6 baseline. No material status correction or new distinct subcase is asserted in this run.

### Foreground asynchronous publication candidate — not promoted

`CleanUpLeftoverDownloads` calls `setForegroundAsync()` without awaiting the returned future. Official Android guidance for Kotlin `CoroutineWorker` provides suspending `setForeground()`, while `setForegroundAsync()` exposes a `ListenableFuture`. This is a real asynchronous completion/failure surface, but the current evidence does not establish a separate canonical P0/P1/P2 root distinct from the existing cleanup recovery/partial-effect root. It remains supporting evidence only, not a new finding.

## Review retrospective

L1 DEEP was useful despite producing no new canonical root. It independently confirmed the already-recorded `IN_PROGRESS` ambiguity from a recovery-carrier perspective and paired it with the opposite unsafe direction: resetting a partially committed occurrence to `ELIGIBLE` permits target-recomputed retry. Together these show that the coarse three-state phase cannot safely decide replay-vs-skip after process death or partial failure.

The full v6 pass also rechecked identity tuple granularity, generation/cadence supersession, destructive locking, successor publication, WorkManager unique-chain semantics, SharedPreferences durability signaling, cache ownership, and cross-consumer propagation. No additional material status change was established.

## Checklist evolution

No new canonical finding was first discovered in this run, so no new mandatory `Checklist gap` / `Proposed checklist change` is generated. The confirmed residuals are already directly covered by v6 first-write durability, recovery semantic identity, carrier-loss/process-death, post-commit, retry/re-entry, destructive mutation authority, and consumer/effect-closure rules. The checklist is not weakened because a candidate was not promoted.

## Exact upstream semantic basis

- Android `SharedPreferences.Editor.commit()`: atomically applies the editor changes and returns true when values were successfully written to persistent storage; it is synchronous. This supports treating the phase writes as explicit Boolean persistence boundaries, while also requiring the false path to remain semantically safe.
- AndroidX WorkManager `enqueueUniqueWork(...)`: returns an `Operation` that can determine when enqueue has completed; request return alone is not completion.
- AndroidX `ExistingWorkPolicy.APPEND_OR_REPLACE`: appends to unfinished leaves; failed/cancelled prerequisites are dropped and the new work starts a new sequence. This is relevant to successor-chain recovery and failure inheritance.
- AndroidX WorkManager cancellation is best-effort; executing work may continue, so generation/occurrence fencing remains necessary independent of cancellation requests.
- Android long-running worker guidance: Kotlin `CoroutineWorker` can use suspending `setForeground()`; `setForegroundAsync()` returns a `ListenableFuture`, so an unobserved call is not a completion barrier.

## Lens coverage current SHA

`lens_coverage_current_sha: {L1: DEEP, L2: BASELINE, L3: BASELINE, L4: DEEP, L5: BASELINE, L6: BASELINE}`

- primary_deep_lens: `L1 Durability & recovery`
- lens_selection_reason: repeated review of the same SHA; the preceding final checkpoint had L4 DEEP, while L1 had not been DEEP for this SHA and is the lens most directly related to the open F10 process-death/retry/recovery-carrier blocker.

### L1 Durability & recovery
- coverage_level: `DEEP`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[BUG-CLEANUP-01: IN_PROGRESS cannot distinguish pre-effect death, partial-effect death, and post-effect/pre-CONSUMED death; restart can skip unstarted/unfinished D1] (1)`
- rejected_candidates_with_proof: `0`
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: `SharedPreferences.Editor.commit`, WorkManager retry/re-entry, enqueue Operation completion
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: phase first-write, exception reset, cancellation/process death, retry, replay owner, reconcile, successor handoff, worker terminal result.

### L2 Identity & provenance
- coverage_level: `BASELINE`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[] (0)`
- rejected_candidates_with_proof: `0`
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: none needed for a material decision
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: generation/cadence/monthly-anchor/occurrence identity, pending/active slot identity, occurrence tags, stale row/path authority.

### L3 Concurrency & authority
- coverage_level: `BASELINE`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[] (0)`
- rejected_candidates_with_proof: `0`
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: WorkManager cancellation best-effort semantics
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: destructiveEffectMutex, synchronized transitions, stale-generation fencing, mixed pending+active fail-closed, async cancellation ordering.

### L4 Destructive ownership
- coverage_level: `DEEP` (carried from an earlier final checkpoint for this same SHA; baseline re-executed this run)
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[] (0)` in this run; L1 owns the confirmed residual count to avoid duplication
- rejected_candidates_with_proof: `0`
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: none additional
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: deleteCancelled/deleteErrored, low-quality ledger refresh, active-download guard, DOWNLOAD_TEMP deletion authority, partial destructive prefixes.

### L5 Platform contract closure
- coverage_level: `BASELINE`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[] (0)`
- rejected_candidates_with_proof: `1` (unawaited foreground publication not promoted to an independent canonical root on present evidence)
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: `SharedPreferences.Editor.commit`, WorkManager `Operation`, `enqueueUniqueWork`, `ExistingWorkPolicy.APPEND_OR_REPLACE`, cancellation best-effort, CoroutineWorker foreground APIs
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: exact platform completion/durability semantics at cleanup authority, enqueue/cancel, and foreground boundaries.

### L6 Cross-feature semantic propagation
- coverage_level: `BASELINE`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[] (0)`
- rejected_candidates_with_proof: `0`
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: none additional
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: coordinator -> worker -> repository/ledger/cache -> reconcile/replay/successor consumer graph and equivalent ownership/error-collapse surfaces relevant to F10.

## Global lens effectiveness summary

Historical final checkpoints before the structured effectiveness format are incomplete, so full global totals remain `NOT_VERIFIED` and are not estimated. For this SHA, the directly verified measured progression is: preceding final checkpoint `L4 DEEP` with one confirmed existing-root residual and one proof-rejected serious candidate; this final checkpoint adds `L1 DEEP` with one distinct confirmed existing-root residual. No ranking or lens removal is inferred from finding counts.

## Checkpoint summary

Intermediate checkpoint was appended and verified on `review/remediation` before this final checkpoint. Production/application source was not modified. After this final checkpoint commit, branch HEAD, file existence, and ancestry must be rechecked; failure must be reported rather than assumed successful.
