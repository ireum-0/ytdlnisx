# Manual correctness review — db29f63 — repository-wide L2 identity/provenance

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 11cc4bf606409b7597a2d6bf38e45c4c3d7af81e
review_parent_sha: 11cc4bf606409b7597a2d6bf38e45c4c3d7af81e

implementation_sha: db29f63ce169176b4c8ade4cec01f66cc0307ec8
implementation_parent_sha: adf2f347ce9e20ec9f9376cf94053694353c9961
implementation_delta: ZERO_FILES_BASELINE_MARKER
source_tree_relation: IDENTICAL_TO_VERIFIED_ADF2F347
verified_source_sha: adf2f347ce9e20ec9f9376cf94053694353c9961

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
repository_wide_clean_claim: REVOKED_BY_FRESH_SOURCE_FINDINGS
canonical_p0: 0
canonical_p1: 1
canonical_p2: 7
canonical_open_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-INCOGNITO-01,BUG-APP-UPDATE-01
new_finding_ids: CURRENT-PLAYER-TIMELINE-INDEX,BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-INCOGNITO-01,BUG-APP-UPDATE-01
reopened_historical_finding_ids: BUG-PLAYER-01
independent_execution: NOT_EXECUTED

## Independent verdict

db29f63 is NOT_CLEAN.

The implementation marker changes zero files relative to adf2f347, so these are not marker regressions. Fresh repository-wide source review found pre-existing correctness roots on the identical source tree that were not covered by the previous repository-wide CLEAN conclusion.

The earlier final-heavy execution remains valid evidence for the tests it actually ran, but no focused JVM/instrumentation coverage was found for the newly established scheduler-window, background-format, mixed-incognito, app-version, player-discontinuity, or playback-position ordering cells. Those prior passes therefore do not close these roots.

## L1-L6 baseline

lens_coverage_current_sha:
- L1: DEEP_FAIL
- L2: DEEP_FAIL
- L3: BASELINE_FAIL
- L4: BASELINE_PASS
- L5: BASELINE_FAIL
- L6: BASELINE_FAIL

primary_deep_lens: L2 Identity & provenance
primary_deep_selection_reason: R1 — multiple confirmed current roots are exact identity/granularity failures: stale timeline index is applied to a different current timeline; selected format-update identity widens to all Processing rows; mixed incognito state is collapsed from ALL to ANY.
remaining_not_yet_deep: L3,L4,L5,L6
next_not_yet_deep_lens: L3 Concurrency & authority
next_lens_selection_reason: R1 — CURRENT-PLAYER-TIMELINE-INDEX and BUG-PLAYER-01 directly involve asynchronous callback/write ordering.

L1 baseline:
- FAIL because BUG-PLAYER-01 has no application-scoped ordered/coalesced durable writer; independent lifecycle IO launches can persist out of logical order and destruction can cancel pending writes.
- FAIL because BUG-FORMAT-BG-02 mutates Processing rows to Saved before issuing WorkManager enqueue and does not observe the returned asynchronous enqueue completion or retain durable retry responsibility.
- Previously reviewed updater/restore/history durability invariants remain source-level intact.

L2 deep:
- FAIL CURRENT-PLAYER-TIMELINE-INDEX: Player.PositionInfo.oldPosition.mediaItemIndex is old-timeline identity, but onPositionDiscontinuity dereferences it through current player.getMediaItemAt(oldIndex). Playlist replacement/removal can therefore reinterpret an old index against a different/new timeline or address an invalid index.
- FAIL BUG-FORMAT-BG-01: selectedItems determines worker ids, but continueUpdatingFormatsOnBackground calls moveProcessingToSavedCategory(), which changes every Processing row. Non-selected rows are therefore mutated outside the exact selected identity.
- FAIL BUG-INCOGNITO-01: areAllProcessingIncognito() returns COUNT(incognito)=... > 0, which is ANY semantics. The UI consumes it as ALL semantics and renders mixed selection as fully incognito.
- PASS preserved updater desired-source/provenance composite identity, observe SourceSnapshot authority typing, restore destination ID mapping, and metadata expected-source CAS boundaries inspected in this run.

L3 baseline:
- FAIL CURRENT-PLAYER-TIMELINE-INDEX because callback identity is not validated against the same current timeline snapshot before dereference.
- FAIL BUG-PLAYER-01 because mutex/lock serialization does not establish logical submission order between separately launched lifecycle coroutines.
- No new destructive ownership race was found in the previously closed restore/history relation paths.

