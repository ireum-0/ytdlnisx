# Manual correctness review — 141b519 L1 durability/recovery — FINAL

date: 2026-10-21
checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: FINAL
manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 8852071b921ef023daa939d4393883a4bbd80a32
review_expected_parent: 5e4afa96169a148bca6d2390761087615326cdcc
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
implementation_previous_comparable_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
previous_to_current_comparison: AHEAD_3_BEHIND_0
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
pinned_master_plan_manifest_blob: 065febdfdccbff12cc6a10a3f55db9bb160293cf
pinned_master_plan_parts_read: 0001.part-0013.part_ALL_MANIFEST_BLOBS_VERIFIED
pinned_master_plan_manifest_sha256: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
applicable_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
review_start_parent_ancestry: AHEAD_4_BEHIND_0_AT_FINAL_PREFLIGHT
review_mode: TRIGGER_3_RESUMED_SAME_SHA_MANUAL_IN_PROGRESS_NOT_NEW_COMPETING_RUN
run_completed_scope: REVIEW_BASIS_PIN_AND_ANCESTRY,WP1_03_REPORT_SUPERSESSION,REPEATED_FULL_GOVERNING_CHECKLIST,BASELINE_L1_L6,ALL_RELEVANT_TRIGGERED_MODULES,AFFECTED_PRODUCER_CARRIER_CONSUMER_RECOVERY_AND_FINAL_EFFECT,PRIMARY_L1_DEEP,PREVIOUS_L3_COLD_SESSION_CANDIDATE_REJECTION,FOUR_EXISTING_AMBIGUOUS_EVIDENCE_ATTEMPTS,THREE_OPEN_AND_TWO_CLOSED_ROOT_REVIEW,ARITHMETIC_AND_DERIVED_INDICES_RECONCILIATION,PRIVATE_MATERIAL_FACTS_ONLY_UPDATE_AND_READBACK
run_remaining_scope: NONE_FOR_THIS_MANUAL_REVIEW
primary_deep_lens: L1_DURABILITY_AND_RECOVERY
primary_deep_selection_reason: R1_DURABLE_HANDOFF_AND_RECOVERY_DIRECT_ROOT_OWNERSHIP_R2_PERSISTED_NATIVE_GAPS
lens_coverage_current_sha: L1=DEEP_FAIL;L2=BASELINE_FAIL;L3=DEEP_FAIL_PRIOR_FINAL;L4=BASELINE_FAIL;L5=BASELINE_FAIL_EXECUTION_UNVERIFIED;L6=BASELINE_FAIL
current_sha_deep_lenses: L1,L3
current_sha_remaining_not_yet_deep: L2,L4,L5,L6
next_not_yet_deep_lens: L2_IDENTITY_AND_PROVENANCE
next_lens_selection_reason: R1_VALID_COMMAND_SOURCELESS_IDENTITY_AND_INCOGNITO_SNAPSHOT_PROVENANCE
independent_verdict: NOT_CLEAN
new_proven_production_roots_this_run: NONE
rejected_prior_candidate: BUG-FORMAT-SESSION-FIRST-USE-RACE-01
rejected_root_count_delta: -1
active_unresolved_p0: 0
active_unresolved_p1: 0
active_unresolved_p2: 7
active_confirmed_open_p2: 3
active_ambiguous_p2: 4
active_in_scope_closed_p2: 2
active_in_scope_real_root_count: 9
repository_discovered_roots: 151
repository_open_roots: 52
repository_ambiguous_roots: 4
repository_closed_roots: 95
repository_unresolved_roots: 56
canonical_inventory_blob: 90feb73ebfa823d6f50bb50fa53681444958b759
repository_index_blob: 0f6b3f4af7c880d049cad7c2fb700753072d8da1
private_handoff_commit: 65a9e501ec86f838ae23eca15f1f0b41c5264cb4
private_handoff_blob: b5084c0d99d2a6d8fa7056ab4af68266c24022e1
private_handoff_modified_fields_count: 7
private_handoff_lifecycle_changed: NO
implementation_source_changed: NO
new_prompt_created: NO
historical_evidence_rewritten: NO
device_or_local_protected_work_edited: NO
independent_jvm_execution: NOT_EXECUTED
independent_android_execution: NOT_EXECUTED
canonical_clean: NO

