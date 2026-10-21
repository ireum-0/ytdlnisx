# Manual '3' correctness review: L2 original/successor provenance (IN_PROGRESS)

date: 2026-10-21
checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: IN_PROGRESS
manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: ab99750127b401168fb55b4c6f007e5930d1ee3f
review_expected_parent: ab99750127b401168fb55b4c6f007e5930d1ee3f
implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_master_plan_manifest_blob: 065febdfdccbff12cc6a10a3f55db9bb160293cf
pinned_master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
pinned_master_plan_manifest_sha256: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
pinned_master_plan_parts_read: 13_PARTS_AT_ONE_SHA_BLOB_IDENTITIES_MATCH_MANIFEST
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
pinned_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
pinned_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
pinned_lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
pinned_lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
pinned_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
current_canonical_review_tip_at_start: ab99750127b401168fb55b4c6f007e5930d1ee3f
wp0b_canonical_checkpoint: review-runs/checkpoints/2026-10-21__141b519__wp0-b-replacement-pre-admission-destruction-independent-source-final.md
wp0b_canonical_checkpoint_blob: 4deda55b0a9a37f375f54849b58aa22f37b3b19b
active_inventory_blob: 8858181ee9e706796fd6dc1e4e3370428031880c
repository_index_blob: db002294caa133314621115fbea74af989af3f4f
wp1_04_task_status: CONSUMED_STOP_MATCHING_INTENT_CLAIM_AND_FULL_REPORT
implementation_agent_active: NO_COMPLETION_STOP_OBSERVED
review_mode: SAME_SHA_NEW_MANUAL_L2_ATTEMPT_PRIOR_WRITE_REFUSED_WITH_NO_DURABLE_RUN
primary_deep_lens: L2_IDENTITY_AND_PROVENANCE_IN_PROGRESS
primary_deep_selection_reason: R1_CURRENT_ROOT_ORIGINAL_VS_NEW_PROCESSING_PROVENANCE;R2_PREQUEUE_DESTRUCTIVE_TRIGGER
lens_coverage_current_sha: L1=DEEP_FAIL_PRIOR_FINAL;L2=IN_PROGRESS_NOT_YET_DEEP;L3=DEEP_FAIL_PRIOR_FINAL;L4=BASELINE_FAIL;L5=BASELINE_FAIL_PLATFORM_UNVERIFIED;L6=BASELINE_FAIL
remaining_not_yet_deep: L2,L4,L5,L6
next_not_yet_deep_lens: L4_PROVISIONAL_AFTER_L2_FINAL
next_lens_selection_reason: R1_L4_DESTRUCTIVE_OWNER_FOLLOWUP_SUBJECT_TO_NEW_EVIDENCE_AND_R1_R5
run_completed_scope: CURRENT_FIVE_REF_PIN;WP1_04_CONSUMED_REPORT_CHECK;WP0B_FINAL_AND_CURRENT_ROOT_INVENTORY_RECONCILIATION;MASTER_PLAN_13_PART_BLOB_READ;CHECKLIST_LENS_POLICY_READ;REAL_SAVED_ERRORED_CANCELLED_PREQUEUE_DELETION_SOURCE_PATH;HISTORICAL_ALIAS_AND_CLOSED_ROOT_COMPARISON
run_remaining_scope: REPEAT_FULL_CHECKLIST_BASELINE_L1_L6;FOUR_AMBIGUOUS_EXACT_EXECUTION_EVIDENCE_FIRST;COMPLETE_L2_PROVENANCE_ACROSS_QUEUE_SCHEDULE_SAVE_RETRY_RESTORE_AND_OLD_ROOT;TRIGGERED_MODULE_AND_CROSS_FEATURE_CLOSURE;REGRESSION_FENCES;FRESH_IMPLEMENTATION_AND_REVIEW_HEAD;FINAL_CHECKPOINT
current_active_scope: DOWNLOAD_ACTIVE_BLOCKER_CLOSURE
active_p0: 0
active_p1: 0
active_p2_unresolved: 8
active_p2_open: 4
active_p2_ambiguous: 4
active_p2_closed: 2
active_real_root_count: 10
repository_roots_total: 152
repository_open: 53
repository_ambiguous: 4
repository_closed: 95
repository_unresolved: 57
root_count_delta_this_manual_checkpoint: 0
independent_execution: NONE
implementation_or_device_modified: NO
private_handoff_or_prompt_modified: NO
canonical_clean: NO

## Independent verdict and correction of earlier failed-to-persist draft

NOT_CLEAN; this checkpoint is IN_PROGRESS, not an independent manual FINAL. The prior attempted manual L2 create was rejected and its path did not exist on reread. Since that failed attempt, compatible normal-forward review work completed WP0-B source proof and formally adopted one DISTINCT OPEN P2 root. Earlier draft wording "root classification pending" and seven active unresolved roots is obsolete. Do not file a duplicate second root or repeat that historical count as live state.

Current accepted source finding is BUG-RECONFIGURE-PREADMISSION-DELETE-01 (OPEN) from the immutable WP0-B FINAL. Its old-ID/ABA subcase belongs to existing BUG-DOWNLOAD-DELETE-SNAPSHOT-01 OPEN (not counted again); notification-bundle sibling BUG-RECONFIGURE-01 remains CLOSED. BUG-INCOGNITO-01 remains separately OPEN. No current changes to any historical closure or inventorial disposition are made in this manual checkpoint. The four preexisting AMBIGUOUS roots remain AMBIGUOUS pending their named final-SHA Android/Room/notification execution evidence, not silently promoted to OPEN or CLOSED.