L4 baseline:
- PASS on the inspected destructive boundaries: History duplicate relationship transfer remains transactionally ordered before duplicate retirement; no new filesystem/History destructive root was established in this run.
- Scheduler/format/player findings are not classified as destructive ownership roots.

L5 baseline:
- FAIL BUG-APP-UPDATE-01: app release comparison removes dots and pads the concatenated decimal string before Int comparison. Valid component-wise ordering is not preserved; e.g. 1.10.0 becomes numerically smaller than 1.9.0 under this transform.
- Exact-alarm capability admission itself is explicit; the confirmed scheduler defect is clock-window semantics, not permission capability.
- Legacy setForegroundAsync uses remain a source suspicion only; no separate blocker is counted because this run did not establish a platform failure through final semantic effect.

L6 baseline:
- FAIL BUG-SCHEDULER-WINDOW-01 propagates through queue admission, DownloadWorker stop/admission logic, and ObserveSourceWorker queue publication.
- FAIL BUG-INCOGNITO-01 propagates from mixed-selection UI state to DownloadItem.incognito, while DownloadWorker persists History whenever downloadItem.incognito is false.
- FAIL background-format roots propagate from selection to durable Saved state, WorkManager handoff, per-item worker result, and final notification.

## Findings

### [P1] CURRENT-PLAYER-TIMELINE-INDEX — stale callback index is dereferenced against the current timeline

Production chain:
Player callback old PositionInfo
-> oldPosition.mediaItemIndex
-> current player.getMediaItemAt(old index)
-> resolve History identity
-> playback-position persistence / queue-selection side effects.

The callback's old index belongs to the old position/timeline. The code does not prove that the current player timeline still has the same indexed item before dereference. Playlist replacement/removal can make the index identify a different item or fall outside the current timeline.

Required correction:
- resolve old-item identity from callback-owned/same-snapshot data where available, or validate generation/timeline identity before current-player dereference;
- do not coerce an invalid old index to a neighboring item;
- cover remove-current, remove-before-current, replace-playlist, empty timeline and stale callback after new timeline.

Attribution: pre_existing / newly established by this manual review.

### [P2] BUG-PLAYER-01 — playback-position persistence is not logically ordered or destruction-safe

Production chain:
pause/stop/destroy/item transition
-> savePlaybackPositionForHistoryId
-> in-memory/cache update
-> separate lifecycleScope.launch(Dispatchers.IO)
-> HistoryReferenceMutationCoordinator lock
-> Room updatePlaybackPosition.

The lock serializes the writes that reach it but does not bind lock acquisition to logical submission order. A later logical zero/reset or seek position can be overtaken by an earlier launch. Activity destruction also owns/cancels lifecycleScope rather than transferring pending durable writes to application-scoped ordered ownership.

Required correction remains the historical F21 contract:
application-scoped per-History ordered/coalescing persistence; latest logical save wins; near-end zero cannot be overtaken; destruction/recreation converges cache and Room.

Attribution: historical root re-established as still present at current source.

### [P2] BUG-SCHEDULER-WINDOW-01 — overnight schedule windows are false after midnight

AlarmScheduler.isDuringTheScheduledTime converts an overnight end hour such as 05:00 to 29, but leaves currentHour in 0..23. For a configured 22:00..05:00 window, 00:00..05:00 therefore fails currentHour in 22..29.

The same predicate is consumed by:
- DownloadViewModel queue admission;
- DownloadWorker stop/admission behavior;
- ObserveSourceWorker queue/start behavior.

A related boundary bug exists in scheduleWithinOrdinaryMutation: the code sets eTime hour/minute, then mistakenly calls sTime.set(SECOND, 0), leaving eTime seconds inherited from the current clock. End-boundary publication can therefore drift by up to 59 seconds.

Required correction:
use one normalized circular-day interval model and one normalized boundary-construction helper; cover same-day and overnight ranges, exact start/end minutes, midnight, and end seconds.

Attribution: pre_existing / newly established.

### [P2] BUG-FORMAT-BG-01 — selected format-update scope widens to all Processing rows

