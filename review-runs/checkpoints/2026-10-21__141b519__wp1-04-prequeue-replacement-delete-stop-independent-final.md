# Independent WP1-04 STOP completion review — pre-queue original replacement deletion

checkpoint_kind: INDEPENDENT_COMPLETION_STOP_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: dc8cb3cc6040bd393c90bb891d36fa0ac7330c02
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
governing_lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
original_wp0a_product_checkpoint_blob: 59e085fcea8cc0e53dd2356e3f8e79333a88f3ee
private_report_path: ytdlnisx/reports/2026-10-21__WP1_INCOGNITO_POLICY_A__wp1-04-prequeue-delete-stop-20261021-74adba1f__FULL_REPORT.md
private_report_commit: aeaf77924db0509f2969bf5cc49f7a1bfefd2362
private_report_blob: 6c3e959a82f93f67fe419fc581a540a5b84194aa
private_report_parent: 903d63e2c258922854c6deb2e7df7a421a22a775
task_instance_id: WP1_QUEUE_REGISTRATION_FROZEN_20261021_141B519_04
task_prompt_blob: b7f1744c12f9b4d2f291da3e10117637680fb615
task_prompt_origin: 11b08221a11961081597676974498399a2bfd4c6
task_intent_commit: 6052bb6e299ab7dd6ae6e3793394173ae2cfef7c
task_intent_blob: c2e35844d2c5329a61bee15a0c20429b00a563fb
task_claim_commit: 903d63e2c258922854c6deb2e7df7a421a22a775
task_claim_blob: e666e79eec745be9b619613c84b6f2bd0678ed23
independent_verdict: STOP_VALID_MATERIAL_PRE_ADMISSION_DESTRUCTIVE_EFFECT_GAP
same_task_execution_disposition: CONSUMED_STOPPED_NO_RELAUNCH
implementation_commit_published: NO
implementation_branch_advanced: NO
implementation_semantic_candidate: NONE_PUBLISHED
unfinished_local_draft: EIGHT_FILES_PROTECTED_UNCOMPILED_UNCOMMITTED_AGENT_REPORTED
independent_local_worktree_hash_verification: NOT_PERFORMED_GITHUB_REPORT_ONLY
agent_reported_build_commands: ZERO
agent_reported_test_methods_executed: ZERO
independent_tests_executed: ZERO
android_execution: NOT_REACHED_NOT_AUTHORIZED
new_canonical_root_count_delta: ZERO_THIS_REVIEW
candidate_independent_replacement_root: NOT_VERIFIED_SEPARATE_ROOT_REQUIRES_ADDITIONAL_ALIAS_AND_CLOSURE_AUDIT
confirmed_wp1_residual: PRE_ADMISSION_REPLACEMENT_DELETION_NOT_COMPATIBLE_WITH_REFUSED_QUEUE_NO_EFFECT_CONTRACT
canonical_in_scope_open_p2: 3
canonical_in_scope_ambiguous_p2: 4
canonical_in_scope_closed_p2: 2
canonical_in_scope_unresolved_p2: 7
canonical_clean: NO
new_prompt_or_handoff_authorized_by_this_review: NO
new_prompt_or_handoff_modified: NO
production_source_or_device_modified: NO

## 1. Independent execution evidence and stop precedence

At private `ireum-0/private:ytdlnisx-review` commit `aeaf77924db0509f2969bf5cc49f7a1bfefd2362`, the immutable Full Report has exact Git blob `6c3e959a82f93f67fe419fc581a540a5b84194aa`. The forward delta from the previous launch-ready private `f2540ff65848d7318dc2adeeacde153b35c2a0a9` contains exactly a new intent, a matching new claim, and the single added STOP Full Report; no launch handoff was repaired by the implementation agent. The report commit is the sole child of the claim commit. The unchanged implementation tip is exactly `141b5199592369ab953f52c55ebdb72ba300f1a4`, with no new published patch.

