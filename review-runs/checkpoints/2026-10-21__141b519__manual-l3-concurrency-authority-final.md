# Manual correctness review — 141b519 L3 concurrency/authority — FINAL

date: 2026-10-21
checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: FINAL
manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 82cf949a6de745d1b4e678ec379034c8a0eb9fde
review_expected_parent: 84a1f6e90f09eae61d76e146013f21d1b0644098
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
review_start_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
previous_comparable_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
previous_sha_to_current_compare: AHEAD_3_BEHIND_0
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
applicable_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
review_mode: TRIGGER_3_NEW_SHA_MANUAL_FULL_REVIEW_NOT_IMPLEMENTATION
run_completed_scope: BASELINE_L1_L6,TRIGGER_MAP,ALL_APPLICABLE_BLOCKER_RELEVANT_MODULES,AFFECTED_PRODUCER_CONSUMER_RECOVERY_EFFECTS,PRIMARY_L3_DEEP,R1_R5_SELECTION,AMBIGUOUS_ROOT_ATTEMPTED_RESOLUTION,EXACT_SOURCE_ROOT_RECOUNT,FINAL_INVENTORY_AND_INDEX_READBACK
run_remaining_scope: NONE_FOR_CURRENT_MANUAL_RUN
primary_deep_lens: L3_CONCURRENCY_AND_AUTHORITY
primary_deep_selection_reason: R1_TWO_PROVEN_NEW_CROSS_THREAD_AUTHORITY_PROBLEMS_AND_EXISTING_STALE_QUEUE_PRIVACY_INTERLEAVING
current_sha_deep_lenses: L3
current_sha_remaining_not_yet_deep: L1,L2,L4,L5,L6
current_sha_next_deep_hint: L1_DURABILITY_AND_RECOVERY
independent_verdict: NOT_CLEAN
new_semantic_roots: BUG-FORMAT-SESSION-FIRST-USE-RACE-01,BUG-FORMAT-SOURCELESS-COMMAND-MEMBER-01
new_root_count_delta: +2
new_open_count_delta: +2
closed_root_delta: +2
open_to_ambiguous_count: 4
active_unresolved_p0: 0
active_unresolved_p1: 0
active_unresolved_p2: 8
active_confirmed_open_p2: 4
active_ambiguous_p2: 4
active_scope_discovered_roots: 10
active_scope_closed_roots: 2
repository_discovered_roots: 152
repository_open_roots: 53
repository_ambiguous_roots: 4
repository_closed_roots: 95
repository_unresolved_roots: 57
canonical_inventory_blob: e54d2b1e7f02b32dbd632d457ab8e822876fc37d
repository_index_blob: 404a6e9a001e334acf3bb1d2e339c6c33c1030a2
canonical_clean: NO
clean_review_basis: NONE_REPOSITORY_WIDE_CLEAN_REVOKED
independent_execution: NOT_EXECUTED
independent_android_execution: NOT_EXECUTED
independent_kotlin_build_execution: NOT_EXECUTED
implementation_source_changed: NO
task_prompt_or_agent_lifecycle_changed: NO
private_handoff_changed_by_this_review: NO
protected_worktrees_edited: NO

## 1. Independent verdict — NOT_CLEAN

Source remains 141b519, the new implementation version after db339e1 with published Room64/65 schema, FORMAT_BATCH carrier/recovery, exact per-member authority, recovery-issue UI, and converter changes. 3 forward commits, 0 behind against db339e1. Prior same-SHA manual depth from db339e1 was not inherited; current SHA BASELINE L1-L6 was independently inspected and L3 promoted to DEEP. No current clean contiguous repository basis exists; CLOSED individual roots do not prove CLEAN.

The 2026-10-21 private WP1_03 task has exact once-only intent 7a4718965ab656567cc92f5c36c60fb65486c096, claim df492014df65cc581e33a82f8a99030bc8adeea5, and matching unique STOP Full Report blob 52f8bb091c2734edc979fcf506e2259aa4f313b1. The stale `NEXT_CHAT.md` PERSISTED_NOT_STARTED capsule is superseded by that report; this is not a launch-ready or active-source wave. Neither STOP nor review-only evidence authorizes relaunch, new prompts, or agent-routing edits.

