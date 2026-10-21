# WP0-B — multi-replacement pre-admission original deletion — independent source review FINAL

checkpoint_kind: INDEPENDENT_SOURCE_SEMANTIC_AND_ROOT_RECONCILIATION
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: 646cd5ffdc36bf11a613cfabed2a744cac7dc758
implementation_reviewed_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
governing_lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
wp1_04_full_report_private_commit: aeaf77924db0509f2969bf5cc49f7a1bfefd2362
wp1_04_full_report_blob: 6c3e959a82f93f67fe419fc581a540a5b84194aa
prior_wp1_04_independent_stop_checkpoint: review-runs/checkpoints/2026-10-21__141b519__wp1-04-prequeue-replacement-delete-stop-independent-final.md
prior_wp1_04_independent_stop_checkpoint_blob: 9ec2af047dff6f5a1965288750854d627ef0762d
product_contract_accepted_wp0a_blob: 59e085fcea8cc0e53dd2356e3f8e79333a88f3ee
review_verdict: SOURCE_PROVEN_NOT_CLEAN_WP0B_CONTRACT_BLOCKED_NO_AUTHORIZED_CORRECTION
new_current_semantic_root: BUG-RECONFIGURE-PREADMISSION-DELETE-01
new_root_current_disposition: OPEN
new_root_severity: P2
new_root_scope_adoption: YES_DOWNLOAD_ACTIVE_BLOCKER_CLOSURE_DEPENDENCY_OF_WP1
new_semantic_root_delta: PLUS_ONE
existing_snapshot_deletion_root: BUG-DOWNLOAD-DELETE-SNAPSHOT-01_OPEN_UNCHANGED_SAME_ROOT_STALE_ID_SUBCASE
existing_notification_reconfigure_root: BUG-RECONFIGURE-01_CLOSED_UNCHANGED_DISTINCT_ORIGINAL_PRODUCER
existing_duplicate_admission_root: BUG-DUPLICATE-ADMISSION-01_CLOSED_UNCHANGED_DISTINCT_ORIGINAL_OWNER_LIFECYCLE
existing_incognito_root: BUG-INCOGNITO-01_OPEN_UNCHANGED_DISTINCT_QUEUE_PRIVACY_CONTRACT
canonical_active_p0: 0
canonical_active_p1: 0
canonical_active_open_p2: 4
canonical_active_ambiguous_p2: 4
canonical_active_closed_p2: 2
canonical_active_real_p2_roots: 10
canonical_active_unresolved_p2: 8
repository_discovered_roots: 152
repository_open_roots: 53
repository_ambiguous_roots: 4
repository_closed_roots: 95
repository_unresolved_roots: 57
canonical_clean: NO
reviewer_test_execution: NONE
local_draft_inspection: GITHUB_ARCHIVED_FULL_REPORT_ONLY_NOT_LIVE_FILES
source_mutation: NONE
new_prompt_or_agent_launch_authorized_by_this_review: NO
private_handoff_modified: NO
governing_plan_or_ledger_modified: NO

## 1. What was independently verified

The exact immutable implementation `141b5199592369ab953f52c55ebdb72ba300f1a4` and the WP1-04 STOP Full Report were freshly re-read at their stable GitHub identities. The Full Report is archived with matching task intent+claim+terminal STOP; the implementation tip is unchanged. Its eight-file local draft remains an **agent-reported uncompiled, untested, uncommitted** artifact, not an independently reviewed implementation candidate. No locally protected Windows state, Android, device, APK, Gradle/JVM runner, or compiled code was accessed by the reviewer. The earlier independent WP1-04 STOP checkpoint is compatible and remains historical evidence. The prior `NEXT_CHAT.md` launch-ready fields still identify a **consumed** WP1-04 and may not be used to bootstrap it.

