# BUG-SCHEDULER-01 — independently reviewed accepted-terminal scheduler START/END closure on db339e1

date: 2026-10-12
record_kind: INDEPENDENT_IMPLEMENTATION_COMPLETION_REVIEW
record_status: FINAL
manual_review_run: NO
review_parent_sha: e29ee5f1a0a84430f7a973936a5fd3152d869a62
review_base_implementation_sha: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
reviewed_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
applicable_protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
applicable_checklist_v6_blob: 7b553328dfcd9941d783658f49ecb16c71b98c56
canonical_finding_classification: BUG-SCHEDULER-01 EXISTING NARROW ACCEPTED_TERMINAL SCHEDULER RECOVERY ROOT
finding_disposition: VERIFIED_CLOSED_ON_EXACT_PUBLISHED_SOURCE
source_semantic_status: SOURCE_FIXED
execution_status: AGENT_REPORTED_EXACT_SHA_152_PASS_0_FAIL_0_SKIP_NOT_REEXECUTED_BY_REVIEWER
repository_wide_verdict: NOT_CLEAN
clean_review_basis: NONE_REPOSITORY_WIDE_CLEAN_REVOKED
canonical_active_download_p2_count: 8_UNCHANGED
new_semantic_root_delta: 0
review_independent_runtime_execution: NOT_EXECUTED

## Publication and ancestry independently verified

The reported implementation SHA db339e1e90392cfd994e2d42bca7ce88afb3be21 is the actual remote HEAD of `checkpoint/pre-baseline-review`. Compare c34b8aa57e01803c9960e4ad873d1ed5b68e019c..db339e1e90392cfd994e2d42bca7ce88afb3be21 is exactly one compatible fast-forward commit (ahead 1, behind 0); five modified files and no unrelated source expansion. The production change is only six inserted lines in `app/src/main/java/com/ireum/ytdl/work/WorkManagerHandoffRecovery.kt` at `reconcileCarrier()`. Four affected Android instrumentation test files are changed: `WorkManagerHandoffProductionTest.kt`, `RealWorkManagerHandoffProductionTest.kt`, `F11SchedulerSettingsTransitionProductionWiringTest.kt`, and `F11HandoffCarrierMutationAdmissionProductionWiringTest.kt`. No DAO schema, migration, permission, Gradle/build setting, or other production file is changed by this commit. The full exact remote source and production producer/consumer/cas path were inspected; test counts are externally reported execution evidence, not reviewer-run tests.

## Independently verified production semantics and specific root closure

Prior c34b8aa57e01803c9960e4ad873d1ed5b68e019c `reconcileCarrier()` deleted an exact scheduler carrier in state `ACCEPTED` on an exact WorkInfo `FAILED` or `CANCELLED` through generic `dao.deleteAccepted(handoffId, requestId)`. This lost the only startup-owned retry record. At db339e1e90392cfd994e2d42bca7ce88afb3be21 a new branch handles only `SCHEDULE_START` / `SCHEDULE_END` with exact WorkInfo in those two terminal failure states, calling existing `retryAfterFailure(context, carrier, null)` and returning before that deletion. No sibling kind's interpretation is changed.

Source traversal independently inspected:
- producer: `prepareSchedulerBoundaryWithinOrdinaryMutation` and `prepareSchedulerBoundaryForRestore` allocate exact durable handoffId/generationId/requestId and boundary/unique-work before WorkManager publication; restored-window fallback preserves exact accepted generation;
- carrier: `WorkManagerHandoffCarrier` and `WorkManagerHandoffCarrierDao` retain `ACCEPTED` across process restart, with no newly introduced field or stricter persisted identity and thus no old/new schema-origin collision. `getOutstandingForBoundary` exposes outstanding current boundary; `markSuperseded`/delete and `advanceRetry` use exact handoff/request/state guards;
- recovery: `reconcile` examines outstanding carrier and exact WorkInfo, accepted failure flows through `retryAfterFailure`. Under `withCarrierMutation` and `RestoreMutationAdmission`, stale/noncurrent generation or current retained-boundary mismatch yields SUPERSEDED without advancing E1. `dao.advanceRetry` requires exact old requestId, writes new requestId/attempt and PENDING_ENQUEUE atomically. `scheduleRetry` re-observes that durable carrier, enqueues current exact request, and waits for `Operation.result`; WorkInfo missing and still-running continue to retain accepted carrier;
- consumer/final effects: `schedulerWorkRequestDisposition` checks handoffId, requestId, generation, boundary, kind, actual WorkRequest ID and current durable boundary before declaring CURRENT, so stale E1 cannot claim current E2. `DownloadWorker` START and `CancelScheduledDownloadWorker` END use this admission; END's exact stopping/requeue side effects precede `retireSchedulerWorkRequest`, whose `deleteAccepted` targets exact handoff+request. Cancellation, Restore reset, settings replace, and superseded tombstones retain prior barriers and do not gain new authority through the six-line change. Other handoff kinds remain in existing branches; success retirement is not redirected to retry.

