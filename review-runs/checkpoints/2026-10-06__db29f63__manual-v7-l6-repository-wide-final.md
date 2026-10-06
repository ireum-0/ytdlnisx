# Manual correctness review — db29f63 — repository-wide L6 cross-feature propagation

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 3e227b4c49fd36c554dd98f11935b680f7f5c253
review_parent_sha: 44c3ef8f07916a8189f614af4d2f9344fb7e9972
intervening_review_reconciliation: FORWARD_COMPATIBLE_IMPLEMENTATION_STOP_REVIEW_NO_SOURCE_CHANGE

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
canonical_p2: 9
canonical_open_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-FORMAT-BG-04,BUG-FORMAT-BG-05,BUG-INCOGNITO-01,BUG-APP-UPDATE-01
new_finding_ids: BUG-FORMAT-BG-05
independent_execution: NOT_EXECUTED

active_download_blockers: BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-FORMAT-BG-04,BUG-FORMAT-BG-05,BUG-INCOGNITO-01
deferred_non_download_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-APP-UPDATE-01
deferred_non_download_policy: RECORDED_NOT_REQUIRED_BEFORE_PO_TOKEN
po_token_resume_condition: ALL_ACTIVE_DOWNLOAD_BLOCKERS_CLOSED

scheduler_stop_review: review-runs/checkpoints/2026-10-06__db29f63__bug-scheduler-window-01-storage-stop-reviewed.md
scheduler_stop_review_tip: 44c3ef8f07916a8189f614af4d2f9344fb7e9972
scheduler_local_candidate: FOUR_UNSTAGED_FILES_EMPTY_INDEX_ZERO_COMMIT_ZERO_PUSH
scheduler_local_candidate_reviewed_here: NO
scheduler_device_gate: BLOCKED_BY_INSTALL_FAILED_INSUFFICIENT_STORAGE
scheduler_reported_focused_jvm: 20_OF_20_PASS
scheduler_next_prompt: ytdlnisx/prompts/2026-10-06_GPT61_SOL_BUG_SCHEDULER_WINDOW_01_STORAGE_RECOVERY_CONTINUATION.md

## Independent verdict

db29f63 remains NOT_CLEAN.

This same-SHA manual run recomputed the full L1-L6 baseline and promoted L6 Cross-feature semantic propagation to DEEP.

During the run, review/remediation advanced normally from 3e227 to 44c3 by one implementation-stop review only. The implementation branch remained exactly db29f63. That stop review records a preserved local four-file BUG-SCHEDULER-WINDOW-01 candidate and a device-install storage blocker; the uncommitted candidate is not GitHub-authoritative source and was not inspected or relied on in this manual review.

One new download-related P2 root is established:
BUG-FORMAT-BG-05 — the format progress notification's Cancel action is not bound to the exact WorkRequest identity and currently cancels nothing.

The active download blocker set therefore increases from six to seven.

The three non-download roots remain recorded but deferred and do not block the user's PO-token plan.

## L1-L6 baseline

lens_coverage_current_sha:
- L1: DEEP_FAIL
- L2: DEEP_FAIL
- L3: DEEP_FAIL
- L4: BASELINE_PASS
- L5: BASELINE_FAIL
- L6: DEEP_FAIL

primary_deep_lens: L6 Cross-feature semantic propagation
primary_deep_selection_reason: R1/R2 — active download roots cross Processing/Saved state, scheduler/WorkManager, worker publication, notifications, multi-download UI, incognito/History and observe-source consumers.
remaining_not_yet_deep: L4,L5
next_not_yet_deep_lens: L5 Platform contract closure
next_lens_selection_reason: R1 — BUG-APP-UPDATE-01 directly owns a remaining L5 representation/platform-contract failure; L4 has no direct open root after baseline recheck. The finding is deferred for implementation priority but remains an open review root.

L1 DEEP_FAIL:
- BUG-PLAYER-01 remains without ordered application-scoped persistence/recovery;
- BUG-FORMAT-BG-02 publishes Saved state before durable scheduler acceptance/recovery ownership;
- scheduler local candidate is uncommitted and does not alter remote L1 status.

