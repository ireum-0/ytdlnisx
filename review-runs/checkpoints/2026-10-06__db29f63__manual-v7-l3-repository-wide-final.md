# Manual correctness review — db29f63 — repository-wide L3 concurrency/authority

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: b6ccb0e0fdbcd0a1eb0f59d4751e2e9c69995906
review_parent_sha: b6ccb0e0fdbcd0a1eb0f59d4751e2e9c69995906

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
canonical_p2: 8
canonical_open_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-FORMAT-BG-04,BUG-INCOGNITO-01,BUG-APP-UPDATE-01
new_finding_ids: BUG-FORMAT-BG-04
independent_execution: NOT_EXECUTED

active_download_blockers: BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-FORMAT-BG-04,BUG-INCOGNITO-01
deferred_non_download_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-APP-UPDATE-01
deferred_non_download_policy: RECORDED_NOT_REQUIRED_BEFORE_PO_TOKEN
po_token_resume_condition: ALL_ACTIVE_DOWNLOAD_BLOCKERS_CLOSED

## Independent verdict

db29f63 remains NOT_CLEAN.

The current implementation SHA is unchanged from the preceding repository-wide L2 run. This same-SHA manual run recomputed the full L1-L6 baseline and promoted L3 Concurrency & authority to DEEP.

One additional download-related P2 root is established:
BUG-FORMAT-BG-04 — stale background-format snapshot adopts newer row authority and can overwrite concurrent current configuration.

This is distinct from:
- BUG-FORMAT-BG-01 selected-set scope widening;
- BUG-FORMAT-BG-02 missing durable WorkManager handoff acceptance/recovery;
- BUG-FORMAT-BG-03 false-success/error/cancellation reporting.

The current active download blocker set therefore increases from five to six.

The three non-download roots remain recorded but deferred and do not block the user's PO-token plan.

## L1-L6 baseline

lens_coverage_current_sha:
- L1: DEEP_FAIL
- L2: DEEP_FAIL
- L3: DEEP_FAIL
- L4: BASELINE_PASS
- L5: BASELINE_FAIL
- L6: BASELINE_FAIL

primary_deep_lens: L3 Concurrency & authority
primary_deep_selection_reason: R1/R2 — CURRENT-PLAYER-TIMELINE-INDEX and BUG-PLAYER-01 are concurrency/authority roots, while active download BUG-FORMAT-BG-02/04 directly cross scheduler and row-ownership boundaries.
remaining_not_yet_deep: L4,L5,L6
next_not_yet_deep_lens: L6 Cross-feature semantic propagation
next_lens_selection_reason: R1/R2 — multiple active download roots propagate across Processing/Saved/WorkManager/worker/History/UI consumers; L4 baseline currently passes and L5's direct open root is deferred non-download BUG-APP-UPDATE-01.

L1 DEEP_FAIL:
- BUG-PLAYER-01 remains without application-scoped ordered persistence/recovery;
- BUG-FORMAT-BG-02 moves durable row state before scheduler acceptance and has no exact durable recovery carrier;
- no new updater/restore/history durability regression found.

L2 DEEP_FAIL:
- prior CURRENT-PLAYER-TIMELINE-INDEX, BUG-FORMAT-BG-01 and BUG-INCOGNITO-01 identity/granularity failures remain;
- BUG-FORMAT-BG-04 additionally shows a stale snapshot can adopt a newer execution token rather than prove the snapshot is still current.

L3 DEEP_FAIL:
- BUG-FORMAT-BG-02 is an unowned async handoff;
- BUG-FORMAT-BG-04 publishes an old configuration snapshot after newer user/queue state wins;
- BUG-PLAYER-01 independent lifecycle launches are serialized only by eventual lock acquisition, not by logical submission order;
- CURRENT-PLAYER-TIMELINE-INDEX applies callback-old index authority to a different current timeline.

L4 BASELINE_PASS:
- no new destructive ownership root found;
- previously closed History duplicate playlist-membership transfer remains transactionally ordered and exact.

L5 BASELINE_FAIL:
- BUG-APP-UPDATE-01 remains as a deferred non-download representation/platform-contract root;
- no new platform root established in this L3 run.

L6 BASELINE_FAIL:
- scheduler, format-background and incognito roots continue to propagate through multiple download surfaces;
- BUG-FORMAT-BG-04 propagates from Saved/config UI or queue transition into background format publication and potentially active execution state.

## L3 deep review — background format authority

### Existing producer chain

DownloadViewModel.continueUpdatingFormatsOnBackground(selectedItems):
1. reads all current Processing IDs;
2. derives worker ids from selectedItems or allProcessing;
3. calls moveProcessingToSavedCategory();
4. creates UpdateMultipleDownloadsFormatsWorker input;
5. calls WorkManager.enqueueUniqueWork();
6. does not observe Operation.result.

This chain has no single durable transaction that couples exact selected identity, status transition, and scheduler acceptance.

### BUG-FORMAT-BG-01 concurrency refinement