These facts close **the accepted-terminal FAILED/CANCELLED START/END carrier-loss subroot** of BUG-SCHEDULER-01 without introducing a new counted root. An apparent failed/cancelled E1 can no longer remove the exact retry owner via this branch; and E1 cannot mutate E2 under retry CAS/retained-boundary checks. This finding disposition does **not** close independent `BUG-SCHEDULER-WINDOW-01`, `BUG-SCHEDULER-RESTORE-01`, owner-race residual or other canonical P2 blockers. No repository-wide CLEAN basis is advanced.

## Executed evidence as reported, not independently reproduced

The implementation agent reported one normal fast-forward push and exact remote equality, **152 PASS, 0 FAIL, 0 SKIP on committed db339e1e90392cfd994e2d42bca7ce88afb3be21** (57 JVM, 95 Android), including accepted-terminal FAILED/CANCELLED recovery, E1->E2 settings/Restore supersession, source-bound corrected Reset, seven window/END-owner tests, and full dynamically applicable exact-SHA gate. This source-level review does not claim to have run Android or Gradle or independently re-read local sealed test logs, and does not count intermediate 64/138, 98/138 or the earlier installed Reset execution as final-SHA evidence.

Agent reports exact-alarm special access restored to original `default` with final read-back; both APKs, protected five worktrees, four unknown-origin cache files, index staging, prior evidence preserved. Evidence integrity: 644 new and 232 historical = 876 files verified, zero mismatches as reported. The local full report remains at `C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-v3-alarm-window-20261011-86f87dee/BUG_SCHEDULER_01_V3_PUBLICATION_AND_EXACT_SHA_VERIFICATION_REPORT.md`; this path is provenance, not a reviewer-accessible GitHub artifact. A pre-push wrapper-path invocation error was separately preserved and corrected within the authorized envelope before the only actual push, per report. Historic Windows WinError 5 causation remains NOT_VERIFIED.

## Scope and checklist decision

Applicable reviewer obligations: L1 durability/recovery (persisted accepted failure and startup retry), L2 request provenance (exact handoff/request/generation), L3 concurrency/authority (Restored E2 and ordinary competing admission), L4 destructive ownership (no old E1 delete/worker effects on E2), L5 platform contract (real WorkManager terminal info and exact-alarm capability), L6 cross-feature propagation (scheduler, Restore, settings, worker and queue consumers). Triggered Module B WorkManager exact accepted/result/retry semantics and Module F persisted-generation compatibility: no new fields/stricter predicates, old accepted rows continue to be queried as-is and retried. `WorkManagerHandoffProductionTest` includes four FAILED/CANCELLED matrix cases plus stale generation, successful retirement and unfinished requests; `RealWorkManagerHandoffProductionTest` covers actual WorkManager outcomes; `F11SchedulerSettingsTransitionProductionWiringTest` tests Restore takeover of old failed/cancelled carriers; Reset harness hook is restricted to exact writer thread, with extra diagnostics only for that test.

No confirmed additional root or same-root residual emerged from independent production source inspection. Historical toolchain fault attribution, other open P2s and exact sealed-log content remain outside this narrow closure. The source semantic verdict is independent; execution evidence remains implementation-reported.

## Reviewer next step

Update private handoff to reflect actual published/independently reviewed db339e1e90392cfd994e2d42bca7ce88afb3be21, supersede already executed V3 prompt routing, record BUG-SCHEDULER-01 narrow finding CLOSED, keep all other canonical 8 P2 root counts and all protected historical evidence unchanged, and route next action to reviewer-owned review of `BUG-SCHEDULER-WINDOW-01` / next eligible active scheduler correctness target under pinned governance. No immediate new implementation prompt is authorized by this checkpoint; determine next safe correction from fresh remote review evidence. No ledger or Master Plan mutation.

review_retrospective: Previously missing local runtime progress was reconciled before V3. Repeated historical environment stops obscured real source quality; a bounded app-UID exact-alarm precondition restored the missing focused test without weakening production. The final SHA is independently inspectable, unlike the five-file local dirty draft.
checklist_evolution: No change to pinned Review Checklist v6. Retain explicit Module B accepted WorkInfo FAILED/CANCELLED matrix and generation-bound retry-CAS with real WorkManager, Reset and Restore consumer evidence in future waves.