continueUpdatingFormatsOnBackground(selectedItems):
- derives worker ids from selectedItems;
- then calls moveProcessingToSavedCategory(), which updates every Processing row;
- only selected ids are sent for actual format fetching;
- non-selected ids are carried as other_ids_in_bundle and can be presented in the completion notification.

This violates exact selected-set identity and can move unrelated in-progress configuration rows to Saved.

Required correction:
transition only the exact selected rows (or prove explicit all-items semantics at the UI contract); do not report nonprocessed rows as updated.

Attribution: pre_existing / newly established.

### [P2] BUG-FORMAT-BG-02 — format background handoff treats enqueue request as completion

After moving rows to Saved, continueUpdatingFormatsOnBackground calls WorkManager.enqueueUniqueWork and discards the asynchronous Operation/completion. No durable recovery carrier for failed enqueue is established in this path.

Required correction:
observe scheduler acceptance/completion or persist exact recovery responsibility before/with the durable state transition; make replay idempotent and selected-set exact.

Attribution: pre_existing / newly established.

### [P2] BUG-FORMAT-BG-03 — per-item format failures are converted into batch success

UpdateMultipleDownloadsFormatsWorker wraps format fetch + format selection + DB writes in runCatching, discards the result, increments progress, returns Result.success(), and finally shows the formats-updated notification while ids remains nonempty.

An item-level network/parser/format/DB failure can therefore be reported as successful update.

Required correction:
typed per-item outcome, cancellation preservation, honest worker result/notification semantics, and sibling isolation without converting failed items to success.

Attribution: pre_existing / newly established.

### [P2] BUG-INCOGNITO-01 — mixed selection is presented as fully incognito

areAllProcessingIncognito uses COUNT(incognito=1) > 0 for both all Processing rows and selected ids. DownloadMultipleBottomSheetDialog consumes this Boolean as an ALL predicate and renders full alpha when true; the same icon state determines whether the next click disables or enables all selected rows.

DownloadWorker separately persists History whenever downloadItem.incognito is false. Therefore a mixed selection with only one incognito row can be shown as fully incognito while other selected rows remain History-writing.

Required correction:
compute exact ALL semantics (including selected-set cardinality/empty semantics) or explicitly render a mixed/indeterminate state; tests must connect the UI aggregate to persisted History behavior.

Attribution: pre_existing / newly established.

### [P2] BUG-APP-UPDATE-01 — release version identity is flattened incorrectly

UpdateUtil.tagNameToVersionNumber removes "-beta" and "." then right-pads to ten characters and parses Int. This is not component-wise version ordering. Valid versions with wider components are misordered; 1.10.0 and 1.9.0 are a direct counterexample.

tryGetNewVersion uses that scalar for stable/beta release choice, so the updater can incorrectly report current/latest or select the wrong upgrade relation.

Required correction:
parse structured version components and beta/build semantics explicitly, or compare against the repository's versionCode-compatible model with validated tag syntax; malformed release tags must not silently gain authority.

Attribution: pre_existing / newly established.

## Trigger map

- Module A — Platform capability: TRIGGERED by exact-alarm admission. PASS for the inspected permission/capability branch; no new capability blocker established.
- Module B — External scheduler handoff: TRIGGERED. FAIL due BUG-FORMAT-BG-02; scheduler-window consumers also depend on AlarmManager/WorkManager boundaries.
- Module C — External representation/schema/authority projection: TRIGGERED by GitHub release tag -> numeric version projection. FAIL due BUG-APP-UPDATE-01.
- Module D — Packaged resource/ABI provenance: NOT_TRIGGERED by any newly established root.
- Module E — Referenced-artifact publication: NOT_TRIGGERED by newly established roots; preserved updater publication path was inspected at baseline.
- Module F — Persisted schema-generation compatibility: NOT_TRIGGERED by a new schema/generation semantic change in this zero-file marker review.
- Module G — Reusable external identifier lifetime: NOT_TRIGGERED.
- Module H — Persisted executable configuration fan-out: TRIGGERED by schedule_start/schedule_end. FAIL due BUG-SCHEDULER-WINDOW-01 and its three production consumers.
- Module I — Maintenance vs live-owner namespace: NOT_TRIGGERED by newly established roots.

semantic_contract_delta_trigger: NO
changed_boundary: NONE_ZERO_FILE_MARKER
note: findings are pre-existing defects on the identical source tree, not consequences of a current implementation delta.