## Current source evidence: distinct original A and Processing P

SavedDownloadsFragment.kt:331-338; ErroredDownloadsFragment.kt:346-353; CancelledDownloadsFragment.kt:325-332 take selected original numeric IDs, launch the Processing-conversion task separately, then navigate to DownloadMultipleBottomSheetDialog with a Bundle of only currentDownloadIDs. DownloadViewModel.kt:1270-1311,1314-1371, with deleteExisting=false, reads an existing original, sets id=0 and Processing before repository update/insert; original and newly allocated Processing have different row identities. Neither the Bundle nor a newly created P supplies A's observation-time DOWNLOAD revision/incarnation or original-to-candidate proof.

DownloadMultipleBottomSheetDialog.kt:266-292 deletes originals first and only then performs scheduled admission; lines 298-315 delete originals first, then call checkProcessingDuplicates and return without queueing on a positive duplicate result. The latter invokes DownloadViewModel.kt:2124-2127 and real duplicate policy that may classify active/queued, existing History or archive items. This incorrect effect requires no privacy toggle or hypothesized thread interleaving.

DownloadViewModel.kt:1513-1516 invokes repository delete, ledger refresh and membership notification cancellation. DownloadRepository.kt:3284-3286 reloads numeric IDs then calls deleteKnownUserRemoval(items) without expectedStatus. Lines 4244-4282 transactionally terminalize linked children, remove History barriers and rows, then release pending Undo tokens and delete only provably owned cache. These effects precede successor admission and have no encompassing rollback if duplicate/scheduling/queue refusal follows; the cache ownership guard prevents an unsupported claim of arbitrary unowned-file deletion.

The old BUG-DOWNLOAD-DELETE-SNAPSHOT-01 targets stale status/generation in status-sweep cleanup and is also responsible for stale-original numeric-ID ABA. The independent new WP0-B root is *premature original removal before a different successor has been accepted*, possible with stable current original identity. Earlier BUG-RECONFIGURE-01 is the deleteExisting=true notification sibling/global Processing cleanup, not the current Saved/Errored/Cancelled deleteExisting=false predecessor chain. Do not reopen this previously CLOSED root on name resemblance. WP0-B already reconciled the root arithmetic in canonical inventories.

## L1-L6 provisional baseline and mandatory trigger map

L1 durability/recovery: original Room deletion, linked barrier/ledger and postcommit Undo/cache/notification precede different queue/scheduler durable acceptance; failure/process-death convergence is not proven. Existing L1 DEEP_FAIL remains previous evidence, not a new DEEP run.

L2 identity/provenance (PRIMARY IN_PROGRESS): original A, new P and original Bundle<LongArray> are separate identity/authority domains; no original generation carrier. Study exact A->P ownership mapping and legacy/Restore collisions before L2 final.

L3 concurrency/authority: independent child launches and mutable Selection/Processing paths admit ordering gaps, but the sequential duplicate refusal proof is sufficient on its own; do not invent a racing order. Previous L3 DEEP_FAIL retains its own historical basis.

L4 destructive ownership: prequeue original row/linked-state deletion commits before successor win, and current status reread under ID-only authority does not restore source-intent authority. L4 remains BASELINE_FAIL, not falsely promoted to DEEP by this module trace.

L5 platform contracts: WorkManager/scheduler acceptance, delivered notification and exact Room65/old-schema execution remain outside reviewer-authorized device effects; no executable final-X evidence is claimed.

L6 cross-feature propagation: Save-for-later, duplicate/archive refusal, Undo, History, retry, format selection, scheduler and Restore share or consume the changed queue/cleanup contract and require distinct effect-class verification.

Triggered Module B (WorkManager/scheduler acceptance): OPEN_GAP; Module F (Room/old generation/cross-ledger recovery): OPEN_GAP; destructive current owner/final-effect and consumer closure: OPEN_GAP. Triggered-module work does not mark L4/L5/L6 DEEP.

## Precise remaining checks and non-actions

The next same-pinned-run work must first attempt the four AMBIGUOUS named execution gates: BG04 changed-source Room65 trigger/owner, BG05 delivered PendingIntent cancellation, FormatAuthority SQLite triggers and null-chapter converter on the exact source; V39 earlier SHA PASS is not current execution. Under existing no-device authorization, preserve the specific execution blocker for each rather than guess PASS. Then complete L2 consumer/migration/Restore/old-root provenance and triggered persistence/cleanup/WorkManager recovery, check ordinary explicit Delete and save-for-later meanings, complete the five-callsite queue authority matrix, and record independent FINAL only after a fresh ref check.

Review does not change implementation, Master Plan, ledger, private handoff, device, prior immutable evidence or any prompt/agent lifecycle. WP1_04 has matched intent/claim and immutable STOP Full Report; its stale PERSISTED_NOT_STARTED field is not permission to restart or emit a bootstrap. Future source correction of WP0-B/BUG-INCOGNITO-01 may exceed WP1_04 authorization and requires a separately approved scope; this checkpoint authorizes no task.
