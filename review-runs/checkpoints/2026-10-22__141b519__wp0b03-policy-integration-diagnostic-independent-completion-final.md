# WP0-B03 — independent completion review of adopted-policy integration diagnostic

checkpoint_kind: INDEPENDENT_DIAGNOSTIC_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
expected_review_parent: 9b4b6d4635acd094fa4f9d7333ff308a39bcaca1
implementation_reviewed_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
implementation_changed: NO
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
WP0B03_policy_checkpoint_blob: c5bd106b874a6bdd32b9c684acd4e2d3d2b2042e
WP0B03_task_instance_id: WP0B03_PRODUCT_POLICY_FEASIBILITY_20261022_141B519_01
WP0B03_prompt_blob: 3b2c544f11a98040603ac47b49338e3d40b40ff4
WP0B03_prompt_origin_commit: c2cf24e1f717f6408988142f93798df91e468d4c
WP0B03_intent_commit: 5ce876ca1280462078567285918e8bcc21866a85
WP0B03_intent_blob: f19814a29de2eb3ce485e98af6dc74052a9ad428
WP0B03_claim_commit: e1b9dd0a7fd0dbcce1ce264ec030db7be79b5d9a
WP0B03_claim_blob: 6ee4fd7a2ca186dd1698ba494b13fa018c14e6c7
WP0B03_report_commit: 81391139e63f2593cffda5e3df9354e7a99cba8c
WP0B03_report_blob: 32e061767277344a902e8d7958ed78cb22338e2f
WP0B03_report_result: CONTRACT_GAP
independent_verdict: CONTRACT_GAP_CORROBORATED_NOT_IMPLEMENTATION_READY
full_implementation_ready: NO
root_state_changes: 0
new_independent_roots: 0
canonical_active_P2_OPEN: 4
canonical_active_P2_AMBIGUOUS: 4
canonical_active_P2_CLOSED: 2
canonical_active_P2_UNRESOLVED: 8
canonical_repository_roots: 152
canonical_repository_OPEN: 53
canonical_repository_AMBIGUOUS: 4
canonical_repository_CLOSED: 95
clean_status: NOT_CLEAN
clean_basis_advanced: NO
prior_manual_L2_review: IN_PROGRESS_UNCHANGED
source_edits: ZERO
test_executions: ZERO
build_device_execution: NOT_EXECUTED
new_agent_prompt: NO
private_execution_handoff_mutation: NO

## 1. Scope and immutable report verification

This is an independent completion assessment of a source-only preflight, **not** an exact-message `3` manual review, production patch review, or a new implementation authorization. User-provided report claimed `RESULT=CONTRACT_GAP` and 0 source/tests/build/device effects. It has been read back from exact private report path `ytdlnisx/reports/2026-10-22__WP0B03__wp0b03-20261022-958d718b__FULL_REPORT.md`, blob `32e061767277344a902e8d7958ed78cb22338e2f`.

The private branch advanced normally and linearly by exactly three commits from `0b0ddf07cd53645c7bbd3e1211200a87c5f220bb` to `81391139e63f2593cffda5e3df9354e7a99cba8c`: one immutable new intent, one matching immutable claim, one new Full Report. Each has exactly one parent equal to prior step and adds only its permitted path; compare returned ahead 3 / behind 0 and only the three new paths. Intent and claim both have the same task ID, prompt path/blob, origin, pinned source/protocol and identical 32-hex claimant. Claim points to verified intent commit/blob; source implementation tip remains `141b5199592369ab953f52c55ebdb72ba300f1a4`. This proves *repository publication chain*, not any host state assertions by itself. The report's 448-existing-blobs preservation assertion is consistent with the three-path diff but was not separately rehashed by this reviewer; protected local workstation/worktrees are report-attributed only.

The report is therefore **consumed and immutable**. Do not relaunch the same WP0B03 task or infer launch readiness from the stale prior handoff `PERSISTED_NOT_STARTED`. No implemented correction or exact-candidate executed verification resulted.

## 2. Independent semantic verdict and exact production corroboration