GitHub readback independently matches the deterministic intent and claim singleton task ID, prompt blob/origin, implementation SHA, protocol identity and one common claimant `74adba1f2e4c6ec28e60d207d32aadbb`; the intent and claim are recorded at their separate first-add commits. These receipts make WP1-04 consumed, and the same-instance STOP report supersedes the stale private `PROMPT_EXECUTION_STATUS=PERSISTED_NOT_STARTED` flag. No second attempt, old WP1-04 bootstrap, prompt recreation or adoption of unfinished draft is valid. Implementation source changed: NO. Report's Windows protected-state, eight-file draft/hash, git diff --check, compilation and test details are **AGENT_REPORTED**, not independently rerun from GitHub; the eight edited files are not a committed/published corrected source. ZERO Gradle, ZERO test methods, ZERO Android tests, and ZERO source pushes were reported. Preserve the draft and all previous protected artifacts untouched.

## 2. Independent real production producer → mutation → consumer effect

The source at `141b5199592369ab953f52c55ebdb72ba300f1a4` establishes a concrete real multi-item replacement path, not merely an agent assertion:

- `SavedDownloadsFragment.kt:331-338` and `ErroredDownloadsFragment.kt:346-353` convert selected original row IDs to separate Processing candidates asynchronously and pass original `selectedObjects` as `currentDownloadIDs` via Bundle. The Bundle is numeric ID-only; original observation-time incarnation/revision is not carried. The new Processing candidates and original selected rows are distinct identity domains.
- `DownloadMultipleBottomSheetDialog.kt:141` reads that original-ID array; `scheduleBtn` callback around 271 calls `downloadViewModel.deleteAllWithID(currentDownloadIDs)` BEFORE `updateProcessingDownloadTimeAndQueueScheduled`. The regular download callback around 304 calls the same deletion BEFORE `checkProcessingDuplicates` and `queueProcessingDownloads`. A Save action around 348 also deletes originals after a different Processing→Saved operation; its precise post-success semantics require independent classification.
- `DownloadViewModel.kt:1513-1516` delegates to `repository.deleteAllWithIDs`, refreshes the low-quality ledger, then cancels membership-waiting notifications. `DownloadRepository.kt:3284-3286` fetches selected current IDs and passes rows to `deleteKnownUserRemoval` without an expected status. `DownloadRepository.kt:4244-4282` applies `expectedStatus==null` original list without original-generation authorization, terminalizes linked children, deletes History replacement barriers and Download rows in a separate Room transaction, releases pending Undo tokens after commit, and calls `deleteCache(authorizedItems)` for **owned** cache. The ownership guard is acknowledged; arbitrary unowned filesystem deletion is NOT asserted.
- Independently of any privacy race, if `checkProcessingDuplicates` reports a duplicate after the original-row deletion, this same callback returns early and never queues the intended candidate. Alternatively if the later CAS refuses a changed Processing privacy generation, the original was already removed. The old original's Room row, linked ledger/barrier, undo owner/cache effects are not restored by the later refusal. This is a concrete possible producer/write/refusal/final-effect mismatch; **no Android reproduction or runtime interleaving was performed**.
- The pre-refusal original-ID lookup is also susceptible to selecting a different current incarnation after a delete/recreate/Restore. A current stamp for new candidate P is not an original row A stamp. Moving the delete call after WorkManager dispatch is not on its own correct: it risks concurrent old/new owners or cleanup failure after new admission. Wrapping Room only does not reverse already-performed filesystem or process-local Undo effects.

This proves the WP1-04 correction cannot claim completed all-effects queue refusal protection by fencing only `DownloadDao.updateForQueueIfSnapshot`. The agent's STOP before a test/build/push was appropriate.

## 3. Root and historical closure reconciliation (bounded)

`BUG-INCOGNITO-01` remains OPEN: stale queue item can overwrite privacy and the new source has not changed. The observed premature original deletion is a material **blocking dependency** for implementing the approved WP0-A policy's refusal-preserves-prior-durable-state contract. No current-sha WP1 fix or closure exists.