## 2. Findings with original wrong effects and exact independent dispositions

The preceding SAME-RUN checkpoints contain detailed producers, carrier, consumers, CAS, recovery and cross-attempt proof:
- `review-runs/checkpoints/2026-10-21__141b519__manual-l3-first-in-progress.md`, SHA f634ee684f48e7bb5bd317f17dcb959ab553f9fd;
- `review-runs/checkpoints/2026-10-21__141b519__manual-l3-command-member-in-progress.md`, confirmed OPEN source-less command root;
- `review-runs/checkpoints/2026-10-21__141b519__manual-active-root-disposition-in-progress.md`, source and agent-test gate details for all ten current active-scope roots.

Exactly one current status per root:

| Root | Status | Production effect / decisive closure barrier |
| --- | --- | --- |
| BUG-FORMAT-BG-01 | OPEN | Unselected Processing row B becomes Saved when only A requested; `FormatBatchHandoff.stage` transitions all captured before selection. Historical test PASS explicitly expects wrong effect. |
| BUG-FORMAT-BG-02 | CLOSED | Old accepted-result omission gone; exact durable FORMAT_BATCH payload+carrier staged transactionally and WorkManager Operation acknowledgement awaited; exact-SHA reported PASS for failure, restart, terminal without ack. |
| BUG-FORMAT-BG-03 | CLOSED | Old per-item swallowed exception/false batch success gone; pending member keeps WorkManager retry and suppresses completed notification; production exact-SHA reported partial-sibling fault PASS. |
| BUG-FORMAT-BG-04 | AMBIGUOUS | Source now uses exact incarnation/revision/actor/config and narrow format-column update; new SQL trigger writer semantics require currently unavailable final-SHA native SQLite Room65 OR ABORT/reopen/integration test. |
| BUG-FORMAT-BG-05 | AMBIGUOUS | Source now binds progress cancel to UUID PendingIntent/receiver/current carrier CAS; delivered actual PendingIntent→receiver→Room→WorkManager final-effect Android gate unexecuted. |
| BUG-INCOGNITO-01 | OPEN | ANY count used for ALL, selected DAO write not Processing-only, and stale full-row queue re-publication can overwrite newer incognito edit before worker claim. Existing WP1_03 STOP/no source change. |
| BUG-FORMAT-AUTHORITY-TRIGGER-01 | AMBIGUOUS | Earlier X SQL conflict fixed in new trigger source, but exact corrected-SHA `FormatAuthorityTriggerProductionWiringTest` OR ABORT/REPLACE/INSERT/reopen/old-schema actually executed method results absent. |
| BUG-RESULT-NULL-CHAPTER-CONVERTER-01 | AMBIGUOUS | Revised `Converters.stringToListOfChapters` handles null/trimmed JSON null, but exact-SHA production Room DAO/reopen migration tests in newly added `ResultChapterConverterProductionWiringTest` have no execution result. |
| BUG-FORMAT-SESSION-FIRST-USE-RACE-01 | OPEN | New distinct global session first-use split DB S2/memory S1 under two concurrently admitted callers; subsequent format owner calls consistently reject. |
| BUG-FORMAT-SOURCELESS-COMMAND-MEMBER-01 | OPEN | New distinct valid blank-URL command rejected by universal `FormatAuthority.proven`, worker creates false unproven issue and stalls selected format batch, even when command intentionally selected. |

For each AMBIGUOUS: precise unresolvable-now reason is independently verified **lack of the named mandatory exact-141b519 Android/Room or delivered notification-effect method results**, not a generic hypothetical test need; existing V39/V51/V52 reports and named test sources were read and counted; the originally governed Android/AVD state is unverified and reviewer-only request lacks authority for new test/device mutations. One bounded next feasible evidence action per root is explicitly recorded in preceding classification checkpoint. A previous OPEN at old X cannot prove bug remains at 141b519 when relevant source differs; source-fixed alone cannot claim an executed gate PASS. Both CLOSED roots have direct source proof plus named attributed exact-141b519 executed tests, whose execution is not falsely attributed to this reviewer.