The function snapshots allProcessing before moveProcessingToSavedCategory() performs its own fresh all-Processing mutation.

A row that enters Processing after the first snapshot but before moveProcessingToSavedCategory():
- may be moved to Saved by the later bulk UPDATE;
- is absent from ids;
- is absent from other_ids_in_bundle.

This strengthens the existing selected-scope root: the bug is not only static overbreadth for nonselected rows; the read/mutate split also permits rows outside the captured bundle to be swept into Saved with no worker ownership.

No new root is counted for this refinement.

### BUG-FORMAT-BG-02 deep confirmation

The repository already contains WorkManagerHandoffRecovery, whose own contract explicitly states:
calling enqueueUniqueWork is not acceptance; exact request identity and Operation.result must be observed and durable recovery ownership retained.

continueUpdatingFormatsOnBackground bypasses that authority:
- no WorkManagerHandoffCarrier is staged;
- no exact request UUID/generation is persisted as recovery debt;
- Operation.result is discarded;
- Processing->Saved publication happens before scheduler acceptance is known.

Process death or enqueue failure can therefore leave exact rows in Saved without discoverable responsibility to perform their requested background format update.

No new root; BUG-FORMAT-BG-02 remains OPEN_P2 with stronger repository-internal authority evidence.

### BUG-FORMAT-BG-03 cancellation/error refinement

UpdateMultipleDownloadsFormatsWorker:
- checks isStopped only before starting each item;
- wraps format fetch + Result write + Download write in runCatching and ignores the Result;
- increments count after the runCatching regardless of success;
- returns Result.success when the loop completes;
- shows the "formats updated" notification while ids remains nonempty.

NewPipe format retrieval explicitly rethrows CancellationException, but this worker's runCatching catches Throwable and can absorb it.

yt-dlp format retrieval is a blocking non-suspend request. Stop/cancel can become observable only after that blocking call returns. The worker does not re-prove stop/cancellation before publication.

Therefore the current root includes:
- item network/parser/DB failure converted to progress/success;
- cancellation during one item potentially converted to success semantics;
- no exact per-item outcome carrier.

No new root; this is BUG-FORMAT-BG-03.

## New root — BUG-FORMAT-BG-04

severity: P2
status: OPEN_P2_REMEDIATION_READY
root: STALE_BACKGROUND_FORMAT_SNAPSHOT_CAN_ADOPT_NEWER_ROW_AUTHORITY_AND_OVERWRITE_CURRENT_CONFIGURATION

### Producer

For each worker id:
- d = dao.getDownloadById(id) captures a full DownloadItem snapshot;
- the worker performs potentially long external format retrieval from d.url;
- the row remains independently mutable by ordinary Saved/queue/configuration UI during this interval.

SavedDownloadsFragment allows a Saved item to be reopened and queued; DownloadBottomSheetDialog can reconstruct/edit the same DownloadItem and persist it again. The row can therefore legitimately advance while the format fetch is in flight.

### Current publication

After fetch, the worker rereads current = dao.getNullableDownloadById(id), but does not compare the old snapshot's configuration identity to current.

Instead it copies only:
- current.status;
- current.executionId;
- current lastIssue fields;
- current History replacement barrier fields;

back into stale d.

Then:
- if current.executionId is nonblank, dao.updateIfExecutionOwned(d, current.executionId);
- otherwise dao.updateWithoutUpsert(d).

This is not an authority proof for d. It lets the old d snapshot adopt the current execution token/status.

updateIfExecutionOwned verifies only that the token it was just given still equals current executionId and that protected issue/barrier fields are preserved. It does not prove:
- URL/source identity still matches d.url;
- type still matches;
- format/container/path/incognito/preferences remain the same configuration generation;
- this format worker owns the new execution attempt.

updateWithoutUpsert is likewise a full-row @Update guarded only by terminal/barrier preservation, not by an exact expected source/config revision.

### Final effect

A background format fetch started for old configuration C1 can complete after C2 is saved or queued and write C1 back over C2 while retaining C2's current status/execution token.

Affected stale fields can include:
- url/source;
- type;
- format/allFormats;
- container;
- download sections/path;
- audio/video preferences;
- extra commands/custom filename;
- thumbnail/incognito and other full-row configuration.

If the row has already been queued/claimed, copying current.executionId into d can make the stale format publisher pass updateIfExecutionOwned and mutate the configuration of the newer active execution.

ResultItem has the same shape of risk:
- r = getResultByURL(d.url) is captured before the external fetch;
- r.formats is changed;
- ResultDao.update(r) is a full-row @Update with no expected-source/revision CAS.

### Classification

This is a separate root from BUG-FORMAT-BG-01/02/03:
- fixing selected-set exactness does not prevent stale snapshot publication;
- fixing scheduler acceptance does not prevent stale snapshot publication after accepted work runs;
- fixing error reporting does not prevent a successful stale fetch from overwriting newer state.

### Narrow correction contract

The format worker must publish only the fields it owns, under an exact snapshot identity.

