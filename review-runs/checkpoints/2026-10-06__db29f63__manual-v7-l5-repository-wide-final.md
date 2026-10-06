# Manual correctness review — db29f63 — repository-wide L5 platform contract closure

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 15a6d6287413be4184382e0c7241ea850f3eaa1c
review_parent_sha: 353ed4f735ea845502ec5f89142cad1de719f4e3
intervening_review_reconciliation: FORWARD_COMPATIBLE_SCHEDULER_EXECUTION_STOP_REVIEWS_NO_SOURCE_CHANGE

implementation_sha: db29f63ce169176b4c8ade4cec01f66cc0307ec8
implementation_parent_sha: adf2f347ce9e20ec9f9376cf94053694353c9961
implementation_delta: ZERO_FILES_BASELINE_MARKER
source_tree_relation: IDENTICAL_TO_VERIFIED_ADF2F347

master_plan_commit: fada33a7eed86b1fa2c07065af66f14bf4d24714
master_plan_sha256: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
plan_head_observed: 8491528b730abea17de22013bca4288e1549a39e
ledger_reference: 899328bc91e4008e39a658387396a0106c8666ec
ledger_head_observed: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d

overall_verdict: NOT_CLEAN
canonical_p0: 0
canonical_p1: 1
canonical_p2: 10
canonical_open_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-FORMAT-BG-04,BUG-FORMAT-BG-05,BUG-INCOGNITO-01,BUG-APP-UPDATE-01,BUG-APP-UPDATE-02
new_finding_ids: BUG-APP-UPDATE-02
independent_execution: NOT_EXECUTED

active_download_blockers: BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-FORMAT-BG-04,BUG-FORMAT-BG-05,BUG-INCOGNITO-01
deferred_non_download_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-APP-UPDATE-01,BUG-APP-UPDATE-02
deferred_non_download_policy: RECORDED_NOT_REQUIRED_BEFORE_PO_TOKEN
po_token_resume_condition: ALL_ACTIVE_DOWNLOAD_BLOCKERS_CLOSED

handoff_update_for_new_finding: INTENTIONALLY_NOT_PERFORMED
handoff_policy_source: USER_INSTRUCTION_2026_10_06
handoff_effect: NEW_FINDING_RECORDED_IN_REVIEW_CHECKPOINT_ONLY

## Independent verdict

db29f63 remains NOT_CLEAN.

This same-SHA manual run recomputed the full L1-L6 baseline and promoted L5 Platform contract closure to DEEP.

During the run, review/remediation advanced normally from 15a6d6 to 353ed4 by scheduler implementation-stop/reconciliation checkpoints only. The implementation branch remained exactly db29f63. Those checkpoints concern storage/instrumentation launch recovery for the preserved local BUG-SCHEDULER-WINDOW-01 candidate; no remote source change occurred.

One new deferred non-download P2 root is established:
BUG-APP-UPDATE-02 — app-update DownloadManager completion authority is neither bound to the exact request id nor durably recoverable across observer loss.

Per current user instruction, this finding is recorded here only. NEXT_CHAT.md / active handoff is not modified merely because this new root was discovered.

## L1-L6 baseline

lens_coverage_current_sha:
- L1: DEEP_FAIL
- L2: DEEP_FAIL
- L3: DEEP_FAIL
- L4: BASELINE_PASS
- L5: DEEP_FAIL
- L6: DEEP_FAIL

primary_deep_lens: L5 Platform contract closure
primary_deep_selection_reason: R1 — BUG-APP-UPDATE-01 directly owns a platform/representation contract failure; Android WorkManager foreground, DownloadManager completion, exact-alarm, shared-storage, URI and installer boundaries were rechecked.
remaining_not_yet_deep: L4
next_not_yet_deep_lens: L4 Destructive ownership
next_lens_selection_reason: R5 — all other lenses are now DEEP on this SHA; L4 remains BASELINE_PASS and is the final not-yet-DEEP lens.

L1 DEEP_FAIL:
- existing durability roots remain open;
- BUG-APP-UPDATE-02 adds a non-durable transient completion observer for app-update installation continuation.

L2 DEEP_FAIL:
- prior identity roots remain;
- BUG-APP-UPDATE-02 ignores the exact DownloadManager request id returned by enqueue.

L3 DEEP_FAIL:
- prior concurrency/authority roots remain;
- BUG-APP-UPDATE-02 allows an unrelated DownloadManager completion broadcast to consume/unregister the updater observer.