L2 DEEP_FAIL:
- CURRENT-PLAYER-TIMELINE-INDEX, BUG-FORMAT-BG-01, BUG-FORMAT-BG-04 and BUG-INCOGNITO-01 remain exact identity/granularity failures;
- BUG-FORMAT-BG-05 adds an external cancellation identifier mismatch: notification identity is not the WorkManager cancellation identity.

L3 DEEP_FAIL:
- BUG-FORMAT-BG-02/04 remain scheduler/row-authority failures;
- BUG-FORMAT-BG-05 requests cancellation through a tag that the target WorkRequest does not own;
- player roots remain concurrency/authority failures.

L4 BASELINE_PASS:
- History duplicate playlist-membership union and final identity recheck remain transactionally ordered;
- format completion notification reopening uses a final Saved/unowned predicate plus exact row snapshot CAS and therefore does not independently revoke a queued/active owner.

L5 BASELINE_FAIL:
- BUG-APP-UPDATE-01 remains open/deferred;
- scheduler source remains open on the authoritative remote SHA until the preserved local correction is published and independently reviewed.

L6 DEEP_FAIL:
- scheduler-window semantics fan out to DownloadViewModel, DownloadWorker and ObserveSourceWorker;
- format-background semantics fan out from selection to bulk state transition, WorkManager handoff, external fetch, Download/Result publication, progress/cancel notification, completion notification and multi-download reopen UI;
- incognito aggregate semantics fan out from multi-selection UI to per-row incognito and final History persistence;
- one new format cancellation root was established at this consumer boundary.

## L6 deep review — scheduler propagation

BUG-SCHEDULER-WINDOW-01 remains one root.

AlarmScheduler.isDuringTheScheduledTime() is consumed by:
- DownloadViewModel queue admission;
- DownloadWorker scheduled execution/stop semantics;
- ObserveSourceWorker follow-up queue/start semantics.

The current authoritative remote implementation still misclassifies the post-midnight half of overnight ranges and still constructs the end Calendar without normalizing end seconds.

A further same-root boundary inconsistency is confirmed:
- the predicate compares only hour/minute and treats the configured end minute as inclusive;
- the AlarmManager end boundary is a timestamp with seconds/millis;
- therefore one correction must define one exact end-boundary contract and use it for both predicate and alarm publication rather than fixing only the 24-hour arithmetic.

The persisted scheduler correction contract already requires exact start/end boundary tests and deterministic seconds/millis normalization, so this L6 refinement does not require a new scheduler prompt.

The later storage-stop review does not change source semantics. It records reported 20/20 focused JVM PASS, compile/APK proof PASS, device installation blocked by insufficient storage, and no commit/push.

## L6 deep review — background format propagation

### BUG-FORMAT-BG-01

Selected format-update IDs are not the authority for the status mutation:
- caller captures all Processing ids;
- selected ids become worker ids;
- moveProcessingToSavedCategory() updates every current Processing row;
- nonselected rows are moved to Saved;
- a row entering Processing after the initial allProcessing snapshot can also be swept into Saved without appearing in ids or other_ids_in_bundle.

The completion notification later includes ids + other_ids_in_bundle and can reopen still-Saved/unowned rows. This downstream behavior does not cure the initial overbroad mutation; it propagates the same root into the completion UI.

### BUG-FORMAT-BG-02

The path publishes Saved state, then calls enqueueUniqueWork and ignores Operation.result.

WorkManagerHandoffRecovery elsewhere in the same repository explicitly models enqueue as request != acceptance and persists exact request identity/recovery debt. The format path bypasses that owner.

No durable carrier lets restart discover an exact format batch that was moved to Saved but never accepted by WorkManager.

### BUG-FORMAT-BG-03

UpdateMultipleDownloadsFormatsWorker:
- wraps per-item fetch + Result update + Download update in runCatching;
- ignores the result;
- increments progress regardless;
- can absorb CancellationException produced by a nested source;
- returns Result.success() when the loop reaches the end;
- emits the formats-updated notification while ids remains nonempty.

