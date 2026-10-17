# V38 independent completion review — correct-CWD build succeeded, separate nullable chapter converter failure STOP

date: 2026-10-17 KST
checkpoint_kind: IMPLEMENTATION_WAVE_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_start_parent: 43e2aadb9e06d2f82b2663daed51edfb1d9e06f5
reviewed_implementation_head: 57d8d8f1b4993d72177c12ac705f7f2137fa92a1
reviewed_implementation_tree: f2adb2f34ee679517df672f8ecfea7fc889c7c11
protocol_blob: d136b46290cf259ea07f9a81706f25d89ea72641
plan_tip: 8491528b730abea17de22013bca4288e1549a39e
ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
v38_prompt_blob: f6cc8ee6b439bc14082ff7bb5408b75d742b5f64
v38_archived_report_commit: a0f9b922df4b9e527189df50fc348808ff319062
v38_archived_report_blob: 077dc8bba101871f05de759bd644e1a0bf6e2f96
v38_archived_report_path: ytdlnisx/reports/2026-10-17__FORMAT_AUTHORITY_TRIGGER_V38__format-authority-trigger-v38-20261017-4c92e0dc__FULL_REPORT.md
v38_execution_result: STOP_OTHER_ROOT_NULL_CHAPTER_CONVERTER
v38_verified_native_child_cwd: PRESERVED_V35_CANDIDATE
v38_build_1: FAILED_NEW_TEST_COMPILATION
v38_build_2: PASS_EXIT_0
v38_original_first_sql1555_test: PASS_AGENT_RECORDED
v38_handoff_distinct_methods: 22_PASS_1_FAIL_12_NOT_REACHED
v38_failure: NullPointerException_fromJson_must_not_be_null_Converters_kt_63
v38_seven_trigger_methods: NOT_REACHED
v38_six_old_writer_migrations: NOT_REACHED
v38_197_published_sha_methods: NOT_REACHED
v38_source_commit_push: NONE
v38_staged_changes: NONE_AGENT_REPORTED
v38_local_new_test_final_sha256: c8e053abfe34cf79376650bb67d0704cb0245893393806278d3c2656a31f0965
v38_local_other_3_scoped_file_hashes: V35_PRESERVED_AGENT_REPORTED
v38_protected_evidence: NO_UNEXPECTED_DRIFT_AGENT_REPORTED
v38_historical_single_file_exception: ONE_ITEM_ONLY_OLD_PATH_MISSING_STILL
existing_canonical_root: BUG-FORMAT-AUTHORITY-TRIGGER-01
existing_canonical_root_status: OPEN
existing_canonical_active_unresolved_p2: 7
new_observed_root_id_provisional: BUG-RESULT-NULL-CHAPTER-CONVERTER-01
new_observed_root_status: OPEN
new_observed_root_inventory_disposition: DEFERRED_WITHIN_ACTIVE_SCOPE
new_observed_root_counted_in_active_p2: NO_PENDING_SEPARATE_SCOPE_AUTHORIZATION
new_observed_root_severity: P2_PROVISIONAL_FOR_DEPENDENCY_TRIAGE
reviewer_executed_builds_or_android_tests: NONE
CLEAN: NO
new_implementation_prompt_authorized: NO
private_handoff_controls_changed_by_review: NO

## Evidence and independent verdict

Fetched the exact private V38 Full Report and verified normal forward private history: one commit from the preceding V38 prompt/handoff tip 9e91895dab0cae210c8211c778c4dc3ec03e71f7 to a0f9b922df4b9e527189df50fc348808ff319062; only one uniquely named V38 report file added. Live implementation branch stayed at X 57d8d8f and live review at 43e2aadb9. The archived execution is attributed to the implementation agent, not independently rerun by the reviewer.

V38 explicitly checked the owner-authorized **one** missing historic V35 `room-runtime-classes.jar` item against the current byte-identical Room2.8.4 AAR member, retaining the original sealed missing fact and 66/67 original physical entries. Reported 1247 other historical sealed entries matched, no unexpected mismatches. Native child ProcessStartInfo.WorkingDirectory twice matched the preserved V35 candidate. BUILD_1 compiled production source but failed two straightforward new instrumentation-test compile cases; after the two scoped test-only corrections, BUILD_2 was executed on the actual candidate and returned exit 0, with both app and test APKs built/verified and admitted emulator app/test pair installed. Four local dirty paths persist; only FormatAuthorityTriggerProductionWiringTest.kt changed beyond V35 pin to SHA256 c8e053abfe34cf79376650bb67d0704cb0245893393806278d3c2656a31f0965. Original V35 production DBManager.kt and FormatAuthoritySchema.kt remain their prior pinned hashes; all four remain **UNCOMMITTED**.

First focused prior SQLite1555 reproducer `com.ireum.ytdl.work.FormatBatchHandoffProductionWiringTest#exactWorkerCompletesCachedFormatBatchAndRetiresOnlyItsPayload` PASS, then additional targeted handoff methods yielded aggregate 22 PASS, one FAIL and 12 NOT_REACHED. Failing `realLegacyWorkerCreatesRowlessIssueWithoutBorrowingH2` reported actual Android JUnit failure `java.lang.NullPointerException: fromJson(...) must not be null`, originating in unchanged production `app/src/main/java/com/ireum/ytdl/database/Converters.kt:63`, not the known SQLite trigger conflict. ADB command exit0 for this method did not signify JUnit PASS; the agent preserved raw first failure. Test abort occurred **before** the intended legacy H2 worker protocol, so this failed test proves the converter crash, NOT failure/success of the late worker semantics.