L4 BASELINE_PASS:
- no new destructive-ownership root found;
- History duplicate relationship transfer remains under shared relationship lock + Room transaction with final identity recheck.

L5 DEEP_FAIL:
- BUG-APP-UPDATE-01 version representation remains open;
- BUG-APP-UPDATE-02 exact DownloadManager completion/observer contract is newly established;
- foreground-service dataSync manifest permission and WorkManager SystemForegroundService type are present;
- shared-storage publication uses MediaStore/SAF fallback when direct raw write is unavailable;
- no separate scoped-storage or installer URI root was established.

L6 DEEP_FAIL:
- prior download propagation roots remain;
- app-update findings propagate from GitHub release selection through DownloadManager completion and installer invocation.

## L5 deep review — Android foreground/work contracts

Manifest/source agreement:
- android.permission.FOREGROUND_SERVICE is declared;
- android.permission.FOREGROUND_SERVICE_DATA_SYNC is declared;
- androidx.work.impl.foreground.SystemForegroundService is declared with foregroundServiceType=dataSync;
- download/observe/background workers request FOREGROUND_SERVICE_TYPE_DATA_SYNC.

No new manifest/type root is established.

Refinement to BUG-FORMAT-BG-03:
UpdateMultipleDownloadsFormatsWorker calls setForegroundAsync(...) and discards its future, unlike workers using the suspend setForegroundSafely() boundary.

Therefore a platform rejection of foreground promotion is not incorporated into the worker's truthful Result path before external fetch/publication begins. This strengthens the existing false-success/error/cancellation ownership defect but does not justify a separate semantic root: the same correction boundary must make worker lifecycle/platform failures observable and prevent success/completion publication after a failed authoritative execution path.

No new root counted for this refinement.

## L5 deep review — shared storage/publication

Default audio/video/command paths still point to public Downloads raw paths, but FileUtil.moveFile() does not rely on legacy raw write permission when that destination is not directly writable.

For primary shared storage it attempts MediaStore publication with:
- exact ContentResolver.insert URI;
- RELATIVE_PATH;
- IS_PENDING;
- exact publication reservation/commit callbacks.

SAF publication retains exact created document URIs and performs stream copy on Dispatchers.IO.

Therefore the presence of Environment.getExternalStoragePublicDirectory by itself does not establish a target-36 scoped-storage blocker for ordinary download publication.

Candidate rejected:
"moveFile performs provider copy on Dispatchers.Main."
The outer decision block uses Dispatchers.Main, but both MediaStore and SAF copy implementations switch to Dispatchers.IO. No long provider-copy main-thread final effect was established.

## L5 deep review — BUG-APP-UPDATE-01

UpdateUtil.tagNameToVersionNumber():
- strips "-beta";
- removes dots;
- padEnd(10, '0');
- converts the flattened decimal text to Int.

This is not a component-wise version order.

Example:
- 1.10.0 -> "1100..." numeric projection;
- 1.9.0 -> "190..." numeric projection;
so 1.9.0 can compare greater than 1.10.0.

The projection controls tryGetNewVersion() selection for stable/beta update decisions. Therefore a valid newer release can be missed or an older ordering can be treated as newer depending on component widths.

No new root; BUG-APP-UPDATE-01 remains OPEN_P2 and deferred from PO-token gating.

Required eventual correction:
- parse/version-compare components explicitly;
- define beta/prerelease ordering;
- reject or safely handle malformed tags;
- test unequal component widths and beta/stable crossings.

## New root — BUG-APP-UPDATE-02

severity: P2
status: OPEN_P2_DEFERRED_NOT_BLOCKING_PO_TOKEN
root: APP_UPDATE_DOWNLOAD_COMPLETION_NOT_BOUND_TO_EXACT_DOWNLOADMANAGER_REQUEST_AND_NOT_DURABLY_RECOVERABLE

Production chain:
UiUtil.showNewAppUpdateDialog
-> choose ABI asset
-> val downloadID = DownloadManager.enqueue(request)
-> construct dynamic BroadcastReceiver
-> register receiver for DownloadManager.ACTION_DOWNLOAD_COMPLETE
-> on any matching action:
   unregisterReceiver(this)
   open the expected Downloads/<release asset name> path.

Exact authority failure:
- the returned downloadID is never read again;
- receiver does not inspect intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,...);
- receiver does not query DownloadManager status for the expected id;
- first ACTION_DOWNLOAD_COMPLETE delivered while registered is treated as the update APK completion.

