# V34 G5 completion review — new format authority SQL trigger conflict root

date: 2026-10-16 KST
checkpoint_kind: COMPLETED_IMPLEMENTATION_RESULT_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_start_parent: bc26e69faa84cae8e62f30e704a628029b0527d0
reviewed_implementation_sha: 57d8d8f1b4993d72177c12ac705f7f2137fa92a1
implementation_parent_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
implementation_tree_sha: f2adb2f34ee679517df672f8ecfea7fc889c7c11
governing_protocol_blob: d136b46290cf259ea07f9a81706f25d89ea72641
plan_tip: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
v34_report_commit: 7db2a16db9bd8daaa9fb08e514918b5332fb3522
v34_report_blob: 6d75a270e3b03c095fca32b818798d2934615d23
v34_prompt_blob: 516ab5b560943de38b94d4d9c9febf004492707c
v34_agent_result: STOPPED_G5_REAL_SEMANTIC_FAILURE
canonical_root_id: BUG-FORMAT-AUTHORITY-TRIGGER-01
canonical_root_status: OPEN
canonical_root_severity: P2
canonical_scope: DOWNLOAD_ACTIVE_BLOCKER_CLOSURE
root_classification: NEW_INDEPENDENT_PRODUCTION_TRIGGER_CONFLICT_NOT_ALIAS_BG01_BG05
root_count_delta: P2_PLUS_ONE
g3_prepublication: 6_EXECUTED_6_PASS
g4_publication: EXACT_X_NORMAL_FF_VERIFIED
g5_postpublication: 21_EXECUTED_20_PASS_1_FAIL_176_NOT_REACHED
exact_failed_test: com.ireum.ytdl.work.FormatBatchHandoffProductionWiringTest#exactWorkerCompletesCachedFormatBatchAndRetiresOnlyItsPayload
source_change_in_reviewer_task: NONE
independent_device_execution: NOT_EXECUTED
historical_live_old_guest_g2: NOT_VERIFIED
CLEAN: NO

## Independent verdict

The implementation remote `checkpoint/pre-baseline-review` is EXACT 57d8d8f, one normal forward implementation commit ahead of db339e1, with unchanged 41-path X tree. The private archived V34 report at exact blob 6d75a2703 documents distinct G3 6/6 actual PASS, authorized normal FF of existing X, and G5 20 PASS/1 FAIL/176 NOT_REACHED across 197 distinct inventory methods. Six migration tests were separately executed prepublication; the 197-inventory methods are not inflated to 203. Report includes actual native Android instrumentation commands and per-method results; ADB native exit 0 on failure does not override JUnit terminal -2/FAIL. Original 177-file sealed evidence and local protected preservation are agent-reported, not reviewer-run host readbacks. No source/test edits or semantic retry happened after first G5 failure.

## Source-semantic causal chain (root OPEN)

At exact X:
- Test `app/src/androidTest/java/com/ireum/ytdl/work/FormatBatchHandoffProductionWiringTest.kt:311-315` seeds download row 1 through production `DownloadDao.insert`, adds cached formats and calls `database.downloadDao.updateWithoutUpsert(row)` BEFORE any batch stage, carrier acceptance, or worker execution.
- `app/src/main/java/com/ireum/ytdl/database/dao/DownloadDao.kt:970-987` passes to Room `@Update updateWithoutUpsertRaw` after its guards; preserved generated `DownloadDao_Impl` was reported by the V34 agent to issue `UPDATE OR ABORT downloads ... WHERE id=?`.
- `app/src/main/java/com/ireum/ytdl/database/FormatAuthoritySchema.kt:24-33,35-49` installs a production `AFTER UPDATE` trigger for `downloads`. Its `stamp` unconditionally attempts `INSERT OR REPLACE INTO format_row_authorities` for `kind='DOWNLOAD',target=CAST(NEW.id AS TEXT)`. The initial insert already gave that exact key an authority record, and `FormatRowAuthority.kt:11-20` declares the `PRIMARY KEY(kind,target)` uniqueness. The trigger is installed and used in the normal format authority transactions rather than a mocked helper.
- SQLite's documented ON CONFLICT precedence means an explicitly conflicted outer statement may supersede the inner trigger write's `OR REPLACE` policy. Independent in-memory SQLite reproduction with a previously existing `('DOWNLOAD','1')` row, an `AFTER UPDATE` trigger containing `INSERT OR REPLACE`, and outer `UPDATE OR ABORT` produces the identical `UNIQUE constraint failed: format_row_authorities.kind, format_row_authorities.target`. Identical setup with bare `UPDATE` instead succeeds. This is a minimal semantic reproduction of the SQL-policy interaction, not an Android device rerun or proof the repaired Room code works.
- V34 reported exact Android failure `SQLiteConstraintException ... (code 1555 SQLITE_CONSTRAINT_PRIMARYKEY)` with production stack `SQLiteConnection.nativeExecute -> EntityDeleteOrUpdateAdapter.handle -> DownloadDao_Impl.updateWithoutUpsertRaw -> DownloadDao.updateWithoutUpsert` and source method at line314. The failure occurs before the worker assertions. It proves a real permitted row update aborts instead of progressing to the intended cached-format handoff.

