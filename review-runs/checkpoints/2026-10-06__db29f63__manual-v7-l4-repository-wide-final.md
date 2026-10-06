# Manual correctness review — db29f63 — repository-wide L4 destructive ownership

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 2d59a479b39ceef83621ac892e2cf9ff9464b164
review_parent_sha: 2d59a479b39ceef83621ac892e2cf9ff9464b164

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
review_evidence_p0: 0
review_evidence_p1: 1
review_evidence_p2: 12
review_evidence_open_roots: CURRENT-PLAYER-TIMELINE-INDEX,BUG-PLAYER-01,BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-FORMAT-BG-04,BUG-FORMAT-BG-05,BUG-INCOGNITO-01,BUG-APP-UPDATE-01,BUG-APP-UPDATE-02,BUG-PLAYLIST-DELETE-01,BUG-COOKIE-RESTORE-01
new_finding_ids: BUG-PLAYLIST-DELETE-01,BUG-COOKIE-RESTORE-01
independent_execution: NOT_EXECUTED

active_download_blockers_unchanged: BUG-SCHEDULER-WINDOW-01,BUG-FORMAT-BG-01,BUG-FORMAT-BG-02,BUG-FORMAT-BG-03,BUG-FORMAT-BG-04,BUG-FORMAT-BG-05,BUG-INCOGNITO-01
new_findings_priority: DEFERRED_NOT_REQUIRED_BEFORE_PO_TOKEN
po_token_resume_condition_unchanged: ALL_ACTIVE_DOWNLOAD_BLOCKERS_CLOSED

handoff_update_for_new_findings: INTENTIONALLY_NOT_PERFORMED
handoff_policy_source: USER_INSTRUCTION_2026_10_06
handoff_effect: NEW_FINDINGS_RECORDED_IN_REVIEW_CHECKPOINT_ONLY

## Independent verdict

db29f63 remains NOT_CLEAN.

This same-SHA manual run recomputed the full L1-L6 baseline and promoted L4 Destructive ownership to DEEP.

Two new P2 roots are established:
- BUG-PLAYLIST-DELETE-01 — playlist endpoint/relationship deletion is a multi-statement destructive operation without one Room transaction;
- BUG-COOKIE-RESTORE-01 — ordinary cookie mutation bypasses RestoreMutationAdmission while Restore performs an authoritative cookie reset/import.

Both findings are non-download/deferred under the user's current priority policy. They do not change the seven active download blockers and do not block PO-token once those download blockers are closed.

Per explicit user instruction, NEXT_CHAT.md and the active implementation handoff are not modified merely because these new findings were discovered.

## L1-L6 baseline

lens_coverage_current_sha:
- L1: DEEP_FAIL
- L2: DEEP_FAIL
- L3: DEEP_FAIL
- L4: DEEP_FAIL
- L5: DEEP_FAIL
- L6: DEEP_FAIL

primary_deep_lens: L4 Destructive ownership
primary_deep_selection_reason: R5 — L4 was the only remaining not-yet-DEEP lens on this implementation SHA after prior same-SHA L1/L2/L3/L5/L6 runs.
remaining_not_yet_deep: NONE
next_not_yet_deep_lens: NONE_ALL_LENSES_DEEP
next_lens_selection_reason: All six lenses are now DEEP on db29f63; revisit only with materially new evidence or a new implementation SHA.

L1 DEEP_FAIL:
- existing playback/order and format-handoff recovery roots remain;
- BUG-COOKIE-RESTORE-01 can silently lose or post-apply an ordinary cookie mutation relative to a durable Restore owner.

L2 DEEP_FAIL:
- existing timeline/format/incognito identity roots remain;
- BUG-COOKIE-RESTORE-01 lacks the active Restore owner/generation as part of ordinary cookie mutation authority.

L3 DEEP_FAIL:
- existing concurrency roots remain;
- BUG-COOKIE-RESTORE-01 allows ordinary cookie mutation to race an authoritative restore transaction because the cookie path bypasses the shared RestoreMutationAdmission mutex/gate.

L4 DEEP_FAIL:
- BUG-PLAYLIST-DELETE-01 has partial destructive commit windows across playlist relations and endpoint deletion;
- BUG-COOKIE-RESTORE-01 permits destructive cookie reset/import and ordinary cookie mutation to have competing owners;
- History/file deletion, cache maintenance, terminal recovery and History duplicate cleanup inspected in this run retain fail-closed ownership/revalidation.