## 1. Independent verdict

NOT_CLEAN at pinned immutable implementation 141b519. The current active download campaign has exactly 3 proven OPEN P2 causes, 4 AMBIGUOUS P2 causes with specifically unsupported executed final effects, and 2 in-scope CLOSED roots; seven unresolved P2, zero P0/P1 in scope. Whole repository has 151 distinct real current/historical roots: 52 OPEN, 4 AMBIGUOUS, 95 CLOSED. This review does not imply that any of the four AMBIGUOUS roots is currently a proven error. No new Android/JVM run or app installation occurred.

The previous L3 FINAL was completed, not resumable; this current run was resumed from its own `manual_review_run_status: IN_PROGRESS` checkpoint, with unchanged pinned SHA and governance. All branch movements from the run's `manual_review_start_parent` were forward-only and compatible. No implementation wave is currently evidenced as active. The stale private WP1_03 PERSISTED_NOT_STARTED capsule is overridden by exact immutable same-task intent 7a4718965ab656567cc92f5c36c60fb65486c096, claim df492014df65cc581e33a82f8a99030bc8adeea5, and STOP report blob 52f8bb091c2734edc979fcf506e2259aa4f313b1. That consumed task is not launch-ready and must not be relaunched; this review did not change the capsule.

## 2. Findings and root dispositions

Three OPEN current wrong-effect roots:
1. `BUG-FORMAT-BG-01` — selected A/background Format capture also moves unselected Processing B to Saved before `FormatBatchMembership.partition`. Exact `DownloadViewModel.kt:2923-2939` → `FormatBatchHandoff.kt:58-87` → `DownloadDao.saveCapturedProcessing` → Saved status, without user selection; prior green test explicitly expected B Saved, an incorrect oracle.
2. `BUG-INCOGNITO-01` — `areAllProcessingIncognito` uses nonzero ANY count for ALL; selected `updateIncognitoByIDs` lacks Processing fence; mutable prior DownloadItem snapshot can overwrite a later incognito edit through `updateForQueueIfSnapshot` full-row requeue, so worker receives older privacy. A single linked semantic root, and the WP1_03 implementation task STOP published no fix.
3. `BUG-FORMAT-SOURCELESS-COMMAND-MEMBER-01` — valid .txt command constructor blanks URL (`DownloadCommandFragment.kt:96-99`), `FormatRequestPolicy.capture` accepts command, but `FormatAuthority.proven` requires all member URLs nonblank; `FormatBatchHandoff.members` rejects selected/unselected carrier and worker/Restore retains a false `UNPROVEN_FORMAT_PAYLOAD` user-action issue instead of completing a valid selection. Independent of BG01 status overreach.

Two CLOSED:
- `BUG-FORMAT-BG-02` — durable same-transaction FORMAT_BATCH payload+carrier, exact UUID WorkManager admission, awaited Operation acknowledgement, restart/retry after SDK finish without semantic receipt, selected V39 same-final-source handoff test evidence attributed PASS.
- `BUG-FORMAT-BG-03` — per-member PENDING/COMPLETE and worker Result.retry prevent swallowing later sibling failure, suppress premature success notification, V39 `partialMemberFailureRetainsReceiptTruthAndOutstandingBatch` attributed PASS.