Source-proven exact producer/caller chain:
1. `SavedDownloadsFragment.kt:331-338`, `ErroredDownloadsFragment.kt:346-353`, and `CancelledDownloadsFragment.kt:325-331` asynchronously invoke `turnDownloadItemsToProcessingDownloads(selectedObjects)`, then navigate using `currentDownloadIDs` consisting **only of old numeric original IDs**. Candidate construction and navigation are concurrent independent coroutines, not one completion barrier. The UI does not convey original revision/incarnation or a proven old-original-to-new-Processing relationship.
2. `DownloadViewModel.kt:1270-1377` with default `deleteExisting=false` deletes earlier Processing state, reads each original, sets `item.id=0`, sets Processing, then inserts/updates independent candidate rows; it can stop/fail partway. This is NOT the previously reviewed `deleteExisting=true` notification bundle, where the original row ID is intentionally retained.
3. `DownloadMultipleBottomSheetDialog.kt:141` reads that old-ID array. The schedule button around 271 calls `deleteAllWithID(currentDownloadIDs)` **before** schedule/queue; the ordinary download click around 304 calls the same deletion **before** `checkProcessingDuplicates` and `queueProcessingDownloads`; if the duplicate list is nonempty, the callback around 306–313 handles duplicates and returns **without queue admission**. The save-for-later branch around 347–348 performs a separate `moveProcessingToSavedCategory` then the same deletion, with no verified owner-bound all-or-nothing acceptance. `onDismiss` around 1339 subsequently deletes Processing candidates; it does not restore the originals.
4. The duplicate path is concretely reachable even without a privacy toggle: `DownloadViewModel.kt:2124-2127` calls `detectAndMarkDuplicates`; at the code around 2590–2747 its URL/type, CONFIG, History or archive policies can append `AlreadyExistsIDs` and mark the Processing candidate Duplicate. With nonempty duplicate return, the dialog takes its early-return path **after the original-removal write**. The schedule route separately has failure/refusal (including no alarm permission, source status/recovery, duplicate, and conditional admission) after original removal. This is a source-path proof, not a claim of a reproduced Android test or that all modes always fail.
5. `DownloadViewModel.kt:1513–1516` forwards `deleteAllWithID` to repository and then refreshes low-quality ledger and cancels membership notifications. `DownloadRepository.kt:3284–3286` reloads current numeric IDs and calls `deleteKnownUserRemoval(..., expectedStatus=null)`. `DownloadRepository.kt:4244–4282` uses these rows directly within an independent Room deletion transaction, terminalizes linked children, deletes history-replacement barriers and rows, then releases process-local Undo tokens and invokes cache deletion of **owned** cache via `DownloadCacheOwnership.deleteIfOwned`. The owned-file guard is an important limit: arbitrary nonowned data deletion is NOT inferred. No queue-admission rollback encompasses these committed original-row, linked-state, Undo and owned-cache effects.
6. `DownloadViewModel.queueDownloads` still checks recovery/retry/barriers, duplicate/archive and hydration, schedules or starts WorkManager after a separate queued-row publication; its `updateForQueueIfSnapshot` does not authorize deletion of different old originals. An old original A stamp cannot be synthesized from newly inserted Processing candidate P. Reusing an ID across delete/recreate/Restore exposes a second stale-ID destruction hazard, but that hazard is already an alias/subcase of the pre-existing OPEN `BUG-DOWNLOAD-DELETE-SNAPSHOT-01`, not another new root.

**Concrete wrong effect without an invented race:** Original Saved/Error/Cancelled A is selected for multi-item reconfiguration. A new Processing P is created. User presses Download, and `deleteAllWithID([A.id])` commits deletion/linked-effects. `checkProcessingDuplicates` finds a matching active/queued or History/CONFIG item, returns nonempty, so `queueProcessingDownloads` is not invoked for P. A and its linked durable ownership have already been removed despite refusal of the intended new queue action. The same class of mismatch may happen on the schedule path and under newer committed P privacy. This is not an assertion of observed device timing or a promise that stale A always maps to a new row.

## 2. Exact semantic root audit: ONE new cause, no double counting

