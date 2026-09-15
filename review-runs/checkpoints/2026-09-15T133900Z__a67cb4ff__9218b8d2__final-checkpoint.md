# Independent correctness review — final checkpoint

- exact_implementation_sha: `a67cb4ff8a367f3a4261eb816d4abfe1290b8e4f`
- frozen_plan_sha: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen_review_start_sha: `34506615a286aaeea1dfdafb040dafabcaf410de`
- frozen_ledger_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- review_parent_sha: `9218b8d259a4123363dba9095de22c3608f02bda`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`, blob `7b553328dfcd9941d783658f49ecb16c71b98c56`
- checkpoint_kind: `FINAL`; only this checkpoint's effectiveness values are eligible for cumulative statistics.

## Independent verdict

`NOT_CLEAN — no material finding/status-count change.`

This is a fresh full-source review of the new implementation SHA, not a reused verdict. Required first-pass coverage is complete: L1-L6 all received BASELINE and L4 received DEEP. The implementation improves mixed pending+active occurrence admission, but it does not close the existing cleanup partial-effect/retry target-widening root. Exact-SHA execution remains NOT_VERIFIED.

Canonical count carried forward: `P0 2 / P1 0 / P2 22`.

## Findings / disposition

### Existing `BUG-CLEANUP-01` — OPEN, no material status change

- severity: existing canonical severity (no severity correction in this run)
- violated invariant: first externally committed destructive effect must have durable retry/recovery semantics that cannot widen the committed target set; ambiguous recovery must fail closed without reauthorizing a broader destructive replay.
- production path: `CleanUpLeftoverDownloads.doWork()` -> `CleanupScheduleCoordinator.withCurrentDestructiveEffect()` -> repository/cache destructive operations -> retry/re-entry.
- concrete evidence: the occurrence is persisted `IN_PROGRESS` before the body, but an arbitrary `Exception` from the body is caught and the same occurrence is persisted back to `ELIGIBLE`. The body can commit earlier destructive operations before a later operation throws. A retry then recomputes targets; there is no durable committed target snapshot constraining the replay.
- affected files: `app/src/main/java/com/ireum/ytdl/work/CleanupScheduleCoordinator.kt`, `app/src/main/java/com/ireum/ytdl/work/CleanUpLeftoverDownloads.kt`, with destructive consumers in `database/repository/DownloadRepository.kt` and cache cleanup paths.
- disposition: `OPEN`; the SHA's mixed-slot fail-closed change is useful but insufficient for this root.

### Existing stale Download-row/path authority root — OPEN/reachable, no material status change

- production path: `DownloadRepository.getUnexistingDownloads()` -> later `deleteKnownUserRemoval()`.
- evidence: stale-path eligibility is observed before the later database deletion and is not revalidated under one shared path-ownership authority at the destructive boundary.
- disposition: unchanged existing root; no new finding/subcase count.

### DOWNLOAD_TEMP live-owner race candidate — rejected as a new residual in this pass

Current `AppCacheManager.delete(DOWNLOAD_TEMP)` executes under `CacheMaintenanceAuthority.withExclusiveMaintenance`, and live DOWNLOAD_TEMP ownership publication uses the same authority. The previously serious candidate is not promoted to a new residual here.

### Foreground async candidate — supporting evidence only

`CleanUpLeftoverDownloads` calls `setForegroundAsync()` without awaiting the returned future. This is an asynchronous completion/failure window, but in this review it does not establish an independent canonical correctness root beyond the existing partial-effect/process-death recovery requirement. It is not counted as a new finding.

## Review retrospective

The changed line addressed one narrow mixed-carrier ambiguity, but full-path tracing showed why the broader cleanup root remains: effect admission, destructive body commits, exception handling, and retry authority have to be reviewed as one semantic transaction. Diff-only review would have been insufficient. No new P0/P1/P2 root and no material status correction was found.

Exact-SHA GitHub execution evidence: combined status contexts `0`; associated workflow runs `0`. Execution is `NOT_VERIFIED`.

## Checklist evolution

No new finding was created, therefore no new mandatory Checklist gap / Proposed checklist change is generated in this run. The retained BUG-CLEANUP-01 evidence is already within v6 durability/destructive-effect/retry closure requirements. No checklist item is weakened because a candidate was rejected.

## Lens coverage current SHA

`lens_coverage_current_sha: {L1: BASELINE, L2: BASELINE, L3: BASELINE, L4: DEEP, L5: BASELINE, L6: BASELINE}`

- primary_deep_lens: `L4 Destructive ownership`
- lens_selection_reason: first review of a new SHA whose only implementation change is directly in destructive cleanup occurrence admission; L4 is the most directly relevant lens. No previous-SHA coverage was inherited.

### L1 Durability & recovery
- coverage_level: `BASELINE`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[] (0)`; partial-effect replay is attributed primarily to L4 to avoid double counting
- rejected_candidates_with_proof: `0`
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: WorkManager retry semantics
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: cleanup debt carriers, effect phase, exception/retry/re-entry, successor publication