Independent concrete incorrect effect: a legitimate download row UPDATE cannot succeed when the row's authority stamp already exists and the caller uses generated `UPDATE OR ABORT`; all downstream format batch/worker completion for this request is blocked. The exception does NOT alone prove corruption of persisted data or that the worker would otherwise pass. Result-side trigger stamps and further Room/DAO insert/update writers may share the same structural defect; classify as ONE common SQL conflict-policy root, not one root per failing method. `FormatAuthoritySchema.kt` is ADDED by X (absent at db339e1); this proves introduction of the trigger mechanism in X, not the absence of all historic formatting issues. The previous five BG01–BG05 roots concern exact status membership, durable enqueue ownership, truthful format failure, stale fetch publication and exact cancellation respectively; this ordinary SQL update abort is a different primary cause. BUG-INCOGNITO-01 remains separately open.

## Narrow correction boundary for separately authorized next agent wave

Correct the SQL trigger/stamping design so updates against **existing** `kind,target` rows remain valid under Room-generated `UPDATE OR ABORT` and analogous direct writer conflict policies, while preserving required durable invariants: monotonic fresh revision from the clock, preserved incumbent incarnation for ordinary UPDATE, new incarnation on row creation/recreation and ID change, owner revocation, lineage/completed-lineage discipline, DELETED tombstones, original/changed RESULT_URL invalidation, valid former-owner rejection, exact batch/request authority and recovery. Use a conflict-policy-insensitive update-existing / conditional insert-absent strategy (or equivalently proved SQL design), not a blanket `OR IGNORE`, swallowed exception, trigger disable, retention of stale owner or downgrade of assertions. Verify the affected producer/write surfaces: direct `@Update`/Room-generated UPDATE OR ABORT, `@Upsert`, DAO `@Query UPDATE`, downloads and results INSERT/UPDATE/DELETE and key/url changes, new writer and old schema migration/open/reopen behavior. `CREATE TRIGGER IF NOT EXISTS` is already in X: any changed trigger body MUST define a safe path for a persistent existing database with old trigger definitions; recreating in-memory DBs only does not establish upgrade. Do not silently change schema version or durable migration without separate explicit authorization.

Review the precise failed method's preparation and 20 predecessor PASS for regression confidence, then execute focused actual failed method and separately affected trigger-semantic negative tests on the exact corrected candidate. Rerunning unchanged X without a material correction is forbidden. If an authorized correction is published by normal FF and source semantics hold, run same-SHA remaining 197-method Android closure gate, recording true named outcomes; do not treat old 20 PASS as full current corrected-SHA evidence. Protect original V34 APKs and 177 sealed files, other protected worktrees, dirty/untracked paths and old WAL histories. No new AVD wipe or host cleanup without explicit owner authorization.

## Scope and lifecycle

This is a reviewer completion review and append-only checkpoint, not a request to run a device, create an implementation commit, issue an implementation prompt or change `NEXT_ACTION_OWNER`. Subsequent repair requires a separately authorized prompt lifecycle. The existing V34 handoff launch-ready capsule is stale because the recorded start HEAD is db339e1 and real remote is X; never launch/reissue V34. The private handoff may receive only directly contradicted factual review/implementation summary/count fields under Section 8.2, not unapproved execution routing.

ROOT OPEN, not CLOSED/AMBIGUOUS. Canonical unresolved P2 scope inventory total should become 7 (prior 6 plus this genuinely distinct root), with six original identities unchanged; do not double-count the single trigger mechanism across DAO/test paths. No independent CLEAN/release/closure is claimed.
