# Manual correctness review 141b519 — L3 concurrency/authority — IN_PROGRESS

date: 2026-10-21
checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: IN_PROGRESS
manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 82cf949a6de745d1b4e678ec379034c8a0eb9fde
review_expected_parent: 82cf949a6de745d1b4e678ec379034c8a0eb9fde
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
applicable_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
review_mode: MANUAL_3_WITH_NO_ACTIVE_IMPLEMENTATION_COMMIT
active_agent_reservation: WP1_03_CONSUMED_STOPPED_BY_EXACT_MATCHING_PRIVATE_REPORT_52f8bb091c2734edc979fcf506e2259aa4f313b1
scope: NEW_SHA_BASELINE_L1_L6_TRIGGERED_MODULES_PRIMARY_L3_DEEP
run_completed_scope: PINNED_REFERENCES,REVIEW_CHECKLIST_AND_LENS_POLICY,NEW_SHA_DELTA,FORMAT_PRODUCER_CARRIER_WORKER_RECOVERY_SAMPLING,NEW_ROOT_SESSION_INIT_CONCURRENCY_PROOF
run_remaining_scope: BASELINE_L1_L6_COMPLETION,TRIGGER_MAP_AND_CONSUMER_CLOSURE,EXISTING_ROOT_DISPOSITION_AND_EVIDENCE_GATE,PRIMARY_L3_DEEP_COMPLETION,FINAL_RECOUNT
primary_deep_lens: L3_CONCURRENCY_AND_AUTHORITY_IN_PROGRESS
lens_coverage_current_sha: L1=IN_PROGRESS;L2=IN_PROGRESS;L3=DEEP_IN_PROGRESS;L4=IN_PROGRESS;L5=IN_PROGRESS;L6=IN_PROGRESS
independent_execution: NOT_EXECUTED
production_source_changed: NO
verdict: NOT_CLEAN
initial_root_status_decision: OPEN
new_root_id: BUG-FORMAT-SESSION-FIRST-USE-RACE-01
new_root_severity: P2
new_root_count_pending_canonical_reconciliation: +1
new_root_attribution: INTRODUCED_BETWEEN_DB339E1_AND_141B519
clean_review_basis: NONE_REPOSITORY_WIDE_CLEAN_REVOKED

## Confirmed new semantic root: concurrent first use can permanently mismatch the format session

Exact source: `app/src/main/java/com/ireum/ytdl/work/FormatAuthority.kt` at 141b519, lines 41-69. `sessions` is a process-wide ConcurrentHashMap keyed by canonical DB path. `transaction` reads `sessions[key]` **inside** `db.withTransaction` (line 49), allocates a fresh serial and commits `FormatRowAuthority("SESSION","process",serial,serial)` when it is null (lines 50-54), but publishes the corresponding in-memory `sessions.putIfAbsent(key,result.first)` only **after** `db.withTransaction` returns (line 67), with no atomic coupling or compare against the currently persisted SESSION. Every subsequent transaction with an existing map entry enforces persisted revision equality by throwing `IllegalStateException("Format database session superseded")` (lines 60-62).

Concrete reachable source ordering: two independently admitted first format consumers A and B enter before the map is populated. Serialized Room transaction A commits durable SESSION=S1 but suspends before its outside-transaction map write. B observes empty map, commits SESSION=S2. A then writes S1 into the empty map before B's corresponding `putIfAbsent(S2)`. In-memory S1 is now stuck while DB has S2. Future `FormatAuthority.transaction` calls all fail check S2 != S1 until process restart or some unproven external reset. This is **a possible production interleaving established by code**, not an observed device race.

Actual producers: `FormatBatchHandoff.capture` and `FormatAuthority.captureForeground` via `DownloadViewModel.captureFormatRefresh` can independently initiate first format transactions. Process-start reconciliation/cancellation also calls `FormatAuthority.transaction`. Durable carrier: `format_row_authorities` SESSION row, which can be overwritten by the second starter. Consumer/recovery: `FormatBatchHandoff.stage/prepareFetch`, `FormatAuthority.publish`, `FormatRecoveryIssues`, and `FormatBatchCancellation`; all indirectly use the same session check. Final incorrect effect: legitimate format publication, cancellation, and retained-hand-off convergence can repeatedly fail with rejected authority despite valid persistent batch/member IDs. The format worker catches exception in `UpdateMultipleDownloadsFormatsWorker.doWork` lines 102-104 and requests retry; retry repeats the mismatched guard. A default onOpen trigger install never reconciles the in-memory singleton.

This is not `BUG-FORMAT-AUTHORITY-TRIGGER-01` (SQLite trigger conflict-policy inheritance during row writes), nor BG02 (enqueue acceptance and retry ownership), nor BG04 (member/configuration stale-format publication): the bad owner is the **global session initialization** even without member changes, missing WorkManager acceptance, or SQL uniqueness conflict. A minimal prevention contract is one DB-serialized cold-start installation + map/persisted session consensus; never race a process cache behind the transaction; preserve process-restart foreground revocation and all independent format consumer APIs. Deterministic test: hold A after DB commit/before map publication, let B commit S2, then release A map publication before B and assert a third format consumer succeeds (currently throws) and no pending handoff or cancellation is stranded.

No independent tests, build or device commands were executed. No canonical inventory/count change or private handoff write is claimed by this intermediate checkpoint. Final root status/count reconciliation follows after remaining manual scope is completed.