L5 DEEP_FAIL:
- BUG-APP-UPDATE-01/02 remain open/deferred;
- no new L5 root established in this L4 run.

L6 DEEP_FAIL:
- existing scheduler/format/incognito propagation roots remain;
- no new download propagation root established in this L4 run.

## L4 deep review — History and file deletion

History UI associated-file deletion:
- freezes exact History records/paths;
- validates path/URI scope;
- excludes targets still referenced by retained History;
- under HistoryReferenceMutationCoordinator revalidates selected record path snapshots immediately before file deletion;
- revalidates snapshots again after file deletion;
- deletes History rows only for IDs whose target snapshots remained unchanged and whose file outcomes permit record removal.

ObserveSourceWorker destructive sync:
- fences final effect by exact observe-source generation;
- uses the same HistoryReferenceMutationCoordinator;
- revalidates History path snapshots before deletion;
- excludes retained references;
- revalidates again after file deletion;
- removes only the exact intersection still unchanged.

No new History-file destructive root is established.

History duplicate cleanup:
- rereads current duplicate identities under the relationship lock and Room transaction;
- materializes playlist memberships/assignments on retained History before retiring duplicate relationships;
- rereads exact identities immediately before final History deletion;
- the complete graph mutation rolls back on failure.

No residual of the prior History duplicate destructive root is established.

## L4 deep review — cache and terminal ownership

AppCacheManager:
- snapshots exact file path/size/mtime identities;
- deletes only under CacheMaintenanceAuthority;
- checks app-owned path scope and exclusions;
- treats live-owned entries as incomplete/skipped rather than rediscovering/deleting them;
- Terminal cache is additionally guarded by recovery namespace health.

TerminalDownloadWorker:
- retains task-token ownership;
- when publication is partial/unknown, persists a recovery carrier before revoking live ownership;
- preserves artifacts rather than deleting ambiguous publication remainder;
- invokes terminal execution/publication recovery after ownership transition.

No new cache/terminal destructive root is established.

## Candidate rejection — DownloadRepository bulk Saved/Processing deletion

Candidate:
deleteSaved()/deleteProcessing() capture a status-specific ID list and then execute a status-wide DELETE; a row entering that status between snapshot and DELETE could be removed without associated barrier cleanup.

Rejected as a current production root in this run:
- both operations execute inside RestoreMutationAdmission.withOrdinaryMutation;
- RestoreMutationAdmission uses one process-local reentrant Mutex for ordinary durable mutations;
- ordinary status-changing repository paths reviewed share that admission boundary;
- no production writer was established that can move a Download into the target status between the captured snapshot and bulk DELETE while bypassing that authority.

Do not count without a concrete bypassing producer.

## [P2] BUG-PLAYLIST-DELETE-01 — playlist destructive graph deletion is not atomic

Production path:
PlaylistViewModel.deletePlaylist
-> PlaylistRepository.deletePlaylist
-> RestoreMutationAdmission.withOrdinaryMutation
-> playlistDao.deletePlaylistItemsByPlaylistId(playlistId)
-> playlistDao.deletePlaylist(playlistId)
-> playlistGroupDao.deleteMembersByPlaylist(playlistId).

The ordinary-mutation gate prevents Restore races but is not a Room transaction.

Schema proof:
- Playlist has no foreign-key cascade declarations;
- PlaylistItemCrossRef is a composite-primary-key relation with no foreign keys;
- PlaylistGroupMember is a composite-primary-key relation with no foreign keys.

Therefore each DELETE is independently durable.

Failure/cancellation windows:
1. cross refs deleted, then coroutine/DB failure before playlist deletion:
   - playlist survives;
   - all History membership for it is permanently lost.
2. cross refs + playlist row deleted, then failure before group-member deletion:
   - playlist_group_members retains orphan references to a nonexistent playlist.
3. retry cannot reconstruct deleted cross refs from the surviving playlist row.

This is a destructive graph atomicity root, not merely cleanup quality.

Required eventual correction:
- execute playlist-item refs, playlist-group membership and playlist endpoint deletion in one Room transaction;
- preferably centralize graph deletion in a transaction-owning repository/DAO boundary;
- deletion of a missing playlist should be idempotent and should not delete unrelated/reused identities;
- focused rollback tests with failure injected after each destructive step;
- verify group-member and History-membership graph is either entirely pre-delete or entirely post-delete.

No focused PlaylistRepository/deletePlaylist JVM or Android test was found.