Two independent new roots versus originals were counted once each. Both are source-proven reachable incorrect-final-effect paths, not just helper suspicion, broken test, alias or staged uncommitted draft. A system-wide observed incidence for the new races is NOT_VERIFIED; deterministic fixture/latch execution is a correction gate, not a prerequisite to source-supported OPEN when actual semantics are proved.

## 3. Baseline lens and trigger matrix

- L1 Durability & recovery: BASELINE_FAIL — session S1/S2 splits persistent authority and process session; format retries cannot converge under mismatched session; old accepted WorkInfo terminal-without-ack is correctly retained. Restore replay was traced through `WorkManagerHandoffRecovery.reconcileFormatsForRestore`; true new Room65/old row runnable final result remains AMBIGUOUS.
- L2 Identity & provenance: BASELINE_FAIL — legitimate source-less command is misclassified as unproven due absent URL despite existing command format identity; exact UUID handoff and row reuse fences appear source-correct for regular members.
- L3 Concurrency & authority: DEEP_FAIL — concrete S1/S2 first-use interleaving; unrelated Saved side effect by selected batch, live Processing incognito edit overwritten by previously captured queue snapshot. No live wrong state is accepted on bare numeric ID alone under corrected FormatAuthority publish, subject to SQLite native gate.
- L4 Destructive ownership: BASELINE_FAIL — unselected status publication and privacy overwrite are effects outside actual current authorized selection. Destructive Reset admission/quiescence and exact WorkManager cancellation were traced; no claim broad scheduler cancellation root closed.
- L5 Platform contract closure: BASELINE_FAIL/EXECUTION_GAPS — current format CoroutineWorker now awaits `setForeground`, but exact notification action/cancellation and new Room65 trigger converter/migration tests remain not executed; historical repository-wide foreground completion root is not auto-closed.
- L6 Cross-feature semantic propagation: BASELINE_FAIL — command input blank URL, FormatRequestPolicy command type, selected/other background format capture, Result chapters, Restore and incognito queue/History all inspected through consumed final effects.

Triggered obligations, executed by source/test evidence review irrespective of DEEP depth:
- A foreground platform completion: TRIGGERED; source awaited but no reviewer-run Android.
- B WorkManager request acceptance/terminal recovery: TRIGGERED; root BG02 CLOSED with exact reported test evidence; retry/late old request, durable payload, SDK terminal no worker ack inspected.
- C identity/SQL authority and ownership: TRIGGERED; trigger conflict fix and Room current-old writer test coverage AMBIGUOUS.
- D packaged resource and recovery UI: TRIGGERED; More entry references string/XML supported but actual runtime render unverified.
- E external generated schema artifact: TRIGGERED; Room64/65 JSON committed and migration paths read, exact execution unresolved.
- F old persisted carrier and migration: TRIGGERED; legacy schema61/63/64/65, retained FORMAT_BATCH row, chapter SQL null/JSON null, source/Result ownership; the exact final Android gate not falsely called passed.
- G exact identity reuse: TRIGGERED; stale/recreated Download/Result, owner revision/incarnation/UUID paths read, prior PASS respected.
- H frozen preference/config materialization: TRIGGERED; `FormatRequestPolicy.capture` snapshot and background request materialization read; unrelated settings remain outside this delta.
- I maintenance versus live owner: TRIGGERED; exact format request cancellation, superseded tombstone, Restore quiescence checked; final notification-action gate AMBIGUOUS.

Producer/consumer delta contract: old generic WORKER input list/status full-row write and ignored external enqueue semantics now split into typed batch identity/payload, exact row authority, per-member FETCH/COMPLETE state and retained request carrier. Reviewed consumers include ViewModel, FormatBatchHandoff, UpdateMultipleDownloadsFormatsWorker, WorkManagerHandoffRecovery, FormatRecoveryIssues/Dialog, FormatBatchCancellation/CancelWorkReceiver, ResultDao, DownloadDao, DBManager onOpen, Migrations, RestoreTransactionCoordinator and command UI. No claim of exhaustive unrelated repository audit outside triggered scope.