**Source-proven current root remains OPEN P2 `BUG-RECONFIGURE-PREADMISSION-DELETE-01`**. The manual replacement dialog calls `downloadViewModel.deleteAllWithID(currentDownloadIDs)` before `checkProcessingDuplicates` / `queueProcessingDownloads` and before scheduled successor admission, so a refusal can destroy A before P is accepted. [DownloadMultipleBottomSheetDialog.kt L266–357](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/ui/downloadcard/DownloadMultipleBottomSheetDialog.kt#L266-L357). This remains the previously established one semantic root, not an independently new root or a regression of the separately CLOSED notification deletion and duplicate-admission contracts.

**Undo-vs-Restore preownership guard is a promising boundary but not a source-closed stage.** `RestoreTransactionCoordinator.begin` publishes a durable active pointer under `RestoreMutationAdmission.withRestorePublication` without a final complete linked Undo inventory. `DownloadRepository.acceptUndoResolution` enters `withOrdinaryMutation`; fallback selected intent is stored in SharedPreferences as kind + authority/resolver generation + RESTORE/COMMIT, not original A/linked target identity. Reader's decode failures can become null. Room carrier, process-local snapshot, low-quality linked owner, and stale completed fallback can differ. Even though the process-local R gate can order admitted calls, it cannot itself prove exact cross-carrier negative target correlation or independent-process exclusion. [RestoreMutationAdmission.kt L19–80](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreMutationAdmission.kt#L19-L80), [RestoreTransactionCoordinator.kt L428–500](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt#L428-L500), [DownloadRepository.kt L1360–1492](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1360-L1492), [L1640–1798](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1640-L1798). Concrete counterexample: selected RESTORE fallback persists while Room marker write fails; `begin` publishes the pointer before discovering the choice and the ordinary resolver is subsequently gated. Waiting for that resolver under active Restore would produce circular dependency; pretending fallback absence means no Undo can lose an accepted choice. The specific target-aware inventory/progress API is NOT_VERIFIED.

**Current targeted reset does not implement the adopted selected-Undo policy.** `clearRestoreTargetedUndoCarriers` deletes PendingUndo tokens matched through target numeric snapshots without checking selected RESTORE/COMMIT. It cannot be extended to consume replacement receipt authority without a reviewed precedence/transfer contract. [DownloadRepository.kt L451–475](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L451-L475); [RestoreTransactionCoordinator.kt L980–1048](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt#L980-L1048).

**Restore APPLYING repeatability remains unsatisfied for a future A/P receipt.** Room apply may commit before external DATA_COMMITTED journal update; recovery repeats APPLYING and `applyAuthoritativeState`. Existing `sidecarsCaptured` inventories current numeric targets once, not retired A and a proposed replacement receipt. A durable decision must bind immutable Restore operation ID and receipt preimage and remain discoverable through final postcommit reconciliation, not infer ownership from imported numeric rows. [RestoreTransactionCoordinator.kt L517–565](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt#L517-L565), [L792–875](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt#L792-L875).

**Ordinary Worker claim and generic carrier deletion are not replacement-aware.** Global/priority Queued/Scheduled Flow can select P without the proposed dedicated handoff request; `claimDownloadThroughProductionAdmission` checks existing native/ledger/capacity/History/operation CAS but no new P exchange receipt/Restore disposition. A normal Worker might claim a P whose proposed carrier is pending/revoked; this is a future naive integration counterexample, not evidence the existing SQL concurrently claims one unchanged row twice. Generic `getOutstanding` omits SUPERSEDED and `deleteResolved` can erase effect-only debt if reused naively. [DownloadWorker.kt L265–291, L1289–1324](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L1289-L1324), [DownloadSchedulerAdmission.kt L185–307](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadSchedulerAdmission.kt#L185-L307), [WorkManagerHandoffCarrierDao.kt L17–182](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/WorkManagerHandoffCarrierDao.kt#L17-L182).

No new canonical root is warranted for unimplemented hypothetical receipt, missing prospective guard or same-root residual; preserve original OPEN, unaffected CLOSED roots, and counts. Agent report's proposed new tests are **unexecuted**, not evidence of production repair. Independent source validation establishes `CONTRACT_GAP`, not `CLEAN` or implementation readiness.

## 3. Exact next proof and boundary

The smallest next reviewer-owned technical proof is a **complete unresolved Undo responsibility inventory before Restore pointer publication**, including:
- trace producers, cancellation/removal snapshot authority, Room carrier, fallback-only selected intent, process-local resolver and postconvergence stale fallback; distinguish intersection vs verified disjoint and UNKNOWN after process death;
- identify durable trusted target/linked identity representation across Room write failure, old unversioned fallback, migration/restart, supported process topology; never fabricate it from a fresh current-ID lookup;
- source-close all writer/reader/cleanup paths and final M→R pre-pointer snapshot/negative predicate with a real failure result; do not wait for Undo under the active Restore pointer or default unknown to COMMIT;
- only after that proof consider a separately authorized implementation guard plus actual source-wiring tests, before full per-member A→P exchange/Worker/Restore integration. If current fallback cannot represent needed identity and convergence, evaluate the smallest *conditional* independently durable versioned extension; **no table/migration/global mutex is preauthorized by this checkpoint**.

The adopted product policy remains fixed: selected Undo protected, unselected conflict explicitly deferred before Restore, Restore-first rejects late old selection; per-member partial success retaining failed A; Restore only changes actual target and preserves valid unaffected P. Status remains NOT_CLEAN. This report/independent review does **not** authorize production edits, new prompt instance, manual L2 finalization or a private execution-control rewrite. `NEXT_CHAT.md` still contains the historical launch-looking WP0B03 capsule; the matching immutable execution report supersedes it under protocol §21.5. No canonical bootstrap is permitted for this consumed task.