Required:
- capture immutable expected source/config identity before fetch;
- after fetch, atomically update only format-owned columns when the exact expected identity still matches;
- never copy a newer executionId into an older snapshot as authority;
- if the row advanced to a new execution/configuration, classify the old format result as superseded and do not publish it;
- Result publication must likewise be narrow/source-matched or generation-matched rather than stale full-row update;
- preserve History replacement barriers and all newer user configuration;
- cancellation before authoritative publication must not mutate the row.

Regression matrix:
- Saved row edited while fetch in flight;
- Saved row queued while fetch in flight;
- new executionId claimed while fetch in flight;
- type changed while fetch in flight;
- URL/source changed or row replaced/deleted;
- Result row refreshed/replaced while fetch in flight;
- unchanged exact snapshot accepts format publication;
- stale worker does not alter current status/execution/config fields.

## Other open roots continuity

CURRENT-PLAYER-TIMELINE-INDEX:
- still OPEN_P1 but DEFERRED_NOT_BLOCKING_PO_TOKEN;
- no new semantic refinement in this run.

BUG-PLAYER-01:
- still OPEN_P2 but DEFERRED_NOT_BLOCKING_PO_TOKEN;
- L3 confirms lock acquisition does not establish logical save order.

BUG-SCHEDULER-WINDOW-01:
- OPEN_P2 active download blocker;
- current persisted scheduler correction prompt remains semantically valid;
- no L3 finding changes its correction contract.

BUG-FORMAT-BG-01/02/03:
- OPEN_P2 active download blockers;
- L3 refinements above apply.

BUG-INCOGNITO-01:
- OPEN_P2 active download blocker;
- no new concurrency refinement.

BUG-APP-UPDATE-01:
- OPEN_P2 but DEFERRED_NOT_BLOCKING_PO_TOKEN.

## Trigger map

Module A Platform capability:
- scheduler exact-alarm admission remains explicit;
- no new blocker.

Module B External scheduler handoff:
- FAIL BUG-FORMAT-BG-02;
- current format-background path bypasses the repository's durable WorkManager handoff authority.

Module C External representation/schema/authority:
- existing BUG-APP-UPDATE-01 remains FAIL but deferred.

Module D Packaged ABI/resource provenance:
- no new trigger.

Module E Referenced artifact publication:
- no new root; updater publication remains preserved.

Module F Persisted compatibility/recovery:
- active format handoff has recovery-discovery failure under BUG-FORMAT-BG-02, but no new schema-generation change.

Module G Reusable external identifier:
- no new blocker.

Module H Persisted executable configuration:
- FAIL BUG-SCHEDULER-WINDOW-01;
- BUG-FORMAT-BG-04 also demonstrates stale executable Download configuration publication.

Module I Maintenance vs live-owner namespace:
- no new root.

## Candidate rejection

Candidate: current.executionId copied into d makes the format worker safe.
Rejected. The worker did not own/capture that newer execution token; copying it after the external fetch is authority adoption, not proof.

Candidate: updateIfExecutionOwned prevents stale format publication.
Rejected. It checks the newly copied executionId and protected barrier fields, not the old snapshot's exact configuration/source generation.

Candidate: format worker only changes allFormats/format.
Rejected. It mutates d in memory but then performs a full-row Room update, so every stale DownloadItem field is eligible to be republished.

Candidate: Result row is safe because lookup uses URL.
Rejected. URL selects the initial r, but ResultDao.update(r) is later full-row publication with no expected revision/source CAS.

Candidate: prior 737 JVM / 100 instrumentation green evidence closes BUG-FORMAT-BG-04.
Rejected. No focused test for UpdateMultipleDownloadsFormatsWorker or the stale snapshot/queue/edit concurrency cells was found.

## Verification evidence

Independent execution: NOT_EXECUTED.

Existing broad heavy evidence remains valid for covered tests, but does not close current open roots.

No focused current tests were found for:
- UpdateMultipleDownloadsFormatsWorker stale snapshot publication;
- background-format WorkManager handoff acceptance;
- per-item failure/cancellation truthfulness;
- selected-set exact status transition;
- mixed incognito ALL semantics;
- overnight scheduler window.

## Final recount

canonical_p0: 0
canonical_p1: 1
canonical_p2: 8
canonical_open_roots:
- CURRENT-PLAYER-TIMELINE-INDEX
- BUG-PLAYER-01
- BUG-SCHEDULER-WINDOW-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-INCOGNITO-01
- BUG-APP-UPDATE-01

active_download_blockers:
- BUG-SCHEDULER-WINDOW-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-INCOGNITO-01

deferred_non_download_roots:
- CURRENT-PLAYER-TIMELINE-INDEX
- BUG-PLAYER-01
- BUG-APP-UPDATE-01

manual review status: FINAL
current SHA DEEP lenses: L1,L2,L3
remaining not-yet-DEEP: L4,L5,L6
next hint: L6 Cross-feature semantic propagation

Next governed implementation action remains BUG-SCHEDULER-WINDOW-01. The persisted scheduler correction prompt remains semantically valid; only its governing review tip/handoff must be rebound.