Cross-attempt and fault cells: initial session A/B cold-start ordering = FAIL; selected A/unselected B = FAIL; source-less command (selected or unselected) = FAIL; old R1 SDK accepted/new R2 = source and attributed test PASS for exact requester; pending after one member failure = attributed test PASS; both SQLite trigger and converter old rows after native reopen = NOT_VERIFIED execution; old notification action vs current request = source scoped but native final-effect NOT_VERIFIED; stale Processing incognito write vs queue E2 = FAIL; new F11 Restored work requeue still flagged for affected native old schema evidence.

## 4. Review retrospective and checklist evolution

A prior test named `realProducerCapturesAAndBButLeavesLateCProcessing` encoded the wrong selected-bundle behavior by expecting B to become Saved, so a green test was not semantic authorization. The cold-session map publication after DB commit was not exposed by sequential producer and recovery fixture tests: two independently admitted consumers at app startup are needed. The source-less command witness was visible in `DownloadCommandFragment` and should have been paired earlier with the new universal `url.isNotBlank()` authority guard. These are avoidable consumer-closure/producer-inventory delays, not evidence of novel test-only roots.

The historical V34 SQL trigger and V38 converter failing source cannot be transplanted unchanged onto current v65 repaired source, yet newly added test source cannot be marked executed merely by existence. Earlier efforts repeatedly stalled on host, AVD, Win32/ADB, device ownership, or 180-second declared timeout with unverified enforcement. This final review reuses prior exact method-result evidence, labels unsupported outcomes, and does not rerun unchanged failed methods or do protected worktree edits.

Checklist evolution: NO CHECKLIST/PLAN/LEDGER MUTATION. Current v7 already requires semantic-contract delta, positive authority, producer→carrier→consumer closure, later-sibling failure, old-generation replay, delivered final-effect verification, exact test provenance. Proposed coverage improvement (not a governance change): add two named required regression rows for (1) simultaneous cold session initializers separated before process-map publication and (2) source-less command selected/unselected background batch with exact command authority and normal Result/Download sibling. Do not label a test PASS until actually executed.

## 5. Final checkpoint and count proof

Active canonical inventory updated and re-read exact blob `e54d2b1e7f02b32dbd632d457ab8e822876fc37d`, with 10 P2 roots: 4 OPEN, 4 AMBIGUOUS, 2 CLOSED; active P2 unresolved unchanged at 8 (OPEN+AMBIGUOUS), P0/P1 zero. Repository derived index updated/re-read blob `404a6e9a001e334acf3bb1d2e339c6c33c1030a2`: 152 total = 53 OPEN + 4 AMBIGUOUS + 95 CLOSED, unresolved 57. The table recount included three historical CLOSED entries whose severity is `NOT_VERIFIED` rather than P[0-3]; they are **not missing root IDs** and remain distinct. Historical footer audit arithmetic for original 148 and subsequent 150 remains immutable context, not current status.

This review changed only append-only reviewer checkpoints and their derived canonical indexes. No implementation, governance, source, private handoff lifecycle, new prompt, execution or device mutation. The private WP1_03 matching STOP report already supersedes stale private launch readiness and MUST NOT be relaunched; reviewer-only request does not authorize changing launch-control fields. CLEAN remains revoked; 141b519 cannot be called repository-wide clean.

manual_review_run_status: FINAL
primary_deep_lens: L3_CONCURRENCY_AND_AUTHORITY
current_sha_deep_lenses: L3
remaining_not_yet_deep: L1,L2,L4,L5,L6
next_not_yet_deep_hint: L1_DURABILITY_AND_RECOVERY
reviewer_executed_tests: 0
reviewer_executed_android_methods: 0
historical_agent_method_execution: V39_REPORTED_46_PASS_1_FAIL_AND_V51_V52_MIXED_INCOMPLETE_NOT_REEXECUTED
new_root_count_delta: +2
current_active_unresolved_total_delta: 0
repository_unresolved_total_delta: 0
checklist_change_required: NO
private_handoff_reconciliation_required_for_material_review_FACT_FIELDS_ONLY: YES
review_branch_final_readback: PENDING_VERIFICATION
