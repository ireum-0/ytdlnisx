# Manual v7 L5 platform contract review — FINAL

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 2c1bec3578ffca771cd0ed7fb425819eb8a2e881
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
review_parent_sha: 2c1bec3578ffca771cd0ed7fb425819eb8a2e881
plan_tip_pinned: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip_pinned: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
master_plan_sha256: 7f3a554a87eae50368edaf0a35f0fcb5d4b85aaea532bb818c90dbc45d90c5fa
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
protocol_blob_pinned: 7c42f1e3b1428216010bbcf46f175d9b2b4c2af2

overall_verdict: NOT_CLEAN
canonical_p0: 0
canonical_p1: 1
canonical_p2: 15
new_finding_ids: BUG-STORAGE-ALLFILES-01
reopened_finding_ids: WORKER-FOREGROUND-COMPLETION-01
independent_execution: NOT_EXECUTED

lens_coverage_current_sha:
- L1: BASELINE_FAIL
- L2: BASELINE_FAIL
- L3: BASELINE_FAIL
- L4: BASELINE_FAIL
- L5: DEEP_FAIL
- L6: DEEP_FAIL
primary_deep_lens: L5_PLATFORM_CONTRACT_CLOSURE
remaining_not_yet_deep: L1,L2,L3,L4
next_not_yet_deep_lens: L1_DURABILITY_AND_RECOVERY

## Findings

WORKER-FOREGROUND-COMPLETION-01 is REOPENED P2.
CleanUpLeftoverDownloads and MoveCacheFilesWorker still issue foreground-establishment asynchronously,
discard completion, and proceed into destructive/filesystem effects. UpdateMultipleDownloadsFormatsWorker
has the same expression; its per-item outcome remains separately owned by BUG-FORMAT-BG-03.
Required closure: foreground establishment must complete successfully before the first correctness-relevant
effect; failure/cancellation must be classified explicitly; production tests must prove no effect occurs
after foreground establishment failure.

BUG-STORAGE-ALLFILES-01 is NEW P2.
The API-30+ settings path exposes special shared-storage access, checks Environment.isExternalStorageManager,
and enables direct/no-cache settings only when that capability is true. Exact repository search finds no
corresponding manifest declaration. Current Android platform documentation requires that package declaration
before this special access can be granted. Required closure: either support the capability through a
policy-compatible package/runtime contract, or remove/replace the impossible grant UI and keep storage
behavior truthful and fail-closed.

Existing fourteen roots remain open. The current scheduler execution-owner release race remains a
same-root residual of BUG-SCHEDULER-WINDOW-01 and remains the next implementation priority.

## Trigger disposition

WorkManager/foreground completion: FAIL.
Scheduler AlarmManager/receiver/handoff: FAIL_EXISTING_ROOTS; receiver lifetime itself has safe goAsync
and durable handoff controls.
Notification cancellation: FAIL_EXISTING_BG05; no additional independent root established.
DownloadManager completion: FAIL_EXISTING_APP_UPDATE_02.
Storage platform capability: FAIL_NEW_ROOT.
Manifest internal receiver export review: PASS_NO_NEW_ROOT.
Other raw WorkManager cancellation candidates: NOT_VERIFIED as independent roots; exact downstream
execution/recovery guards prevent unsupported duplicate counting.

## Review retrospective

Prior L6 DEEP coverage did not substitute for L5 platform-contract closure. L5 found one historical
cross-worker asynchronous completion root still present and one package/runtime capability mismatch.
Neighboring safe controls using suspending foreground establishment, goAsync/finally, awaited WorkManager
cancellation and durable handoff make the foreground conclusion source-semantic rather than speculative.

## Checklist evolution

checklist_change_required: NO
Existing v7 async-completion, platform-contract, producer/consumer/final-effect and manifest/capability
rules already cover both findings. The issue was review depth, not missing governance.

## Final state

current_sha_deep_lenses: L5,L6
remaining_not_yet_deep: L1,L2,L3,L4
next_hint: L1_DURABILITY_AND_RECOVERY
current_next_implementation_priority: UNCHANGED_SCHEDULER_EXECUTION_OWNER_RELEASE_RACE
private_handoff_movement: COMPATIBLE_NEXT_CHAT_ONLY
protocol_changed_during_run: NO
implementation_moved_during_run: NO