The newly distinct source-level semantic cause is **unconditional destructive replacement-original removal before the replacement action has obtained an authoritative success/acceptance outcome**. It can fail with stable current IDs and no privacy change, no status-sweep trigger and no notification-bundle sibling failure. Treat as ONE new `BUG-RECONFIGURE-PREADMISSION-DELETE-01` P2 OPEN root, explicitly adopted in active download scope as a material correction dependency of WP1-04. Evidence is sufficient under protocol §9 producer→destructive write→refused queue consumer→lost original effect. The new root is not a new zero-test pass or a new implementation task.

Compared to closest historical contracts:
- `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` (OPEN) is status/discovery sweep's stale captured row generation used by `deleteKnownUserRemoval`. WP0-B **does** expose an adjacent ID-only original-target ABA/subcase of this existing root: count that destructive-identity defect there ONCE, not as a separate additional root.
- `BUG-RECONFIGURE-01` (P1 CLOSED) was specifically a **notification `deleteExisting=true` bundle**, where a stale sibling previously caused a global Processing delete of another surviving same-ID row; the October 7 independent audit closed that exact producer and exception path. WP0-B is a **manual multi-item Saved/Error/Cancelled `deleteExisting=false`** flow that deletes different original rows BEFORE queue acceptance, independent of sibling exception. Do not reopen or silently count the old closed root merely by word similarity.
- `BUG-DUPLICATE-ADMISSION-01` (CLOSED) requires atomic duplicate identity decision and new-row publication; it does NOT own deletion of prior originals from a separate user reconfiguration operation. This root's refusal is a trigger for the new destructive misordering, not proof the earlier duplicate-admission fix regressed.
- `BUG-INCOGNITO-01` (OPEN) concerns committed queue-time ON/OFF provenance and protected attempt/Processing selection; its proposed fail-closed publication requires preserving prior state, and the destructive pre-admission action obstructs that correction. The new cause occurs independently with ordinary duplicate settings; do not count two separate roots for its incognito-triggered and duplicate-triggered subcases.
- `BUG-RETRY-01` (OPEN), `BUG-LOWQUALITY-02` (OPEN), `BUG-QUEUE-04` (CLOSED) and Undo/cache ownership roots concern distinct retry-classification, ledger attempts, claimed-queued deletion, or other producers; preserve their separate current dispositions. A hypothetical cleanup-failure subcase with same destructive-original ordering is NOT separately added.

This root is P2 for current active download remediation scope with no evidence here for a new P0/P1 classification. Source-only proof supports OPEN; no runtime execution or platform contract closure is being claimed.

**Arithmetic after scope adoption:** previous nine real active roots (3 OPEN + 4 AMBIGUOUS + 2 CLOSED = 7 unresolved) + ONE genuinely distinct current `OPEN` P2 root = **ten real roots (4 OPEN + 4 AMBIGUOUS + 2 CLOSED = 8 unresolved)**. Repository previous 151 total (52 OPEN, 4 AMBIGUOUS, 95 CLOSED) becomes **152 (53 OPEN, 4 AMBIGUOUS, 95 CLOSED), 57 unresolved**. No previous root changes classification. V1.8 is immutable earlier advisory snapshot with seven unresolved, not an updated canonical verdict.

## 3. WP0-B safe operation contract and unresolved implementation scope

**Required user-facing result:** a failed, refused, cancelled, or unadmitted replacement queue/schedule action must not delete its original Saved/Error/Cancelled rows or the original linked ledger/barrier/Undo/owned-cache/notification owner. Successful queue/schedule replacement must preserve exactly one authorized successor with the explicit queue-time incognito ON/OFF frozen, no concurrently active original owner, no false success or inherited old-authority deletion. User dismiss/reconfigure, Save-for-later (nonqueue) and partially successful multi-batch actions need their own clearly scoped semantics, not a generic borrowed queue result.

**Producer capture and mapping:** at the actual Saved/Error/Cancelled selection observation, carry an original target's identity, DOWNLOAD revision/incarnation **and any database-root discriminator needed by old/new collision proof**, original status/operation/execution/Undo/recovery ownership and original→new candidate mapping to the eventual action. A `Bundle<LongArray>` alone is untrusted after coroutine suspension and Restore. Do not authorize old-original deletion by reading a newer source stamp from P, and do not retroactively baptize an old object with a current DB revision. Existing/legacy unversioned originals may be refused with truthful current-value refresh, but do not blanket-block legitimate multireconfigure without an explicit supported route for reentry.