Status:
BUG_PLAYLIST_DELETE_01_STATUS=OPEN_P2_DEFERRED_NOT_BLOCKING_PO_TOKEN

## [P2] BUG-COOKIE-RESTORE-01 — cookie mutation bypasses Restore ownership/admission

Cookies are part of RestoreAppDataItem and the authoritative restore path resets/imports them inside RestoreTransactionCoordinator.applyAuthoritativeState:

- db.cookieDao.deleteAll()
- insert each restored CookieItem

The Restore operation has already published a durable active owner before this phase.

Ordinary cookie mutation path:
CookieViewModel
-> CookieProjectionCoordinator.acquireAndProject / mutateAndProject
-> CookieRepository
-> CookieDao insert/update/delete/deleteAll.

Authority gap:
- CookieRepository does not call RestoreMutationAdmission;
- CookieViewModel does not check RestoreGate;
- CookieProjectionCoordinator has its own cookie projection Mutex but does not participate in RestoreMutationAdmission;
- RestoreTransactionCoordinator uses raw CookieDao and does not share CookieProjectionCoordinator's mutex.

Therefore ordinary cookie mutation remains admitted while an authoritative Restore is active.

Reachable failure semantics:
1. ordinary cookie mutation commits before Restore's Room transaction:
   - Restore later overwrites it even though the user mutation was accepted during the active restore.
2. ordinary mutation starts during Restore and waits on SQLite write serialization:
   - it may commit after Restore's transaction, overwriting the authoritative restored cookie state while Restore still owns the operation.
3. cookie runtime projection can reflect a state whose Room ordering differs from the restore result because projection ownership is separate from Restore ownership.

This violates the same Restore authority contract already enforced by Download/History/Observe/updater ordinary mutations.

Required eventual correction:
- ordinary cookie insert/update/delete/enable/deleteAll and acquisition upsert must enter RestoreMutationAdmission.withOrdinaryMutation, or a shared cookie mutation boundary that checks the same active Restore owner;
- restore-owned cookie reset/import must use a restore-authorized internal primitive, not recursively claim ordinary authority;
- coordinate runtime cookies.txt projection so a rejected/stale ordinary mutation cannot publish executable credentials after Restore wins;
- test ordinary insert/update/delete/deleteAll racing PREPARED/APPLYING/DATA_COMMITTED restore phases;
- test projection state after restore and after rejected ordinary mutation;
- preserve cancellation and projection-failure semantics.

No focused cookie-vs-Restore admission/race test was found.

Status:
BUG_COOKIE_RESTORE_01_STATUS=OPEN_P2_DEFERRED_NOT_BLOCKING_PO_TOKEN

## Other destructive boundaries

Playlist delete is the only new playlist destructive root established.

Restore playlist reset itself uses db.withTransaction and explicitly clears relationship rows before endpoint rows; it does not share BUG-PLAYLIST-DELETE-01's partial-commit structure.

Observe source delete uses generation-CAS delete/cancel and shared ordinary mutation authority.

Download user removal/Undo uses durable undo carriers and transactional row/linked-state mutation before cache cleanup.

No additional destructive root was established from these paths in this run.

## Verification evidence

Independent execution: NOT_EXECUTED.

Existing broad heavy evidence remains evidence for tests actually executed, not for these newly established cells.

Repository search found no focused tests for:
- PlaylistRepository.deletePlaylist rollback after individual destructive steps;
- playlist-group orphan behavior on partial delete;
- CookieRepository/CookieProjectionCoordinator vs RestoreMutationAdmission;
- ordinary cookie mutation racing authoritative cookie restore.

## Final recount

review_evidence_p0: 0
review_evidence_p1: 1
review_evidence_p2: 12

review_evidence_open_roots:
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
- BUG-PLAYLIST-DELETE-01
- BUG-COOKIE-RESTORE-01

active_download_blockers remain:
- BUG-SCHEDULER-WINDOW-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-INCOGNITO-01

new deferred roots:
- BUG-PLAYLIST-DELETE-01
- BUG-COOKIE-RESTORE-01

manual_review_status: FINAL
current_sha_deep_lenses: L1,L2,L3,L4,L5,L6
remaining_not_yet_deep: NONE
next_hint: NONE_ALL_LENSES_DEEP_REVISIT_ONLY_WITH_MATERIALLY_NEW_EVIDENCE_OR_NEW_SHA

NEXT_CHAT / active implementation routing is intentionally unchanged by these newly discovered findings.
