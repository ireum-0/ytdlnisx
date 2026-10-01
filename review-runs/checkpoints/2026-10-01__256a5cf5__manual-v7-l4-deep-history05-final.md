# Manual correctness review — 256a5cf5 — L4 destructive ownership deep (final)

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 3cb34f31c7f3431ef665c5cbee097aca2b52355b
review_parent_sha: 66076078ea4f8b47c167ecab81edd49d096eac77
intermediate_checkpoint: review-runs/checkpoints/2026-10-01__256a5cf5__manual-v7-l4-deep-history05-intermediate.md
intermediate_commit: 66076078ea4f8b47c167ecab81edd49d096eac77

implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_tree: acc40abe31e99b78e76056eb0a002b584d00c64c
master_plan_tip: 2145847a1054da28398b730b9be0ca728668f967
master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
protocol_blob: 3394e14db9f2bf79dcd8c1e3492537c58d4eb933
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
canonical_count_semantics: ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY

## Independent verdict

NOT_CLEAN.

Canonical active-remediation counts:
- P0=0
- P1=0
- P2=1

Active root:
- BUG-UPDATER-02 — OPEN P2 / SAME_ROOT_RESIDUAL.

Existing repository-wide finding outside the adopted active inventory:
- BUG-UPDATER-03 — P2 / PRE_EXISTING_BASELINE_DEFECT / active_scope=NO.

New repository-wide finding established by this run:
- BUG-HISTORY-05 — P2 / PRE_EXISTING_BASELINE_DEFECT / active_scope=NO.

BUG-HISTORY-05 does not increment the canonical active-scope P2 count without explicit
inventory/scope adoption. The earlier provisional interpretation that this was a BUG-HISTORY-04
same-root residual is rejected by the production counterexample: the relationship loss occurs with
fully current, correctly revalidated duplicate identity and therefore has a distinct causal root.

Known-Good Baseline remains NOT_CREATED. BUG-UPDATER-02 independently blocks the current active
remediation scope.

## Findings

### 1. BUG-UPDATER-02 — OPEN P2 same-root residual, reconfirmed

Fresh exact-source review reconfirms both already-established cells.

Backup/restore provenance:
- BackupSettingsUtil does not exclude ytdlp_source_generation,
  ytdlp_committed_source_generation, ytdlp_committed_source,
  ytdlp_committed_result, ytdlp_pending_source_generation or ytdlp_pending_source.
- BackupRestoreParser accepts those portable settings.
- Merge writes accepted portable settings directly.
- Reset clears portable settings and republishes data.settings through putPortable().
- UpdateUtil.committedMatches() accepts generation/source equality from those preferences as
  committed proof, so foreign source-device runtime provenance can suppress destination
  reconciliation.

Startup recovery:
- App.onCreate() starts RestoreTransactionCoordinator.recover() asynchronously.
- MainActivity starts updater reconciliation independently and does not await that restoreRecovery.
- UpdateUtil.beginMutation() uses RestoreMutationAdmission.withOrdinaryMutation().
- An active Reset can therefore reject updater admission.
- MainActivity wraps the entire startup attempt in runCatching and installs no same-process
  retry/convergence owner after RestoreGate release.

Relation: SAME_ROOT_RESIDUAL.
Canonical active count impact: already counted, +0.

### 2. BUG-UPDATER-03 — P2 pre-existing baseline defect, reconfirmed

BackupRestoreParser.validateSettings() accepts every String value without a key-specific
ytdlp_source semantic check. A present blank ytdlp_source can therefore enter Merge/Reset settings
state, while current UpdateUtil.readDesiredSourceLocked() rejects a present blank source.

The prior exact historical attribution remains applicable: this malformed portable source contract
predates the current remediation.

Relation: existing distinct repository root.
Attribution: PRE_EXISTING_BASELINE_DEFECT.
Active inventory: NO.
Canonical active count impact: +0.

### 3. BUG-HISTORY-05 — P2 pre-existing playlist-membership loss during valid duplicate cleanup

Correctness is decided independently of the earlier BUG-HISTORY-04 closure.

Current production trace:
1. HistoryViewModel.deleteDuplicates() obtains duplicate candidate IDs.
2. deleteDuplicateHistoryGroups() enters HistoryReferenceMutationCoordinator and one Room
   transaction.
3. It rereads current History rows, recomputes HistoryDuplicateIdentity and groups only rows that
   remain true duplicates.
4. For a valid duplicate pair it copies keyword assignments from duplicate to retained History.
5. It does not read the duplicate's PlaylistItemCrossRef memberships for transfer.
6. It calls playlistDao.deletePlaylistItemsByHistoryIds(duplicate.id).
7. It rechecks duplicate identity and deletes the duplicate History row.