The generic pre-admission destructive removal may additionally be a **distinct** bug even if privacy never changes, because a duplicate check may refuse after already committed deletion. Do NOT automatically merge it into BUG-INCOGNITO-01, count it as a new root, or claim it is safely covered by an old CLOSED root. Explicitly compare it in later reviewer work to repository OPEN `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` (status-sweep deletion stale snapshot) and previously CLOSED `BUG-RECONFIGURE-01` (notification sibling/global Processing cleanup). Their historical correction paths and producer boundaries are different; this checkpoint does **not** reopen the latter or revise any official root count. That dedicated duplicate/alias/scope classification and sufficient original-target destructive ownership proof remain **NOT_VERIFIED**, not a silent status claim. Preserve BG02/BG03, scheduler WINDOW/RESTORE, existing Undo/recovery and format/Result contracts.

The current official downloaded active inventory remains 3 OPEN + 4 AMBIGUOUS + 2 in-scope CLOSED = 7 unresolved P2, P0/P1 zero; repository derived 151 = 52 OPEN + 4 AMBIGUOUS + 95 CLOSED. `CLEAN=NO`. No canonical inventory/index rewrite from an uncompleted duplicate/alias determination.

## 4. Narrow next reviewer prerequisite — WP0-B, NOT a new implementation prompt

A **reviewer-owned, read-only, source-complete replacement-operation boundary** is required before authorizing a new implementation source task:

1. Identify the exact original row observation, status, DOWNLOAD incarnation/revision and effective deletion authority at Saved/Errored/Cancelled multi-item navigation; distinguish the separate newly-created Processing candidate and planned target mapping. Bound asynchronous original→Processing preparation to identity/ownership and actual generation; reject unknown/unversioned originals without destroying them. Enumerate zero/one/many partials, duplicates and ID reuse.
2. Prove one operation-wide success/refusal contract: queue/scheduled candidate publication, CONFIG/archive duplicate/error classification, History hydration, scheduler and eventual WorkManager handoff versus original deletion, linked child ledger/barriers, user Undo, owned cache cleanup, membership notification; prevent pre-admission deletion on a failed candidate. Consider split-phase durable hold/reservation/compensation and process-death convergence only if supported by existing code and separately approved owner; never invent a new durable schema without authorization.
3. Trace cancellation/recovery/Restore, all five real `updateForQueueIfSnapshot` caller classes, save-for-later nonqueue action and actual adjacent configuration/Undo surfaces; prove at what irrevocable effect boundary another owner is prevented from acting, and at what point UI success can be claimed. Avoid broad delete-before-check workarounds and leave unrelated old semantics untouched.
4. Decide whether source can close the mismatch within already-authorized narrow DAO/VM/UI/repository scope without new deletion/cache/Undo ownership primitive. If the necessary correction requires a destructive-owner redesign, new schema, or global Restore/worker scope, **STOP prompting** and request explicit separate authorization on exact minimal expanded contract and its tests. If an old/new authority discriminator collides, preserve the WP0-A instruction to fail closed. No fresh WP1 agent ID or prompt is justified until this review gate closes.
5. Deterministic negative tests must prove old A exists after refusal, original linked ledgers/barriers/Undo and owned caches remain intact, no unauthorized notifications or worker, saved/errored/cancelled exact descendant ownership, duplicate refusal, OFF→ON competing Processing edit, Restore/delete/recreate and process-death/retry, correct success of legitimate replacement without parallel old/new owners. Preserve final-SHA Android permission STOP and immutable 197 selectors.

This checkpoint is **review-only**. Protocol §8.2 forbids changing agent-routing fields of `NEXT_CHAT.md`, creating a successor prompt, or launching the agent from this review evidence alone. Existing private capsule remains **historically stale and nonlaunchable** because WP1-04 has an exact matching STOP report and claimed receipts. Future separately authorized reviewer routing must reconcile it; do not emit canonical WP1-04 bootstrap. No local protected state was modified during this review.