Independent exact-X source inspection:
- `Converters.kt` Git blob 3c4c0bdaa41d504349d0a14f3189eb0726c828b8: `stringToListOfChapters(value: String?) = gson.fromJson(value, Array<ChapterItem>::class.java).toMutableList()` (line63), with `listOfChaptersToString(list: List<ChapterItem?>?) = gson.toJson(list)` (lines65-69).
- `database/models/ResultItem.kt` blob e0265756c7ab31e799a1ce694b98e35b3724c5f2 permits `var chapters: List<ChapterItem>?` at line27. This is a production model contract, not just a harness field.
- `database/dao/ResultDao.kt` blob d345739445aec9f1b8d8bcd8f82f55f9866d9eb6 supports true Room insert and readback `insert(item)` / `getResultByID(id)`.
- `FormatBatchHandoffProductionWiringTest.kt` blob 7e8a9cac66307033cea7bc8af9d5c0d9186277cb: `insertResult` uses `ResultItem(...,chapters=null)` and inserts via actual `resultDao.insert(item)` then `getResultByID(id)!!` (lines667-673), called by failing method at lines357-359.
- Actual reported generated `ResultDao_Impl` readback invoked converter; `Gson.toJson(null)` yields JSON `null`, while `fromJson` can return Java null; Kotlin `toMutableList()` on that null crashes. This producer -> Room persisted JSON null -> DAO readback converter -> NPE is an independently clear concrete wrong effect at current X. The archive also identifies another production chapter-null construction at HistoryFragment.kt:1569; the reviewer did not independently re-fetch that exact source path, so production consumer breadth, old-schema migration and appropriate null-versus-empty policy remain for a *separately authorized* diagnostic/repair. Never silently change the failing test fixture to a nonnull value to manufacture green.

## Root classification, governance and count

`BUG-FORMAT-AUTHORITY-TRIGGER-01` stays OPEN. One earlier exact failure reproduction now passed with uncommitted patch, but seven newly written direct trigger regressions, six genuine migration methods, all 197 final exact-published-SHA methods remain NOT_REACHED and no implementation publication exists. Thus no CLEAN, closure or on-device full root proof.

The null-chapter converter is a **distinct** production serialization root from SQLite (kind,target) conflict; one report/test method should not double-count it with authority-trigger root. Checked canonical active inventory `review-runs/inventories/ACTIVE_DOWNLOAD_REMEDIATION_INVENTORY_V1.md`, repository derived finding index and NEXT_CHAT root/alias summary; none has a preexisting chapter-converter root or alias. Because this real DAO readback failure gates the *background format handoff test suite*, it is inside the wider `DOWNLOAD_ACTIVE_BLOCKER_CLOSURE` campaign even though OUTSIDE the much narrower V38 active implementation root. It must **not** be incorrectly labeled outside the remediation campaign.

Explicit required disposition: `DEFERRED_WITHIN_ACTIVE_SCOPE` for the new distinct root. Concrete governance/dependency reason: V38 authorization explicitly forbade unrelated new production converter edits, and this chat's user supplied a completion report rather than separate permission for a new converter/schema/old-state correction. The exact expected nullable serialization contract and all producer/consumer, historical-database read paths have not yet been separately scoped for authorized repair. Record it as an OPEN proven current production crash and a **deferred dependency**, not as CLOSED, a mere harness failure, or a silently adopted/automatically launched eighth remediation task. Existing authorized active inventory remains seven unresolved P2; report provisional P2 for new deferred root separately. A future user-approved focused converter task should verify exact historic persisted SQL null, JSON null and nonnull/empty arrays, readback from current and supported old Room schemas, real downstream History/Result and handoff consumers, process restart, and failure-before-worker staging; then resume the interrupted handoff method without disguising causality. Count/adoption requires a separate explicit inventory disposition, not silent mutation. If governance later chooses adoption, reconcile count once and preserve all prior decisions.

## Review retrospection and execution boundary

V38 eliminated earlier wrong-CWD and missing-JAR uncertainty: correct source built, the first known failing trigger-sensitive worker method passed, no protected drift. Two new-test compilation errors required a single materially changed second build and were resolved without touching production. The next observed crash is in a shared converter before the method's intended handoff contract, so the other-root stop and preservation of first failure were correct. Do not repeat V38, silently change `chapters=null` to `emptyList()`, or claim this failure is evidence against the newly compiled SQLite patch. However the direct seven/6/197 gates are *not evidence of pass*; the existing root cannot close. If a future separately authorized, accurately scoped task is launched, preserve all four current candidate hashes and all previous run directories, original dirty work, APKs, reports, refs and runtime first-failure evidence. Do not automatically assign a V39 implementation or relax user-authorized one-file exception to anything else.

The remote private `NEXT_CHAT.md` still records V38 as PERSISTED_NOT_STARTED even though this verified immutable V38 report proves completion. Its stale **execution routing** is not permission to relaunch V38. Under REVIEW_PROTOCOL §8.2 a review-only checkpoint may not modify prompt execution state, NEXT_ACTION_OWNER or EXACT_NEXT_ACTION without separate routing authorization. Leave the stale capsule untouched; report discrepancy and require an explicit authorized lifecycle transition before any next bootstrap.

## Checkpoint effect

Append-only reviewer evidence only. No source/device execution or publication, no retroactive historical evidence change, no new task/prompt, and no private handoff control rewrite. Independent finding proof relies on pinned-X source and the agent's accurately attributed real Android instrumentation record. Later implementation/governance movements must be reconciled under protocol; no assumption of their absence beyond this exact fresh review.