Counterexample with no stale authority:
- retained R and duplicate D have the same valid destructive duplicate identity;
- R belongs only to Playlist A;
- D belongs only to Playlist B;
- R remains the retained History row;
- D is validly removed;
- Playlist B -> D is removed and no Playlist B -> R row is created.

Final effect:
- the user's durable Playlist B membership for the logical media disappears even though dedupe
  retains an equivalent History row.

This relation is correctness state elsewhere in production:
- captureAndDeleteHistoryForUndo() snapshots playlistMemberships together with the History row and
  assignments in one transaction;
- restoreHistory(HistoryUndoSnapshot) restores surviving playlist memberships;
- PlaylistItemCrossRef has exact (playlistId, historyItemId) primary-key identity and PlaylistDao
  provides explicit insert/get/delete primitives.

Regression gap:
- HistoryDuplicateIdentityProductionWiringTest seeds retained and duplicate into the same single
  playlist.
- stale-candidate tests therefore prove preservation only when both rows already share the same
  membership.
- the successful cleanup test confirms duplicate memberships become empty but does not require the
  retained row to inherit a duplicate-only membership.

Attribution:
- exact baseline 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9 performed
  mergeHistoryAssignments(duplicate -> retained) and then deleteHistoryRecords(duplicates);
- deleteHistoryRecords() deleted PlaylistItemCrossRef rows for the duplicate IDs without transfer.
- therefore the relationship-loss contract predates this remediation.

Root relation:
- NEW_DISTINCT_ROOT, not BUG-HISTORY-04 residual.
- BUG-HISTORY-04's original stale destructive-authority invariant is closed: current code treats
  candidate IDs as hints, rereads current rows, recomputes identity under the shared relationship
  lock/Room transaction, and revalidates exact identity immediately before History deletion.
- BUG-HISTORY-05 persists even when all of those HISTORY-04 checks succeed.

Narrow future correction if scope is adopted:
- within the existing relationship lock and same Room transaction, read duplicate playlist
  memberships and materialize their union on retained.id before retiring duplicate memberships;
- preserve retained-only memberships and make shared memberships idempotent;
- keep the existing final duplicate-identity revalidation;
- keep assignment transfer, relationship transfer and History deletion in one rollback boundary;
- add asymmetric fixtures with retained-only and duplicate-only playlists plus injected deletion
  failure rollback coverage.

## Full checklist / trigger recount

Mandatory execution-order review was repeated against exact source. No prior same-SHA verdict was
used as a substitute for current source semantics.

Blocker-relevant triggers:
- Module A platform admission: fresh source preserves ExactAlarmCapabilityPolicy pre-31 behavior,
  minSdk26 support floor and non-exported ordinary ResumeActivity; no new blocker found.
- Module C external representation/schema/authority projection: FAIL due BUG-UPDATER-02 foreign
  updater authority and BUG-UPDATER-03 malformed portable source.
- Module D packaged resource/ABI provenance: PASS in the reviewed boundary. arm64-only publication
  is configured and GitHub contains the arm64 libffmpeg/libffprobe blobs plus bundled payload;
  runtime resolution validates revision/generation.
- Module E shared-generation promotion: FAIL for BUG-UPDATER-02 updater provenance/recovery.
  Bundled FFmpeg staging/journal/exact-generation rollback path remains closed on fresh source.
- Module F persisted schema-generation compatibility: FAIL. BUG-UPDATER-02 imports generation proof
  across installations; BUG-UPDATER-03 also exposes a persisted nonblank-contract mismatch.
- Module H persisted executable configuration fan-out: FAIL due BUG-UPDATER-03.
- Module I maintenance vs live-owner namespace: PASS in the reviewed Terminal-cache boundary;
  unavailable/opaque ownership remains non-removable and AppCacheManager deletes only REMOVABLE.
- Core destructive identity/reference preservation: FAIL due BUG-HISTORY-05.

No new blocker-relevant Module B or G uncertainty was introduced by the evidence in this run.

Preserved canonical root spot-checks:
- BUG-DOWNLOAD-01: FIXED_CLOSED — authority-read failure remains typed; recovery retains exact
  responsibility rather than treating uncertainty as absence.
- BUG-LOCALADD-06: FIXED_CLOSED — exact UUID pending payloads remain independently discoverable.
- BUG-SCHEDULER-05: FIXED_CLOSED — pre-31 capability remains valid without API31 special access.
- BUG-ABI-01: FIXED_CLOSED.
- BUG-HISTORY-04: FIXED_CLOSED for its stale-candidate destructive-authority root.
- BUG-MIGRATION-01: FIXED_CLOSED — current History references plus source size/mtime gate source
  retirement.