This propagates one item failure/cancellation into false batch success and completion UI semantics.

### BUG-FORMAT-BG-04

The worker captures a full DownloadItem before external fetch, later rereads only selected current authority fields, copies the current executionId/status/issues into the old object, and republishes the old full row.

The external fetch result therefore can overwrite newer Saved edits or newer queued/active configuration while borrowing the newer execution token.

The completion notification's reopen path does not repair this stale publication; it only operates after whatever full-row state the worker left behind.

### [P2] BUG-FORMAT-BG-05 — progress Cancel action has no exact WorkManager target

Production chain:
UpdateMultipleDownloadsFormatsWorker
-> NotificationUtil.updateFormatUpdateNotification(
     workID,
     UpdateMultipleDownloadsFormatsWorker::class.java.name,
     ...
   )
-> PendingIntent extra "workTag" = fully-qualified worker class name
-> CancelWorkReceiver
-> WorkManager.cancelAllWorkByTag(workTag).

But the actual WorkRequest is created with:
.addTag("updateFormats")

and a separate unique work name derived from the timestamp id.

Therefore the current Cancel action supplies a tag that the WorkRequest does not have. Pressing Cancel does not target the running format work.

This is not merely presentation:
- user cancellation intent never reaches the worker;
- the worker continues external fetch/publication;
- BUG-FORMAT-BG-03 then controls how any later stop/error would be represented;
- notification UI falsely presents an available cancellation action.

Simply changing the extra to "updateFormats" is not sufficient closure:
- multiple independently named format batches can exist;
- all share the "updateFormats" tag;
- cancelAllWorkByTag("updateFormats") would cancel every concurrent format batch, not the exact batch represented by the notification.

Required correction:
- carry the exact WorkRequest UUID or another exact one-request cancellation identity into the notification PendingIntent;
- CancelWorkReceiver must cancel that exact request, not a shared semantic tag;
- initial and updated progress notifications must preserve the same exact cancellation identity;
- cancellation must be observable by the worker under BUG-FORMAT-BG-03's truthful cancellation contract;
- concurrent batches A/B: cancel A must preserve B;
- stale notification for a completed/superseded request must not cancel a later request;
- process/lifecycle recreation must not reinterpret a reused notification request code as new work authority.

No focused JVM or instrumentation coverage for this notification-to-WorkManager cancellation route was found.

## L6 deep review — completion notification / reopen

Candidate: a stale formats-complete notification can reclassify a newer Active/Queued row back to Processing.

Rejected by current production proof:
- HomeFragment carries numeric ids only, but repository.transitionFormatNotificationCandidateToProcessing reloads the current row;
- it requires status=Saved, blank executionId, blank operationId, retryAttempt=0 and no live DownloadWorker owner;
- it reloads again under the execution lock;
- updateForQueueIfSnapshot applies exact expected status/execution/operation/retry/issue CAS.

Therefore a queued/active/new-owner row is rejected rather than reopened.

Candidate: HomeFragment navigates before turnDownloadItemsToProcessingDownloads finishes, causing durable wrong state.

Not established as a separate blocker:
- the multi-download dialog observes processingDownloads as a live Flow and processingItems as loading state;
- delayed row transitions can populate the dialog after navigation;
- this review did not establish a final durable/user-semantic failure from that ordering alone.

The raw detached CoroutineScope in HomeFragment remains a lifecycle-quality suspicion, not a counted blocker in this checkpoint without a proven final effect.

## L6 deep review — incognito to History

BUG-INCOGNITO-01 remains one root.

DAO aggregate:
- getProcessingAsIncognitoCount()/ByIDs returns only the number of incognito rows.

ViewModel:
- areAllProcessingIncognito returns count > 0.

Multi-download UI:
- consumes that Boolean as if it means all selected items are incognito;
- mixed selection is rendered fully enabled and the next toggle action is chosen from that false aggregate.

DownloadWorker:
- History persistence remains per row and executes whenever downloadItem.incognito is false.