### L2 Identity & provenance
- coverage_level: `BASELINE`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[] (0)`
- rejected_candidates_with_proof: `0`
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: none required to close a candidate
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: generation/cadence/anchor/occurrence tuple equality; cleanup row/path identity

### L3 Concurrency & authority
- coverage_level: `BASELINE`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[] (0)`
- rejected_candidates_with_proof: `0`
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: none required to close a candidate
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: coordinator mutex/occurrence authority, stale observation versus later delete, cache maintenance authority

### L4 Destructive ownership
- coverage_level: `DEEP`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[BUG-CLEANUP-01: partial destructive commit followed by body Exception resets occurrence to ELIGIBLE and permits target-recomputed retry] (1)`
- rejected_candidates_with_proof: `1` (new DOWNLOAD_TEMP live-owner race residual rejected because delete and live ownership publication share CacheMaintenanceAuthority)
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: WorkManager retry semantics as supporting recovery basis
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: full cleanup worker destructive body; occurrence admission/effect recovery; stale-row deletion; DOWNLOAD_TEMP exclusive maintenance ownership

### L5 Platform contract closure
- coverage_level: `BASELINE`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[] (0)`
- rejected_candidates_with_proof: `0`
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: AndroidX WorkManager CoroutineWorker foreground API; ListenableWorker.Result.retry
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: CoroutineWorker foreground publication, retry/result surface, exact-SHA status/workflow evidence

### L6 Cross-feature semantic propagation
- coverage_level: `BASELINE`
- new_confirmed_findings: `[] (0)`
- existing_finding_status_changes: `[] (0)`
- confirmed_residuals_or_subcases: `[] (0)`
- rejected_candidates_with_proof: `0`
- not_verified_candidates: `0`
- checklist_gaps_triggered: `0`
- upstream_semantic_checks: none required
- cross_feature_propagation_hits: `0`
- lens_scope_reviewed: cleanup coordinator/worker/repository/cache helper family and equivalent ownership/error-collapse surfaces relevant to this remediation

Primary detecting lens for the confirmed residual: `L4`; supporting lenses: `[L1, L5]`. It is counted once only.

## Global lens effectiveness summary

Prior final checkpoints predate a complete uniformly structured effectiveness history. Current-SHA coverage is exact as above. Historical global per-lens totals beyond values explicitly recoverable from prior final checkpoints are `NOT_VERIFIED`; no estimates or finding-count ranking are introduced here. This final checkpoint becomes a measured raw-effectiveness datum for future aggregation.

## Exact upstream semantic basis used

- AndroidX WorkManager `CoroutineWorker`: suspending foreground API versus future-returning `setForegroundAsync()` completion surface.
- AndroidX WorkManager `ListenableWorker.Result.retry()`: another attempt does not itself provide an application-level durable destructive-target snapshot.

## Checkpoint summary

Bootstrap and intermediate checkpoints were appended on `review/remediation` and verified by direct branch-path lookup before this final checkpoint. Production/application source was never modified for checkpointing. Final checkpoint append and branch ancestry/existence verification must be performed immediately after this file is committed; any failure must be reported rather than assumed successful.