**Admission and old/new atomicity:** pre-queue duplicate/archive/retry/History checks must be evidence, not an early irreversible write. Before any original deletion, guarantee original and candidate version/owner eligibility and transactionally couple the exact appropriate original terminalization with candidate Queued/Scheduled publication or use a fully proved durable split-phase owner protocol. Consider all-or-nothing per-candidate vs batch behavior explicitly; a single failed candidate may not erase other originals, and a partial admission cannot claim entire-batch success. Prevent both old and new from retaining conflicting active authority. No presumption that moving the deletion after WorkManager scheduling suffices: cleanup failure then creates competing owners. Do not broaden `deleteKnownUserRemoval(expectedStatus=null)` as a universal solution or disturb intentional explicit manual deletes.

**External effects and recovery:** `terminalizeLinkedChildren`, History-replacement barriers, pending Undo, cache ownership, membership notifications and WorkManager start/schedule all have different postcommit/runtime durability. Specify one actual owner for each postcommit cleanup, its durable discovery/wakeup and finite convergence on process death, cancellation, Restore and old supported Room versions. A Room transaction cannot roll back deleted owned cache files or released in-memory Undo capabilities. Existing `deleteCache` ownership guard must continue to protect unowned files. If no existing operation/cleanup carrier can prove these properties, a new schema/global deletion/Undo/recovery primitive is **outside already approved narrow WP1_04 scope** and requires a separate explicit user authorization before any implementation write; this review does not invent or preapprove that primitive.

**Minimum deterministic end-effect regression obligations:** (a) duplicate refusal after attempted replacement must preserve all original rows, linked lowquality state/barrier/Undo, owned caches and notifications, leave no runnable worker; (b) archive unavailable and History hydration/attempt admission refusal; (c) queue/schedule success with original correctly retired and one successor that uses queue-click ON/OFF through claim/History/log/fingerprint; (d) concurrent original delete/recreate/Restore, stale selection, old false→true→false privacy and candidate status advance; (e) partly prepared multi-member and Save-for-later semantics; (f) crash between Room original/successor transaction and cache/notification/Undo effects, with durable convergence or explicit fail-closed refusal and proven old-owner status; (g) unchanged notification `deleteExisting=true` sibling handling and ordinary explicit user delete, previous BG02/03, scheduler and known CLOSED roots. Test authorship and execution are NOT part of this read-only checkpoint; Android requires separate device permission and original 197 selector preservation.

## 4. Decision and next authority

**WP0-B review COMPLETE for source wrong-effect and root/alias scope.** It does **not** establish an implementation-ready, minimal owner/cleanup protocol. Correcting new distinct root and the related WP1 blocker might require original-target provenance changes in Saved/Errored/Cancelled producer files, deletion/Undo/linked-ledger owners, filesystem cleanup and durable convergence outside the old WP1_04 prompt's scope. Consequently **do not create or launch WP1_05 or repurpose/restart WP1_04** based on this review-only authorization. A separate owner request must first approve bounded replacement-operation design/source scope (and if necessary durable cleanup carrier/migration with old-state fixtures). Independently eligible existing BG01, source-less command and AMBIGUOUS verification work remain separate governed opportunities.

WP1_04 immutable intent, claim, report and 8-file unfinished draft remain protected evidence. Leave `NEXT_CHAT.md` launch control unchanged; matching same-instance STOP report overrides its stale `PERSISTED_NOT_STARTED`. Do not emit a canonical bootstrap. Source `141b5199592369ab953f52c55ebdb72ba300f1a4` is NOT_CLEAN, and no implementation publication, device, Kotlin compile, reviewer test, updated Master Plan or ledger occurred in this review. The following derived root inventories must be reconciled to the single new adopted root using fresh review HEAD checks and normal fast-forward only; older review/checkpoint evidence remains immutable.
