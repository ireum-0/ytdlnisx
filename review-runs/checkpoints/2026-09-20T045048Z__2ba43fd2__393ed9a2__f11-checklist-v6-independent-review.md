# F11 / BUG-BACKUP-03 — Checklist v6 independent review

## Review identity

- review kind: FINAL INDEPENDENT CORRECTNESS REVIEW
- exact implementation branch: `checkpoint/pre-baseline-review`
- frozen implementation SHA: `2ba43fd272967725b946b1a8fca4e6314107b03b`
- exact implementation base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`
- exact implementation range: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff..2ba43fd272967725b946b1a8fca4e6314107b03b`
- implementation range shape: 10 commits ahead / 0 behind; exact base is merge base
- review_parent_sha: `393ed9a2b0ade7ead283637f2a932a9a8456f99a`
- implementation files changed: 66 tracked files (59 production, 7 AndroidTest)
- review execution framework: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` at `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- governing F11 design checkpoint: `393ed9a2b0ade7ead283637f2a932a9a8456f99a`
- governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`

## Independent verdict

`NOT_CLEAN`

F11 / `BUG-BACKUP-03` remains the existing P0 root. This review confirms four current-change residual correctness blockers within that existing root. No new canonical root ID and no canonical inventory count delta are proposed.

The implementation's file-backed restore journal, immutable plan, one restore-wide Room transaction, deterministic thumbnail publication, explicit SharedPreferences commit handling, malformed-carrier fail-closed behavior, and overlapping Reset ownership are materially stronger than the pre-F11 implementation. They do not close the P0 because scheduler/remainder responsibility, mutation admission, a transitive alarm handoff, and reconciliation replay still violate the selected F11 contract.

## Governance / stale-handoff reconciliation

The private dynamic handoff read at review start still described F11 as `READY_FOR_F11_LUNA_IMPLEMENTATION` at base `3072ce86...`. That state is stale relative to the actual implementation branch. Exact GitHub source wins for current implementation state. The implementation branch was independently verified at `2ba43fd2...` before and during review.

The 27 previously discriminated red tests remain inherited baseline failures, not PASS results and not F11 findings. This review did not reopen them.

## Checklist v6 mandatory-order summary

1. Scope/invariant: destructive Reset must be one recoverable operation across Room, preferences, filesystem, workers, notifications.
2. First authoritative observation: immutable validated RestorePlan is durable before active pointer publication.
3. Carrier creation gap: active pointer is written only after plan/journal/payload staging; malformed active carrier fails closed.
4. Post-carrier/pre-handler frontier: active pointer gates conflicting actors; however ordinary mutation admission is not uniformly serialized with the final mutation boundary.
5. Helper-internal throwable window: phase writes and Room/prefs/file failure paths are generally retained as recovery debt.
6. Semantic preservation/proof/identity: destination identities are rebuilt rather than trusting backup IDs.
7. Persistence/first-write: plan/journal and thumbnail staging use checked file publication; preferences use checked commit + compensation.
8. Async request/acceptance/completion: WorkManager Operation.result is awaited in several reconciliation paths, but preserved responsibilities and the exact-alarm transitive path remain incomplete.
9. Recovery identity/live authority: active restore operation is exact; ordinary mutator authority is not uniformly fenced against owner publication.
10. Discovery closure/carrier loss: broad cancellation can remove WorkManager responsibility for destination state that Reset intentionally preserves.
11. Multi-ledger/process death: Room/journal ambiguity is addressed; external scheduling replay is not fully idempotent.
12. Post-commit sidecar: several sidecars are journaled/reconciled, but preserved scheduling/low-quality/managed-observe responsibilities are incomplete.
13. Outer catch/final result: committed reconciliation failure is represented as committed-pending.
14. Filesystem/reference cleanup authority: deterministic restored thumbnail publication is source-level sound for new payloads; no new F11 blocker confirmed here.
15. Retry/reconfigure/resume/startup/restore: startup waits for Restore recovery for most conflicting reconcilers; scheduler replay defects remain.
16. Cross-attempt/live-owner: repeated restore recovery keeps one operation, but repeated immediate Download reconciliation can accumulate UUID-distinct WorkRequests.
17. Negative invalidation: candidate false positives were rejected with production proof below.
18. Concurrency/sibling/lock order: History has a shared restore/mutation mutex; other conflicting writers do not have an equivalent complete authority boundary.
19. Semantic-contract delta: `allowDuringRestore` is not propagated through AlarmScheduler -> WorkManagerHandoffRecovery.
20. Consumer/effect closure: transitive scheduler, preference writers, low-quality manager, and managed ObserveSource effects expose residuals.
21. Conditional modules: A/B/C/E/H/I assessed below.
22. Candidate rejection re-proof: completed below.
23. Tests/production wiring: existing 15-test F11 suite is real production wiring but does not cover every required durable phase/external-acceptance crash boundary.
24. Terminal fault matrix: incomplete, detailed below.
25. CLEAN gate: fails due open P0 residuals and required verification gaps.
26. Attribution: all confirmed blockers are residuals of existing `BUG-BACKUP-03`, not new canonical roots.

## Findings

### F11-R1 — P0 residual / HIGH — broad quiescence destroys preserved operational responsibility

Conflicting work may be quiesced for destructive Reset, but state/categories intentionally preserved by the RestorePlan must retain or reconstruct their durable execution responsibility before COMPLETE. F11 design also prohibits broadly cancelling unrelated work merely for convenience.

`RestoreTransactionCoordinator.quiesce()` derives `hasDownloadReset` from History or any Download category. At `RestoreTransactionCoordinator.kt:580+`, a paused-only or History-only Reset therefore cancels the broad Download tag set, including `DownloadWorker`, `download`, scheduled-cancel, updateFormats, updateData, cache work, and low-quality work.

The Room apply correctly preserves absent Download categories. But `reconcilePostCommit()` at `RestoreTransactionCoordinator.kt:1402+` reconstructs Download scheduler responsibility only when the incoming plan itself contains `queued` or `scheduled`:

`val hasRunnableDownloadReset = data.queued != null || data.scheduled != null`

Concrete counterexample:

1. destination has a runnable Queued row and its DownloadWorker responsibility;
2. Reset contains only `paused`;
3. quiescence cancels the Download worker;
4. Room clears/restores only Paused and preserves the Queued row;
5. post-commit reconciliation sees no imported queued/scheduled category and does not call `startDownloadWorker`;
6. Reset can reach COMPLETE with a runnable row but no worker responsibility.

Existing `BackupPausedProductionWiringTest.resetPausedCategoryDoesNotDeleteQueuedRows` proves row preservation only; it does not prove scheduler responsibility survives.

Additional same-root subcases:
- unrelated low-quality operations can survive Room targeting while the global `low_quality_redownload` work is cancelled; post-commit code only refreshes captured targeted operation IDs, not the surviving manager's execution responsibility;
- History/Download Reset cancels `observeSources`; automatic-keyword coverage only enqueues a managed discovery source when missing or STOPPED. An existing ACTIVE managed source whose work was cancelled is not rescheduled and can remain ACTIVE-but-unscheduled.

Consequence: Reset can preserve durable domain rows while silently losing the asynchronous owner that makes those rows progress.

Required remediation: separate “must quiesce” from “category was imported”; capture/reconcile every durable responsibility whose work is cancelled and whose authoritative state survives. Do not delete preserved rows or broaden category semantics.

Primary detecting lens: L1. Supporting: L3, L6, Module B, Module I.

### F11-R2 — P0 residual / HIGH — RestoreGate is a point-in-time observation, not a complete mutation authority boundary

Discovery is not mutation authority. An ordinary conflicting writer that wins before Reset must finish before Reset becomes authoritative; if Reset wins first, the ordinary writer must be rejected/deferred before mutation.

History demonstrates the required shape:
- `HistoryReferenceMutationCoordinator.withLock` takes a mutex, checks RestoreGate inside it, and performs the ordinary mutation while holding it.
- restore apply uses the same mutex through `withRestoreLock`.

Other F11 surfaces generally do not have an equivalent boundary:
- `DownloadRepository.ensureRestoreAdmission()` checks before later DAO/Room writes;
- `ObserveSourcesRepository.checkRestoreAdmission()` checks before later DAO operations;
- `AutomaticKeywordRuleRepository.save()` checks before normalization/reads and only later starts `db.withTransaction`;
- `PlaylistRepository` follows the same point-in-time pattern;
- `LowQualityRedownloadManager.setSelected()` checks the gate and then launches an asynchronous mutation later (`LowQualityRedownloadManager.kt:53-55`);
- preference consumers are incomplete: `ProcessingSettingsFragment` writes portable `subs_lang` and `audio_bitrate` through `apply()` without a RestoreGate boundary, while settings Reset can clear/import those portable preferences.

Reachable race:

ordinary writer observes ALLOWED -> suspends/queues async body -> Reset publishes active ownership and commits authoritative state -> admitted ordinary body resumes -> ordinary mutation commits into the restored graph/preferences.

An extra uncoordinated boolean check only moves the race.

Required remediation: coherent shared admission/mutation serialization or equivalent final mutation-point revalidation serialized with restore ownership, across the complete affected consumer set. Preserve lock order and do not hold a global lock across native/network work.

Primary detecting lens: L3. Supporting: L1, L4, L6.

### F11-R3 — P0 residual / HIGH — alarm-based scheduled Download reconciliation self-blocks on the active RestoreGate

A committed Reset in RECONCILING must be able to rebuild scheduler authority and reach COMPLETE. Semantic exceptions such as `allowDuringRestore` must propagate through the complete consumer/effect graph.

Production path:
1. `reconcilePostCommit()` calls `DownloadRepository.startDownloadWorker(... allowDuringRestore = true, awaitAcceptance = true)`.
2. outer DownloadRepository gate is bypassed.
3. with `use_alarm_for_scheduling=true` and future scheduled work, `DownloadRepository.kt:4450` calls `AlarmScheduler.scheduleAt(...)`.
4. AlarmScheduler calls `WorkManagerHandoffRecovery.prepareSchedulerBoundary(...)`.
5. that function unconditionally checks `!RestoreGate.isRestoreInProgress(context)`.
6. RECONCILING necessarily still owns the active Restore pointer, so the check throws.
7. recovery re-enters the same path with the same active gate and fails again.

The failure happens before the durable scheduler handoff carrier is prepared.

Consequence: a valid Reset with future scheduled work can remain permanently in RECONCILING under alarm-based scheduling.

Required remediation: propagate narrowly scoped restore-reconciliation authority through AlarmScheduler/WorkManagerHandoffRecovery or use an equivalent deterministic restore-owned handoff. Do not globally weaken RestoreGate.

Primary detecting lens: L5. Supporting: L1, L6, Module A, Module B.

### F11-R4 — P0 residual / MEDIUM — immediate Download reconciliation is not process-death idempotent

Post-commit scheduler reconciliation must be retryable and idempotent.

`reconcilePostCommit()` can call `startDownloadWorker()` while RECONCILING. The immediate branch names unique work as `"$DOWNLOAD_WORK_NAME-${request.id}"` at `DownloadRepository.kt:4475`; `request.id` is a fresh WorkRequest UUID.

Thus:

RECONCILING -> enqueue accepted -> process death before COMPLETE -> same restore operation recovers -> reconcile again -> second UUID-distinct work name is enqueued.

WorkManager unique-work collision semantics are keyed by the unique name; a fresh name does not collide with the prior request. Per-Download claim/CAS reduces duplicate destructive effects but does not make scheduler publication itself converge.

Required remediation: give F11 reconciliation a stable restore-operation scheduling identity or equivalent idempotent publication protocol without changing normal independent Download trigger semantics.

Primary detecting lens: L1. Supporting: L5, Module B.

## Required verification gap

Even without the source blockers, the current F11 suite is insufficient for CLEAN under the pinned Master Plan/design checkpoint.

The 15-method `BackupResetTransactionProductionWiringTest` covers malformed/pre-publication, PREPARED, FILES_READY, Room rollback, ambiguous Room commit, deterministic thumbnail replay, preference commit failure, pre-side-effect category reconciliation failures, and generic RECONCILING debt.

It lacks deterministic proof for at least:
- restart from durable QUIESCED;
- restart from exact DATA_COMMITTED;
- crash after COMPLETE is durable but before active pointer/op-directory retirement;
- external scheduler side effect accepted, crash before COMPLETE, then replay;
- preserved sibling/remainder responsibility after broad quiescence.

Implementation-agent PASS counts are evidence but not independent reviewer execution.

## Terminal fault matrix

| Boundary | Result |
|---|---|
| before active owner publication | PASS source-level |
| PREPARED restart | PASS source-level |
| QUIESCED restart | GAP |
| FILES_READY restart | PASS source-level |
| Room failure before commit | PASS source-level |
| Room commit before journal advance | PASS source-level |
| DATA_COMMITTED exact restart | GAP |
| RECONCILING failure before external completion | PASS source-level |
| external enqueue accepted -> crash -> replay | FAIL |
| COMPLETE durable -> crash before retire | GAP |
| malformed active carrier | PASS source-level |

## Cross-attempt / live-owner matrix

| Scenario | Result |
|---|---|
| same restore operation replay uses durable plan/journal | PASS source-level |
| second Reset cannot replace active owner | PASS source-level |
| worker already active when Reset starts | representative quiescence coverage exists |
| worker starts while active gate already exists | representative gate coverage exists |
| ordinary writer admitted before owner publication, mutates after apply | FAIL F11-R2 |
| preserved queued responsibility after paused/history-only Reset | FAIL F11-R1 |
| alarm-scheduled reconciliation under active gate | FAIL F11-R3 |
| accepted immediate enqueue then process death/replay | FAIL F11-R4 |
| stale/complete restore cleanup mutates newer owner | no counterexample confirmed |

## Conditional modules

- Module A platform capability/permission truth: TRIGGERED. FAIL under F11-R3 transitive exact-alarm handoff; no separate permission defect confirmed.
- Module B external scheduler handoff: TRIGGERED. FAIL under F11-R1/R3/R4.
- Module C external representation/schema: TRIGGERED. Parser/marker/version/playlist-shape source review found no new blocker. Historical compatibility execution remains implementation-agent evidence.
- Module D packaged resources/ABI: NOT_TRIGGERED materially.
- Module E referenced artifact publication: TRIGGERED for thumbnails. No new current-change blocker confirmed.
- Module F persisted schema generation: NOT_TRIGGERED; no Room version/schema change.
- Module G reusable external ID: no distinct trigger beyond restore remapping already reviewed.
- Module H persisted executable config fan-out: TRIGGERED by settings/templates, but no distinct current-change semantic-contract blocker confirmed.
- Module I maintenance vs live-owner: TRIGGERED. FAIL under F11-R1.

## Candidate rejection re-proof

1. Automatic-keyword rule deletion during History Reset: equivalent linked History/rule Reset semantics existed pre-F11; not established as a new absent-category regression.
2. TerminalExecutionRecovery/TerminalPublicationRecovery outside `restoreRecovery.await()`: they act on terminal-specific table/cache authority; no overlap with the F11 destructive graph established.
3. Youtuber visibility-only group clearing: equivalent reset behavior existed pre-F11.
4. old custom-thumbnail orphan cleanup: pre-F11 History Reset also deleted rows without demonstrated file retirement; not reclassified into F11 without stronger current-change evidence.
5. 27 frozen failures: exact baseline/candidate discriminator already established method/signature equivalence; not reopened.

## Baseline-exception assessment

The narrow 27-method inherited-failure exception remains sound for F11 non-regression purposes on this SHA. It remains exact-method/signature scoped and is not PASS.

## F10 preservation assessment

No F10 production scheduling redesign was found in the F11 range. `2ba43fd2` is test-only and accepts the same cleanup occurrence in pending or active replay slots. No F10 cadence/retry/successor widening confirmed.

## Lens coverage

`lens_coverage_current_sha: {L1: DEEP, L2: BASELINE, L3: BASELINE, L4: BASELINE, L5: BASELINE, L6: BASELINE}`

- primary_deep_lens: `L1 Durability & recovery`
- lens_selection_reason: F11 is a destructive restore/process-death root; blockers are dominated by responsibility preservation and replay/reconciliation semantics.

### L1 Durability & recovery
- coverage_level: DEEP
- new_confirmed_findings: 0 canonical roots
- existing_finding_status_changes: `BUG-BACKUP-03` remains NOT_CLEAN after implementation
- confirmed_residuals_or_subcases: F11-R1, F11-R4
- rejected_candidates_with_proof: 1
- not_verified_candidates: 0 material
- checklist_gaps_triggered: 0
- upstream_semantic_checks: WorkManager Operation/unique-work replay; SharedPreferences commit/apply
- cross_feature_propagation_hits: Download, low-quality, managed-observe responsibility
- lens_scope_reviewed: journal phases, carriers, Room/prefs/files, reconciliation, startup, scheduler replay

### L2 Identity & provenance
- coverage_level: BASELINE
- new_confirmed_findings: 0
- confirmed_residuals_or_subcases: 0
- rejected_candidates_with_proof: 1
- not_verified_candidates: 0 material
- checklist_gaps_triggered: 0
- lens_scope_reviewed: RestorePlan detachment, plan digest, remaps, thumbnail identity, operation identity

### L3 Concurrency & authority
- coverage_level: BASELINE
- new_confirmed_findings: 0 canonical roots
- confirmed_residuals_or_subcases: F11-R2
- rejected_candidates_with_proof: 1
- not_verified_candidates: 0 material
- checklist_gaps_triggered: 0
- lens_scope_reviewed: RestoreGate, ordinary writers, HistoryReferenceMutationCoordinator, worker quiescence, owner publication

### L4 Destructive ownership
- coverage_level: BASELINE
- new_confirmed_findings: 0
- confirmed_residuals_or_subcases: F11-R1 support
- rejected_candidates_with_proof: 1
- not_verified_candidates: 0 material
- checklist_gaps_triggered: 0
- lens_scope_reviewed: Room rebuild, files, sidecars, broad cancellation, cleanup authority

### L5 Platform contract closure
- coverage_level: BASELINE
- new_confirmed_findings: 0 canonical roots
- confirmed_residuals_or_subcases: F11-R3; F11-R4 support
- rejected_candidates_with_proof: 0
- not_verified_candidates: 0 material
- checklist_gaps_triggered: 0
- upstream_semantic_checks: Android WorkManager unique work/REPLACE/Operation completion, tag cancellation, AlarmManager handoff
- lens_scope_reviewed: WorkManager, exact alarm, handoff carriers

### L6 Cross-feature semantic propagation
- coverage_level: BASELINE
- new_confirmed_findings: 0
- confirmed_residuals_or_subcases: transitive `allowDuringRestore` failure; incomplete gate consumer closure
- rejected_candidates_with_proof: 0
- not_verified_candidates: 0 material
- checklist_gaps_triggered: 0
- cross_feature_propagation_hits: Download -> AlarmScheduler -> WorkManagerHandoffRecovery; RestoreGate across DB/prefs/manager consumers
- lens_scope_reviewed: repositories, UI/viewmodels, workers, scheduler/handoff layers

Global lens effectiveness summary: NOT_VERIFIED; historical final-checkpoint corpus was not exhaustively recomputed.

## Review retrospective / checklist evolution

The earlier ad-hoc review missed material scope because it did not execute the pinned v6 checklist in mandatory order, focused on the coordinator before transitive consumer/effect closure, did not systematically inspect sibling/remainder responsibility after broad quiescence, and did not trace `allowDuringRestore` through AlarmScheduler to the handoff carrier.

Checklist gap: none confirmed. v6 already contains the required async-completion, consumer-closure, sibling/remainder, authority/concurrency, and scheduler-handoff checks.

Proposed checklist change: none required. This was an execution-discipline gap, not a missing checklist rule.

## Required next action

Do not mark F11 CLOSED and do not start F12.

Run a narrow F11 review-remediation wave from exact SHA `2ba43fd272967725b946b1a8fca4e6314107b03b` that:
1. preserves/reconstructs every durable responsibility cancelled by Reset when its authority survives;
2. closes ordinary-mutation vs Restore ownership races across the complete consumer set;
3. fixes alarm-scheduler transitive RestoreGate self-block;
4. makes Download reconciliation idempotent after accepted enqueue + process death;
5. fills the durable-phase / accepted-side-effect restart test matrix;
6. re-runs affected focused, inherited-baseline discriminator, broader non-regression, and F10 cleanup gates.

If remediation requires Room schema migration or broad ownership redesign outside F11, STOP for independent planning.

## Preservation

Review-only checkpoint. No production/application source modified.

INDEPENDENT EXECUTION: NOT EXECUTED
