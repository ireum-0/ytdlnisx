# Independent FINAL — WP-1 instance 02 queue-incognito snapshot scope STOP

checkpoint_kind: INDEPENDENT_COMPLETION_STOP_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: dda38ad4cecf07fbce39990f89c7fbe537848c70
reviewed_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
reviewed_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
pinned_master_plan_sha: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_sha: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
pinned_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
task_instance_id: WP1_INCOGNITO_POLICY_A_20261021_141B5199_02
task_prompt_blob: 81568b6d4caef8172447b1c6080336e8592eeaa5
task_prompt_origin_commit: 3a243539349d51cdd3f2f56e6c170ee8aea913b0
verified_task_intent_commit: 789f57e61db75cb88d8bf2c85de2ca03719d01ab
verified_task_intent_blob: aa5623ce4c5467c9bfea574634c191dc9577b4a9
verified_task_claim_commit: 7a9709a04b23c5a1d92f43b51eb0a096ec7a1ace
verified_task_claim_blob: c90674d915db2ff29aa206764edde2ec60a0e8e1
private_stop_report_commit: 41da1e95c10abc10c4c6cf8246d2fc2fb81f5fa8
private_stop_report_blob: 7c6a646a79b63e68ea2d58f2cc73ea86da60071c
private_stop_report_path: ytdlnisx/reports/2026-10-21__WP1_INCOGNITO_POLICY_A__wp1-02-queue-contract-stop-20261021-e3070e89__FULL_REPORT.md
independent_verdict: STOP_JUSTIFIED_EXISTING_INCOGNITO_ROOT_RESIDUAL_SOURCE_PATH
canonical_root: BUG-INCOGNITO-01
new_semantic_root_count: 0
root_status_before: OPEN
root_status_after: OPEN
canonical_active_p2_open: 8
canonical_active_p2_ambiguous: 0
canonical_clean: NO
remote_production_changed: NO
reviewer_performed_tests: NONE
agent_executed_tests: ZERO_EXECUTED_REPORTED
implementation_candidate_committed_or_pushed: NO
local_unfinished_draft: AGENT_REPORTED_PRESERVED_NOT_INSPECTED_OR_ADOPTED
implementation_prompt_or_handoff_changed: NO
next_implementation_task_authorized_by_this_review: NO

## Independent verdict and immutable identity

