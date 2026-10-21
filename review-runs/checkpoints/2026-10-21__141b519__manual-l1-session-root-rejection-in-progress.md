# 2026-10-21 same-SHA manual 3 L1 review — intermediate: cold-session false-positive correction

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: IN_PROGRESS
manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 8852071b921ef023daa939d4393883a4bbd80a32
review_expected_parent: 8852071b921ef023daa939d4393883a4bbd80a32
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
master_plan_parts_verified: 0001.part-0013.part_MATCH_MANIFEST_AND_PINNED_TREE
pinned_master_plan_sha256_manifest: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
pinned_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
pinned_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
pinned_lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
pinned_lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
pinned_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
latest_prior_manual_final_blob: 092ef291ca716205098a62e88c172bbd2706b0a8
prior_manual_status: FINAL_NOT_RESUMABLE
agent_status: WP1_03_IMMUTABLE_INTENT_CLAIM_AND_STOP_REPORT_CONSUMED_NO_ACTIVE_WAVE
current_review_basis: SAME_IMPL_AS_PREVIOUS_FINAL_NEW_MANUAL_RUN
run_completed_scope: BASIS_GOVERNANCE_RECONCILIATION,PRIOR_MANUAL_REVIEW_FINAL_VERIFICATION,FORMAT_AUTHORITY_SESSION_PRODUCER_CONSUMER_MUTEX_END_TO_END,SESSION_FIRST_USE_CANDIDATE_REJECTION
run_remaining_scope: REMAINING_AMBIGUOUS_FIRST_REVIEW,BASELINE_L1_L6_REEVALUATION,TRIGGERED_MODULE_AND_CONTRACT_CLOSURE,PRIMARY_L1_DEEP,OTHER_CANDIDATE_PROOF_OR_REJECTION,FINAL_STATUS_COUNTS
primary_deep_lens: L1_DURABILITY_AND_RECOVERY_IN_PROGRESS
primary_deep_selection_reason: R1_DURABLE_RECOVERY_OPEN_AND_TRIGGERED_INTEGRATION
lens_coverage_current_sha: L1=DEEP_IN_PROGRESS;L2=BASELINE_FAIL;L3=DEEP_FAIL_PREVIOUS_FINAL;L4=BASELINE_FAIL;L5=BASELINE_FAIL;L6=BASELINE_FAIL
remaining_not_yet_deep: L1,L2,L4,L5,L6
next_not_yet_deep_lens_hint: L2_IDENTITY_AND_PROVENANCE
trigger_map: A_FOREGROUND=YES_PLATFORM_EFFECT;B_WORKMANAGER=YES_DURABLE_HANDOFF;C_SQL_AUTHORITY=YES_TRIGGER;D_UI_RESOURCE=YES_ISSUE_DIALOG;E_GENERATED_SCHEMA=YES;F_PERSISTED_MIGRATION=YES;G_ID_REUSE=YES;H_PREFERENCE_CONFIG=YES;I_CANCEL_VS_OWNER=YES;SEMANTIC_CONSUMER_CLOSURE=YES
independent_execution: NOT_EXECUTED
production_source_changed: NO
checkpoint_root_id: BUG-FORMAT-SESSION-FIRST-USE-RACE-01
checkpoint_root_disposition: FALSE_POSITIVE_REJECTED_NOT_CURRENT_CANONICAL_ROOT
checkpoint_root_delta: -1
checkpoint_active_unresolved_p2_after_reconcile: 7
checkpoint_active_open_after_reconcile: 3
checkpoint_active_ambiguous_after_reconcile: 4
checkpoint_active_closed_after_reconcile: 2
checkpoint_repository_roots_after_reconcile: 151
checkpoint_repository_open_after_reconcile: 52
checkpoint_repository_ambiguous_after_reconcile: 4
checkpoint_repository_closed_after_reconcile: 95
checkpoint_repository_unresolved_after_reconcile: 56
canonical_clean: NO

## Independent contradiction and exact proof

The prior same-SHA L3 FINAL `2026-10-21__141b519__manual-l3-concurrency-authority-final.md` counted an alleged S1/S2 first-use race: A commits DB SESSION S1, B commits S2 before A publishes S1 into process cache, and future calls fail. That ordering is **impossible in the actual supported single-process producer composition**.

`FormatAuthority.kt:45-69` wraps BOTH `db.withTransaction {...}` (lines 48-66) and `sessions.putIfAbsent(key,result.first)` (line 67) inside `RestoreMutationAdmission.withOrdinaryMutation(context) {...}` (line 46). `RestoreMutationAdmission.kt:18-36` uses ONE process-global `Mutex` and `withContext(Held())` around the full block, so B cannot enter its first `FormatAuthority.transaction` until A has completed its process-map publication and exited that global mutex. Reentrant `Held` can execute nested calls inside the same existing owner but cannot let an independently admitted B steal its ownership. Room's own serial transaction adds a second serialization layer. The source manifest `AndroidManifest.xml` contains no `android:process` override for app/WorkManager/CancelWorkReceiver; no production alternate-process producer of this session was established. A successful DB commit followed by suspended continuation *still holds* the ordinary mutex; it does not allow B.

Hence the prior source chain's critical interleaving was an invalid assumption, not a production wrong effect. Treat the earlier OPEN as independently **REJECTED candidate**, not CLOSED source defect or sibling/alias of a real defect. The immutable prior checkpoint remains historical evidence and is not edited. This does not prove all session/recovery operations correct: separate restart/Restore gaps are being inspected in L1. New evidence would need a concrete path bypassing the global admission mutex, not mere `ConcurrentHashMap.putIfAbsent` placement.

This intermediate checkpoint is the canonical semantic correction for this root candidate. Derived inventory/index are to be reconciled immediately on their next guarded writes; no implementation patch, new prompt, future assignment, or device run is authorized by this manual review.