Four AMBIGUOUS (original wrong-effect known historically, current final-SHA binary closure not proven):
- `BUG-FORMAT-BG-04`: source narrow format-only CAS and revision/incarnation/cache fences inspected; new Room65 SQL conflict-policy and old-state native final effect lacks exact 141b519 execution. Prepublication V39 on 8f1e64 included 7 trigger PASS and 35 handoff PASS, but no final-141b519 `FormatAuthorityTriggerProductionWiringTest` execution was found in actual later 197-method manifests. One bounded next evidence: after separately authorized safe Android target, execute exact-final-SHA seven trigger tests including OR ABORT, old65 reopen and interrupted-repair paths.
- `BUG-FORMAT-BG-05`: notification action URI/UUID + receiver exact cancel + Room tombstone/native await source checked; prior request-scope Android tests do not prove real delivered PendingIntent click→receiver→Room CAS→WorkManager terminal/recovery. One bounded next evidence: safely authorized final-141b519 Android delivered notification action with old/new request competition. Source does not prove a current wrong effect; native final-effect remains unverified.
- `BUG-FORMAT-AUTHORITY-TRIGGER-01`: older X code provably broke actual Room UPDATE OR ABORT through inherited SQLite trigger conflict policy. Current `FormatAuthoritySchema.stamp` uses UPDATE then guarded INSERT, onOpen/migration trigger repair. V39 prepublication 7 native trigger selectors PASS at earlier SHA 8f1e64, but the exact final-141b519 actual native effect test outcome remains unverified. One bounded next evidence is the same exact-final-SHA seven-selector Room trigger/old-version reopen run; distinct historical root, not an extra test-error count.
- `BUG-RESULT-NULL-CHAPTER-CONVERTER-01`: old Gson null.toMutableList failed, current `Converters.kt:63-73` handles SQL NULL and JSON null. Existing immutable V39 report records 7 prepublication Android converter selectors PASS at 8f1e64 (current DAO, corrupt rows, old61/63/64/65 migration and reopen). They were **executed on the earlier candidate**, contrary to previous loose `no test` wording, but none is evidenced as an executed exact-141b519 final APK method. One bounded next evidence: separately authorized seven-selector final-SHA Android `ResultChapterConverterProductionWiringTest` run.

Rejected candidate, **not** a fourth status or a CLOSED production bug: `BUG-FORMAT-SESSION-FIRST-USE-RACE-01`. Prior L3 supposed caller A could DB-commit SESSION S1 and caller B DB-commit S2 before A `sessions.putIfAbsent` S1. `FormatAuthority.transaction` encloses both DB transaction and map insert in `RestoreMutationAdmission.withOrdinaryMutation`; its global mutex excludes such independent B admission before A exits. App manifest has no alternate process producer established. Historical immutable L3 source assertion was false-positive. See immediate first resumed checkpoint `manual-l1-session-root-rejection-in-progress.md`; never silently recast as CLOSED regression.

## 3. L1 DEEP and L1–L6 baseline + trigger execution

L1 DEEP_FAIL: exact FORMAT_BATCH producer/stage/Operation acknowledgement and durable startup/Restore replay were inspected end to end. T0 failed Room batch stage rolls back status+carrier; crash after staged pending retains exact retry; late accepted SDK work is checked by exact WorkInfo ID; terminal SDK without worker semantic acknowledgement retains carrier; member A succeeded/member B throws leaves B PENDING; stale request R1 cannot publish against R2; Restore active admission blocks ordinary effects and dedicated RECONCILING resumes the durable format namespace. Relevant existing final-source tests were separately attributed V39 PASS, not reviewer executed; no implicit CLEAN. L1 actual failing paths remain BG01, source-less command and incognito queue durable wrong publication; Room65/old source and delivered action exact-final native gates unresolved.

L2 BASELINE_FAIL: URL-less command misclassified despite valid command policy, download/Result row key reuse and saved request identity source reviewed; incognito edits lack snapshot provenance. L3 BASELINE_FAIL (previously DEEP): Global mutex invalidates false S1/S2 claim, yet BG01 and stale incognito update still violate correct authority. L4 BASELINE_FAIL: B state change outside selected set; privacy overwrite after user's newer state. L5 BASELINE_FAIL/EXECUTION_UNVERIFIED: worker awaits setForeground, but notification delivery and new Room65/old-state executed effects absent; preserved unrelated platform root not automatically closed. L6 BASELINE_FAIL: command constructor→capture policy→batch validator→worker→issue UI, Result chapter SQL converter/reopen, incognito source snapshot→queue→worker privacy, Restore and notification final effects all traced; not diff-only.