The linked [private Full Report](https://github.com/ireum-0/private/blob/41da1e95c10abc10c4c6cf8246d2fc2fb81f5fa8/ytdlnisx/reports/2026-10-21__WP1_INCOGNITO_POLICY_A__wp1-02-queue-contract-stop-20261021-e3070e89__FULL_REPORT.md) identifies `TASK_INSTANCE_ID=WP1_INCOGNITO_POLICY_A_20261021_141B5199_02`, matches the previous persisted prompt (blob `81568b6d4caef8172447b1c6080336e8592eeaa5`), and records an unambiguous `OUTCOME=STOPPED` before build/test/commit/push. GitHub independently contains its deterministic new intent and claim with matching claimant/token and ordered first-add normal forward commits `789f57e`, `7a9709a`; the Full Report commit was next. The bounded private compare from the pre-intent head `9599dc18` to report head `41da1e95c10abc10c4c6cf8246d2fc2fb81f5fa8` shows precisely these three files added, no unrelated path change. Both intent and claim are verified; the task is **CONSUMED** irrespective of the unchanged `PERSISTED_NOT_STARTED` text in the stale private handoff.

The `checkpoint/pre-baseline-review` implementation branch is still `141b5199592369ab953f52c55ebdb72ba300f1a4`; nothing from the locally preserved two-file draft exists in the remote implementation branch, hence that draft cannot be treated as an implementation candidate or independently reviewed final source. The report's local full protected-state checks, exact 40-hex `db339e1e90392cfd994e2d42bca7ce88afb3be21` scheduler worktree match, preservation totals, `git diff --check` result and unique unfinished draft patch are **AGENT_REPORTED_RUNTIME** evidence; the independent reviewer did **NOT** run protected local Git, Gradle, JVM, Android or device commands. Remote source and private report identities are **GITHUB_VERIFIED**. No false test PASS or root closure follows.

## Source-semantic proof: current queue full-row replay can erase a later privacy change

All source references below are exact at `141b5199592369ab953f52c55ebdb72ba300f1a4`.

1. [`DownloadViewModel.queueProcessingDownloads`, lines 2118–2122](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt#L2118-L2122) loads `repository.getAllProcessingDownloads()` and passes snapshots to `queueDownloads`.
2. [`queueDownloads`, lines 2230–2240](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt#L2230-L2240) caches `sourceSnapshots`; [status mutation, lines 2327–2336](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt#L2327-L2336) reuses the original `DownloadItem` to prepare Queued/Scheduled publication.
3. [`persistQueuedItems`, lines 2353–2360 and 2411–2435](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt#L2411-L2435) uses the prior `sourceSnapshots` for a call to `dao.updateForQueueIfSnapshot`. This call is under a recovery side-effect lease and execution lock; those guards do not themselves compare the row's `incognito` value.
4. [`DownloadDao.updateForQueueIfSnapshot`, lines 755–782](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/DownloadDao.kt#L755-L782) re-reads the current row in a Room transaction and compares status, execution/operation/retry IDs, issue and barrier authority but **not** `incognito` or a captured incognito generation. On matches it calls `updateRaw(item.copy(executionId = ""))`, a full-row replacement. A fresh Processing-only incognito edit can therefore be overwritten by the older queue snapshot. This is a directly demonstrated *possible interleaving in source semantics*, not an Android-race reproduction.
5. The same helper is called during [History re-download preparation, lines 2129–2159](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt#L2129-L2159) and from other queue/retry/notification paths, so changing its contract must be reviewed against **all existing caller meanings** rather than globally widening accepted writes. The persistence result is used for [actual WorkManager handoff, lines 2485–2500](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt#L2485-L2500).
6. The [existing UI incognito setter, ViewModel lines 3105–3111](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt#L3105-L3111) changes Processing values independently; the [worker claim-to-runner and History](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L1370-L1416) correctly freezes a **claimed** `downloadItem.incognito` under user-approved A, but that snapshot can already be wrong due to pre-claim stale queue publication.

Concrete bad ordering: queue reads Processing/incognito=false; another admitted UI coroutine commits Processing/incognito=true; stale queue transaction still matches its current predicates; it writes the old false with Queued/Scheduled; subsequent worker claim legitimately freezes false and ordinary History/logging may occur. The same UI often disables new toggles after pressing Queue, but that does not prove already-admitted asynchronous toggles or other producers cannot interleave. **The conditional bad ordering is SOURCE_VERIFIED; actual field race frequency and local execution are NOT_VERIFIED.**

This is not a ninth finding. It is a material **pre-claim propagation / competing-writer** subcase of `BUG-INCOGNITO-01` with no independently established new root. Preserve the canonical eight OPEN P2 and policy A. No warranted changes to worker-held incognito, fingerprint algorithms or retrospective log/history behavior.

## Reapproval recommendation and exact safe boundary (NOT an implementation authorization)

**Proposed narrow policy-A queue extension for a later authorized task:** a captured stale queue/preparation `DownloadItem` must never overwrite a more recently committed Processing incognito value and then claim/launch the old privacy value. At the exact queued-state mutation boundary, under existing Room transaction/Restore/lease/execution ownership, either (i) CAS-refuse the stale requested queue transition if its prior incognito identity/value is superseded, preserving the new Processing state and surfacing a truthful failure/refresh action, **or** (ii) prove an atomic merge of current incognito into the durable queued row AND every in-memory handoff/producer/duplicate/hydration consumer that uses the original object, so the worker sees exactly the committed queued policy. **Candidate (i) is the simpler fail-closed first option**, not a preauthorized specific patch. A refusal may itself require verifying how earlier same-request hydration or archived duplicate effects are handled; no hidden partial side effect.

Potential in-scope expanded review files: `DownloadDao.kt`, `DownloadViewModel.kt`, narrowly required queue-callers and incognito UI/adapter/tests. **Do not automatically approve** a global `updateForQueueIfSnapshot` signature/API or broad `DownloadRepository` behavior change without tracing `queueProcessingDownloads`, direct individual `queueDownloads`, `hydrateHistoryRedownloadBeforeQueue`, retry/reconfigure, notification Saved→Processing and worker/admission consumers. No schema/fingerprint/history/worker rewrite is assumed. If preserved configuration/revision/incarnation evidence cannot distinguish stale selection, STOP rather than CAS only a colliding/ambiguous value.

Required future direct regression: deterministic latch after initial queue snapshot before publication, transactional UI incognito change, old queue publication refusal or proven atomic current-value propagation, truthful UI, no WorkManager start on refused row; nonconflicting normal queue; Queued/Scheduled/Active no incognito mutation after claim, restart/requeue, missing/deleted/recreated row, duplicate/preparation path safety, old supported schema state as applicable and actual History/log/fingerprint consistency. Agent-reported draft at `D:/AndroidStudioProjects/ytdlnisx-f11/build/wp1-incognito-policy-a-20261021-e3070e89` is unfinished with missing UI/API wiring and uncompiled code; preserve it untouched. A new agent may *inspect* its diff only after separately granted local protected-state authority and exact source/provenance checks, never assume it is clean, mergeable or authorized to overwrite.

No new implementation task, persisted prompt, or `NEXT_CHAT.md` routing is authorized by **this review-only checkpoint** under protocol §8.2. This exact WP-1 instance is consumed by its archived STOP, so its existing launch-ready capsule is stale and **must not be reissued**. Separate user approval and a fresh guarded handoff/prompt lifecycle transition are required to expand queue authority. The current Android V55 guest state and installation/instrumentation permission are still unverified; do not infer Android closure readiness. Mandatory Android/focused verification remains NOT_REACHED, not waived.

## Independent retrospective

The protection fix in WP-1 instance 02 successfully moved the boundary forward, but source tracing exposed a second independent concurrency writer before worker admission. The required improvement is to follow the incognito configuration value through **all preclaim full-row persistence carriers**, not merely to fix the incognito DAO predicate and postclaim History snapshot. The STOP was appropriate under the narrow prompt because queue transition authority was expressly excluded. Keeping source-test execution and history frozen avoids partial-correction green claims. Archive the precise source counterexample, prioritize caller-complete CAS semantics and appropriate deterministic race regression when specifically authorized; do not turn this into a generic device-health task or a new overarching protocol revision.

**Final decision:** STOP accepted, same-root `BUG-INCOGNITO-01` remains OPEN, 8 active P2, CLEAN=NO, no source push or executed tests, no new implementation authorization, previous protected work and local draft preserved by report.