This makes the observer unable to prove the broadcast belongs to the updater request.

Final effects:
1. an unrelated DownloadManager completion can arrive first;
2. updater receiver unregisters itself;
3. it immediately attempts to open the expected APK path even if the update request is still running, failed, or absent;
4. when the real update request later completes, no receiver remains to continue installation.

A second same-root durability gap exists:
- enqueue occurs before receiver registration;
- the completion continuation exists only in an Activity-owned dynamic receiver;
- process/activity loss removes the observer while DownloadManager remains durable;
- no persisted request id or later status reconciliation recovers a completed update.

These are one root because both arise from the same missing durable exact DownloadManager request carrier/completion authority.

Required eventual correction:
- retain exact enqueue request id as the update operation identity;
- observe/handle only ACTION_DOWNLOAD_COMPLETE for that id;
- query DownloadManager status and localUri rather than reconstructing success from filename alone;
- recover across Activity/process recreation from durable request state or an equivalent persistent completion owner;
- do not unregister/consume the expected continuation on unrelated broadcasts;
- distinguish SUCCESSFUL, FAILED, RUNNING/PENDING/PAUSED;
- installer/open action must use the exact completed artifact identity;
- tests for unrelated-first completion, expected failure, process/recreation recovery, duplicate/stale completion, exact successful completion.

No focused tests for this boundary were found.

## Installer URI boundary

FileUtil.openFileIntent():
- converts raw files to MediaStore/FileProvider-compatible content URI when possible;
- uses ACTION_VIEW with resolved MIME;
- grants FLAG_GRANT_READ_URI_PERMISSION;
- catches ActivityNotFoundException/SecurityException/IllegalArgumentException.

No separate FileUriExposed/URI-grant root was established.

The installer continuation is still affected by BUG-APP-UPDATE-02 because it can be invoked for the wrong or not-yet-complete DownloadManager event.

## L4 baseline recheck

History duplicate cleanup:
- rereads duplicate identities under HistoryReferenceMutationCoordinator lock;
- copies playlist memberships to retained History;
- copies assignments/materializes;
- deletes duplicate relationships;
- rechecks retained and duplicate identity immediately before deletion;
- deletes History within the same Room transaction.

No new L4 blocker established in this run.

## Existing download roots continuity

BUG-SCHEDULER-WINDOW-01:
- remains OPEN_P2 on remote db29f63;
- local four-file candidate remains uncommitted/not GitHub-authoritative;
- later scheduler checkpoints report storage recovery, successful APK install, but instrumentation execution still blocked at host process launch;
- no source-semantic change in this manual run.

BUG-FORMAT-BG-01/02/04/05:
- remain OPEN_P2 unchanged.

BUG-FORMAT-BG-03:
- remains OPEN_P2;
- L5 refinement: ignored setForegroundAsync future means foreground promotion failure is outside truthful result accounting.

BUG-INCOGNITO-01:
- remains OPEN_P2 unchanged.

Active download blocker set remains seven.

## Verification evidence

Independent execution: NOT_EXECUTED.

Repository search found no focused tests for:
- app version component ordering;
- tryGetNewVersion release selection;
- app-update DownloadManager request-id matching;
- unrelated ACTION_DOWNLOAD_COMPLETE handling;
- process/recreation recovery of an app-update request;
- format worker foreground-promotion failure.

Scheduler execution evidence belongs to separate stop-review checkpoints and is not upgraded here into source closure.

## Final recount

canonical_p0: 0
canonical_p1: 1
canonical_p2: 10

canonical_open_roots:
- CURRENT-PLAYER-TIMELINE-INDEX
- BUG-PLAYER-01
- BUG-SCHEDULER-WINDOW-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-INCOGNITO-01
- BUG-APP-UPDATE-01
- BUG-APP-UPDATE-02

active_download_blockers:
- BUG-SCHEDULER-WINDOW-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-INCOGNITO-01

deferred_non_download_roots:
- CURRENT-PLAYER-TIMELINE-INDEX
- BUG-PLAYER-01
- BUG-APP-UPDATE-01
- BUG-APP-UPDATE-02

manual review status: FINAL
current SHA DEEP lenses: L1,L2,L3,L5,L6
remaining not-yet-DEEP: L4
next hint: L4 Destructive ownership

Per user instruction, no NEXT_CHAT.md or active handoff mutation is performed for BUG-APP-UPDATE-02 in this review.