- BUG-RUNTIME-01: FIXED_CLOSED — exact staged generation/journal/rollback semantics remain.
- BUG-TERMINAL-06: FIXED_CLOSED — unknown/live/recovery-owned entries remain protected.
- BUG-COOKIE-03: FIXED_CLOSED — mutation/projection remains mutex-serialized and configured-cookie
  request construction fails closed without a usable projection.
- BUG-BACKUP-11: FIXED_CLOSED — command_path remains non-portable.
- BUG-PAUSE-03: FIXED_CLOSED — Pause All uses exact snapshot + exact execution lease; broad download
  tag cancellation is absent from pauseAllDownloads.
- BUG-RESUME-01: FIXED_CLOSED — Resume/Retry carries expected identity into non-exported ordinary
  ResumeActivity.
- BUG-TOOLING-01: FIXED_CLOSED — wrapper distribution and extracted-root identities remain
  separated and exact materialized launcher completeness remains enforced.

## L1-L6 coverage

- L1 Durability & recovery: BASELINE / FAIL because BUG-UPDATER-02 can lose same-startup
  reconciliation after active Reset admission rejection.
- L2 Identity & provenance: DEEP / FAIL from prior same-SHA run; fresh review reconfirmed the
  updater provenance cells.
- L3 Concurrency & authority: BASELINE. Dedupe relationship mutation is serialized by
  RestoreMutationAdmission + Room transaction; no separate L3 root established in this run.
- L4 Destructive ownership: DEEP / FAIL due BUG-HISTORY-05.
- L5 Platform contract closure: DEEP from prior same-SHA run; fresh platform/ABI/Resume spot-checks
  found no new L5 root.
- L6 Cross-feature semantic propagation: DEEP / FAIL from prior same-SHA run; updater final-effect
  failure remains, while BUG-HISTORY-05 additionally demonstrates a relationship final-effect loss.

primary_deep_lens: L4 Destructive ownership
primary_deep_selection_reason:
R1/R2 — the newly established valid-dedupe relationship loss is directly a destructive durable
identity/reference failure and no stale-candidate premise is required.

remaining_not_yet_deep:
- L1
- L3

next_not_yet_deep_lens:
L1 Durability & recovery

next_lens_selection_reason:
R1 — BUG-UPDATER-02 is the currently adopted active root and its remaining un-DEEP lens directly
owns the same-startup durable recovery/convergence gap.

## Review retrospective

This is a new same-SHA manual run. No durable IN_PROGRESS manual run existed at trigger start; the
previous manual L2 run was FINAL.

The prior chat-only provisional L4 conclusion was not treated as durable evidence. Exact source was
reread from GitHub and the finding was reclassified after causal separation:
- BUG-HISTORY-04 requires stale candidate destructive authority and is closed.
- BUG-HISTORY-05 requires only a valid dedupe whose rows have asymmetric playlist memberships and
  is therefore independent and pre-existing.

This corrected classification also changes the count conclusion from the prior chat-only
provisional statement: canonical active-scope P2 remains 1, not 2. Repository-wide known P2 roots
visible to the reviewer include active BUG-UPDATER-02 plus out-of-scope BUG-UPDATER-03 and
BUG-HISTORY-05.

No production source, test, configuration, ledger or governance file was changed by this review.
No tests were independently executed. Historical exact-SHA execution remains historical evidence
only.

## Checklist evolution

No Review Checklist, lens-policy or Review Protocol change is required.

Existing v7 rules were sufficient:
- core invariant 12 treats durable relationship keys as identity claims;
- destructive/reference mutation rules require preservation at the actual mutation point;
- sibling isolation prevents valid relationship siblings from disappearing as collateral cleanup;
- Module F/H/C already expose the updater restore defects.

Test evolution for a future BUG-HISTORY-05 correction should use asymmetric relationship fixtures
rather than a symmetric shared-playlist fixture. This is implementation/test coverage guidance, not
a governance change.

## Checkpoint summary

manual_review_run_status: FINAL
overall_active_remediation_verdict: NOT_CLEAN
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
production_active_scope_p0: 0
production_active_scope_p1: 0
production_active_scope_p2: 1
active_finding: BUG-UPDATER-02
active_finding_relation: SAME_ROOT_RESIDUAL
existing_out_of_scope_finding: BUG-UPDATER-03
new_repository_finding: BUG-HISTORY-05
new_repository_finding_severity: P2
new_repository_finding_attribution: PRE_EXISTING_BASELINE_DEFECT
new_repository_finding_active_scope: NO
new_finding_ids_this_run: 1
bug_history_04_status: FIXED_CLOSED
primary_deep_lens: L4
next_lens_hint: L1
known_good_baseline: NOT_CREATED
independent_execution: NOT_EXECUTED

INDEPENDENT EXECUTION: NOT EXECUTED