Therefore a mixed selection can display an all-incognito state while non-incognito rows remain History-writing. The correction must use exact ALL cardinality semantics or an explicit mixed state and must preserve per-row behavior through queue/download execution.

## Deferred roots baseline continuity

CURRENT-PLAYER-TIMELINE-INDEX:
- exact source still dereferences oldPosition.mediaItemIndex through the current player timeline;
- OPEN_P1, deferred from PO-token gating only.

BUG-PLAYER-01:
- playback saves still launch separate lifecycle-owned IO writes without a durable logical sequence;
- OPEN_P2, deferred.

BUG-APP-UPDATE-01:
- tagNameToVersionNumber still flattens dotted components into one padded integer;
- OPEN_P2, deferred.

Deferral changes implementation order only; it does not close or waive the roots.

## Trigger map

Module A — Platform capability:
- scheduler exact-alarm capability remains a trigger;
- no new capability root established.

Module B — External scheduler handoff:
- FAIL BUG-FORMAT-BG-02;
- FAIL BUG-FORMAT-BG-05 cancellation handoff/identity;
- scheduler boundary recovery remains open only because authoritative remote correction is not yet published.

Module C — External representation:
- FAIL BUG-APP-UPDATE-01, deferred.

Module D — Packaged resource/ABI:
- no new trigger.

Module E — Referenced artifact publication:
- no new blocker; preserved updater publication boundary remains intact.

Module F — Persisted compatibility:
- no new schema-generation root;
- BUG-FORMAT-BG-02 remains a durable recovery-discovery failure rather than a schema migration root.

Module G — Reusable external identifier:
- no new trigger.

Module H — Persisted executable configuration:
- FAIL BUG-SCHEDULER-WINDOW-01;
- BUG-FORMAT-BG-04 remains stale executable Download configuration publication.

Module I — Maintenance vs live-owner namespace:
- no new blocker; completion-notification reopen revalidates exact live-owner absence.

## Terminal / cross-attempt matrix

BUG-FORMAT-BG-05:
- cancel before first progress update: initial ForegroundInfo has no exact cancel action; no proof of user cancellation path;
- cancel after progress action appears: current action carries wrong tag and does not cancel target;
- concurrent batches A/B: changing to shared tag would over-cancel both; exact request identity required;
- stale completed notification: exact request cancellation must be harmless and must not target a newer batch;
- process restart: cancellation identity must remain bound to the same WorkRequest generation.

BUG-FORMAT-BG-02:
- Saved state + enqueue failure/process death remains undiscoverable without a durable handoff carrier.

BUG-FORMAT-BG-03:
- item failure/cancellation can still converge to success/progress/completion notification.

BUG-FORMAT-BG-04:
- old successful fetch can publish after a newer config/execution wins.

BUG-SCHEDULER-WINDOW-01:
- repeated checks across midnight remain deterministically inconsistent on authoritative remote source.

BUG-INCOGNITO-01:
- mixed aggregate remains false authority for History privacy presentation.

## Verification evidence

Independent execution: NOT_EXECUTED.

Accepted prior identical-source-tree heavy evidence remains evidence for tests actually executed, but it does not close the newly established roots.

Reported scheduler local-candidate evidence from the intervening stop review:
- focused JVM 20/20 PASS;
- compile/APK proof PASS;
- device install FAILED with INSTALL_FAILED_INSUFFICIENT_STORAGE;
- device tests NOT_EXECUTED;
- no commit/push.

This manual review does not upgrade reported local evidence into remote source closure.

Repository search found no focused tests for:
- CancelWorkReceiver exact format-work cancellation identity;
- updateFormatUpdateNotification cancellation route;
- concurrent format batch cancellation isolation.

## Final recount

canonical_p0: 0
canonical_p1: 1
canonical_p2: 9
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

manual review status: FINAL
current SHA DEEP lenses: L1,L2,L3,L6
remaining not-yet-DEEP: L4,L5
next hint: L5 Platform contract closure

Next governed implementation action remains BUG-SCHEDULER-WINDOW-01 storage-recovery continuation from the preserved local four-file candidate. This manual L6 review does not change that scheduler correction contract.