Trigger matrix: A foreground platform TRIGGERED source awaited, native effect not independently tested; B WorkManager receipt and retry TRIGGERED source and V39 evidence; C SQLite native authority TRIGGERED Room65 tests pending current SHA; D packaged recovery UI TRIGGERED source resource bindings seen, runtime render unknown; E external generated schema TRIGGERED JSON64/65 committed, native old-state compatibility unproven; F persisted legacy carrier/migration TRIGGERED old61/63/64/65 source/prepublication tests, final SHA unverified; G identity and key reuse TRIGGERED durable incarnations, UUID and membership source/tests; H frozen policy TRIGGERED FormatRequestPolicy bounded preferences/command materialization, selected set too late BG01; I exact cancellation vs live owner TRIGGERED durable tombstone, no real delivered action test. Trigger execution does not mark nonprimary lenses DEEP.

## 4. Review retrospective and checklist evolution

Avoidable previous reviewer errors: (1) the cold-session map-publication race ignored the enclosing process-global mutex; treating database and cache steps as independent was unsound; (2) earlier V39 prepublication 7 SQL-trigger + 7 converter executed method PASS evidence existed, though final 141b519 gate remained genuinely missing; reporting `no tests` instead of `none executed at final SHA` obscured provenance; (3) existing green BG01 test encoded unjustified unselected B status change. Corrected each here without reproducing historical immutable checkpoint files.

Checklist not modified. Proposed test and oracle changes only: assert actual global lock admission before devising a cold-session interleaving, require selected A/unselected B retain B's Processing state, valid .txt command selected/unselected positive provenance, and require named final-SHA native SQL trigger, old-schema chapter reopen and delivered notification-action method results for ambiguous closures. No generic test PASS is inferred from source changes.

## 5. Verified arithmetic and state handoff

Active inventory `review-runs/inventories/ACTIVE_DOWNLOAD_REMEDIATION_INVENTORY_V1.md` read-back blob 90feb73ebfa823d6f50bb50fa53681444958b759: 9 real active roots = 3 OPEN + 4 AMBIGUOUS + 2 CLOSED, CANONICAL_P2 unresolved 7. Whole-repository index `review-runs/inventories/REPOSITORY_FINDINGS_INDEX_V1.md` read-back blob 0f6b3f4af7c880d049cad7c2fb700753072d8da1: exactly 151 root rows = 52 OPEN + 4 AMBIGUOUS + 95 CLOSED, 56 unresolved. Current branch identities were fresh checked before this FINAL append, including private forward movement only adding independent plan text and then fact-only handoff change. No divergence/rewrite detected.

Private `ytdlnisx/NEXT_CHAT.md` changed only seven directly contradicted factual lines: CANONICAL_P2=7; ACTIVE_REMEDIATION_OPEN_P2_COUNT=7; remove rejected SESSION root from CANONICAL_OPEN_ROOTS, ACTIVE_DOWNLOAD_BLOCKERS and CANONICAL_CONFIRMED_OPEN_ROOTS; whole repo ROOTS_TOTAL=151 and OPEN=52. Read-back private blob b5084c0d99d2a6d8fa7056ab4af68266c24022e1. Historical report, task intent+claim, any prompt/agent lifecycle and implementation remained unchanged.

Final result: pinned same SHA 141b519 **NOT_CLEAN**. Next new manual review of same SHA may promote L2 under R1 (if its prerequisites/evidence permit) but this is a coverage hint, not a new authorized task. Missing required executable effect actions remain individually bounded external gates, not passed. There is no canonical launch-ready prompt because WP1_03 was consumed with STOP report.