## Terminal / async fault matrix summary

- Background format enqueue async failure after Processing->Saved: FAIL — no observed acceptance and no durable recovery carrier.
- Background format item fetch/selection/write failure: FAIL — runCatching discards failure and emits batch success.
- Playback-position logical save ordering: FAIL — later logical event has no durable sequence that prevents earlier coroutine write from winning.
- Scheduler overnight boundary: deterministic logic FAIL rather than injected-fault case.
- Mixed incognito: deterministic aggregate/consumer contract FAIL.
- App version projection: deterministic representation FAIL.
- Previously closed updater/restore/history first-write/recovery boundaries: no regression found in exact-source baseline inspection.

## Cross-attempt / live-owner matrix

- Player save rapid sequence / pause->stop->destroy / near-end zero: FAIL because final durable write is not tied to latest logical submission.
- Format background restart after enqueue failure: FAIL because Saved rows carry no exact scheduler-recovery responsibility for this format task.
- Scheduler repeated checks across midnight: FAIL deterministically until wall clock reaches start hour again.
- Observe/restore/updater live-owner paths inspected here retain their previously established exact generation/owner checks.

## Recovery discovery closure

FAIL for BUG-FORMAT-BG-02: there is no demonstrated durable carrier that lets restart discover that an exact selected format-update batch was moved to Saved but never accepted by WorkManager.

FAIL for BUG-PLAYER-01: cache has latest submitted value, but Room persistence is launched from Activity lifecycle and there is no demonstrated application-scoped ordered drain/recovery owner that guarantees cache->Room convergence after destruction.

No newly discovered recovery-discovery regression in updater/restore/history closure paths.

## Candidate rejections

- Exact-alarm permission missing during ordinary queue request: NOT a separate blocker in this run. The branch returns an explicit user-visible failure and disables scheduler; it does not silently claim the queue started.
- Legacy setForegroundAsync calls in several workers: NOT_VERIFIED as a separate current blocker. Source shows ignored futures, but this review did not establish a concrete supported-platform failure through final durable/user-visible effect; do not count without execution/contract proof.
- UpdateMultipleDownloadsDataWorker partial metadata failures: NOT counted as the same root as format update. Current metadata writer uses expected-source column-level CAS and logs batch failures; no false "formats updated" contract applies.
- Historical Observe partial-snapshot, backup capture-empty, metadata stale-full-row, updater generation-origin, and History duplicate membership defects: exact-source baseline inspection shows the stronger typed/snapshot/CAS/transactional contracts still present; no current residual established in those roots.

## Verification evidence

Independent execution for this manual run: NOT_EXECUTED.

Existing accepted identical-source-tree heavy evidence:
- full JVM: 737 PASS / 0 FAIL / 0 SKIP;
- instrumentation: 100 PASS / 0 FAIL / 0 SKIP;
- KSP/Kotlin/AndroidTest compile and arm64 assemble: PASS;
- x86 artifact proof: PASS.

Those runs remain evidence for their covered tests only. Repository search found no focused JVM or Android-test coverage for:
- UpdateMultipleDownloadsFormatsWorker;
- continueUpdatingFormatsOnBackground;
- areAllProcessingIncognito;
- isDuringTheScheduledTime;
- tagNameToVersionNumber;
- VideoPlayerActivity.onPositionDiscontinuity;
- savePlaybackPositionForHistoryId ordering/destruction.

Therefore the newly established roots remain open despite the prior broad green run.

## Final recount

canonical_p0: 0
canonical_p1: 1
canonical_p2: 7
canonical_open_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-INCOGNITO-01,BUG-APP-UPDATE-01

repository_wide_clean: NO
known_good_baseline: HISTORICAL_TAG_REMAINS_IMMUTABLE_BUT_NO_LONGER_SUFFICIENT_AS_CURRENT_REPOSITORY_WIDE_CLEAN_CLAIM
manual_review_status: FINAL
current_sha_deep_lenses: L1,L2
current_sha_l1_status: DEEP_FAIL
current_sha_l2_status: DEEP_FAIL
remaining_not_yet_deep: L3,L4,L5,L6
next_hint: L3 Concurrency & authority

next_required_remediation: CURRENT-PLAYER-TIMELINE-INDEX
next_remediation_reason: highest-severity actionable current root; P1 before P2 roots.
