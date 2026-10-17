# V39 independent completion review — exact published SHA G5 timeout; cause NOT_VERIFIED

date: 2026-10-17 KST
checkpoint_kind: IMPLEMENTATION_COMPLETION_INDEPENDENT_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
review_start_parent: e0a090ca62d04a470e2c94ed98ef201dd0205e82
implementation_parent: 57d8d8f1b4993d72177c12ac705f7f2137fa92a1
reviewed_implementation_sha: 8f1e64e51db96420aa33026ecf7b1ea8f780a602
reviewed_implementation_tree: 988c9cca1385c057740a117f6d199943694ced05
implementation_commit_ancestry: ONE_NORMAL_FORWARD_CHILD_X_AHEAD_1_BEHIND_0
implementation_changed_paths: 6_EXACT
v39_report_commit: 30c1e7cb15de1bbd056257fdee41945be1e872cf
v39_report_blob: 61f14c6393c9212b73ec80978c30b2ce0d8a3a04
v39_report_path: ytdlnisx/reports/2026-10-17__RESULT_NULL_CHAPTER_V39__result-null-chapter-v39-20261017-f6086319__FULL_REPORT.md
private_report_history: ONE_FORWARD_REPORT_ONLY_ADDITION
v39_prompt_blob: 7881358b9832f14550d3507c6f6fffc363f02f05
review_protocol_blob: d136b46290cf259ea07f9a81706f25d89ea72641
governance_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governance_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
converter_root_id: BUG-RESULT-NULL-CHAPTER-CONVERTER-01
converter_root_status: OPEN
sql_root_id: BUG-FORMAT-AUTHORITY-TRIGGER-01
sql_root_status: OPEN
canonical_active_p2_unresolved: 8
new_semantic_root_confirmed: NO
g5_timeout_root_classification: NOT_VERIFIED
clean: NO
v39_prepublication_distinct_methods: 73_PASS_0_FAIL
original197_exact_sha: 46_PASS_1_FAIL_150_NOT_REACHED_0_SKIP
original197_executed: 47
original197_failure_method: com.ireum.ytdl.database.DownloadFormatNotificationAuthorityProductionWiringTest#mixedFormatNotificationProcessesEligibleSiblingAndPreservesStaleRows
original197_failure: kotlinx.coroutines.TimeoutCancellationException_Timed_out_waiting_for_10000_ms
v39_source_commit_push: VERIFIED_REMOTE
v39_source_worktree_clean: AGENT_REPORTED
v39_protected_evidence_intact: AGENT_REPORTED
v39_report_archived: VERIFIED_REMOTE
new_implementation_prompt_authored: NO
private_handoff_execution_fields_changed: NO
reviewer_executed_android_tests: NONE

## Independent verdict, scope and history

Independently fetched exact original V39 Full Report (Git blob 61f14c6393c9212b73ec80978c30b2ce0d8a3a04), current private/ref, current pinned protocol/governance and implementation commit. Exact GitHub compare X...8f1e64e yielded ahead1/behind0, X as merge-base, one parent, tree 988c9cca1385c057740a117f6d199943694ced05 and exactly six changed paths: new FormatAuthorityTriggerProductionWiringTest.kt, new PreFixRoom65FormatAuthoritySchema.kt, new ResultChapterConverterProductionWiringTest.kt, modified Converters.kt, DBManager.kt and FormatAuthoritySchema.kt. No unrelated expansion or rewritten history in this range. Private report history from 8a0c389ab3a6c880db00ec9552b541b5d1ccae78 to 30c1e7cb15de1bbd056257fdee41945be1e872cf was one forward commit adding ONLY this immutable V39 report.

The agent-reported Windows runs are accurately attributed, not independently rerun by reviewer: both candidate and exact committed-SHA Gradle Android builds ended exit0, with separate source/installed app/test SHA and signer readbacks; 73 distinct prepublication method PASS (35 Handoff + seven converter DAO/old schema + 18 related + seven trigger + six genuine old-writer migrations = 73). Later source-clean exact published-SHA postpublication pair had matching byte digests to prepublication; 197-row G5 manifest independently parsed from archived report: 46 PASS + 1 FAIL + 150 NOT_REACHED = 197, with 47 actually executed. Native adb/outer runner exit0 did not override the one AndroidJUnitRunner JUnit status -2 FAIL. No reported repeated failed method, source correction, source push or silent test success following this failure.

## Source-semantic inspection — separate roots remain open

The published converter Git blob 68b85ae0a72afa48190527d5534def94fe152c9e changes only `stringToListOfChapters(value: String?)` to nullable `MutableList<ChapterItem>?`: SQL NULL and trimmed JSON literal null return null; nonnull arrays still deserialize and copy via `toMutableList()`. The nullable `ResultItem.chapters` producer and Gson writer, generated Room DAO result readback, ResultRepository and ResultMetadataMergePolicy consumer tests, and old61/63/64/65 read/reopen fixtures appear in new production-boundary test blob 4575b84c6fa7b56ec43b28b23d9d4e717b6c13b1. Tests explicitly distinguish JSON null vs SQL NULL, [] vs ordered member lists, corrupted JSON observable failures, reopen and old schema rows. This addresses the V38 crash rather than hiding it by rewriting test input. Source review supports the narrow converter mechanism; method-pass evidence is from archived V39 run. No independent full-app CLEAN inference.

Published FormatAuthoritySchema Git blob 9fc29c124c4e623e8adf1dd034de8180fed5adb8: `stamp` first performs a key-specific UPDATE then conditional INSERT only if absent, rather than INSERT OR REPLACE under Room UPDATE OR ABORT; stores revision/incarnation/owner/lineage/state under writer transaction. `install` begins/ends a transaction; on DBManager.create/onOpen (blob 7a25831fc899599ac085795af182d3d5e21bca75) six trigger definitions are checked and old differing SQL replaced atomically before DAO write. New Android fixture retains historical old65 INSERT OR REPLACE, with direct ownership, rollback, old65 and URL tests in separate seven-PASS class. Same-root source semantics are reviewable and focused evidence passed; remaining original197 gate cannot be silently waived.

## The 10-second notification timeout: distinct failure event, root NOT_VERIFIED

Actual method47 is `com.ireum.ytdl.database.DownloadFormatNotificationAuthorityProductionWiringTest#mixedFormatNotificationProcessesEligibleSiblingAndPreservesStaleRows`, Kotlin coroutine `TimeoutCancellationException` after 10000ms. Independently fetched exact-SHA existing unchanged test blob f450bfe0a153effafa667807dce503b82a336ee8. Its `replayFormatNotification` invokes `viewModel.turnDownloadItemsToProcessingDownloads(ids, deleteExisting=true)` then `withTimeout(10_000L)` polls until `processingItemsJob != null` and `join()`s that job, BEFORE testing retained Active/PostProcessing/Queued/deleted and eligible Saved row effects. Thus the absence of post-await assertions is an evidence gap: no final-state proof. The exception's native stack contains coroutine timeout internals, no application method pointing to blocked read, coroutine publication versus join, SQL transaction, restore gate or worker.

Exact-SHA DownloadViewModel blob 71a1b4931af7430ad09650b857e5a1f6d39a4c9d: `turnDownloadItemsToProcessingDownloads` schedules an outer `viewModelScope.launch(Dispatchers.IO)`, checks restore admission and launches an inner IO job, processes each selected candidate and finally sets `processingItemsJob = job`. Saved-eligible candidates are delegated to `DownloadRepository.transitionFormatNotificationCandidateToProcessing` (blob 8125ef4b1a09872c83be876e4f98f14981553246), which reloads current row and uses a worker execution lock/compare-and-set update; stale noneligible rows should not be mutated. None of the changed converter path consumes this notification test's Result chapters. The test's `@After` cancels/joins the viewmodel then closes the in-memory Room database; no pre-teardown job/row snapshot was captured. Therefore neither a confirmed new production root, a SQL1555 recurrence, a converter recurrence, nor a mere flaky harness classification is supported by existing runtime evidence. Do not inflate P2 or invent a correction to make the timeout green. A subsequent **separately authorized read-only, bounded** diagnostic should pin exact 8f1e64e + existing test source and preserve the original failure; identify the missing awaited stage and original coroutine/row/lock/RestoreGate signals without rerunning unchanged failed tests merely for a green result. Only after causal evidence should an implementation or test contract correction be proposed, with no arbitrary timeout increase.

## Limitations, retrospective and next action

The report says all historical protected entries matched after run, with exactly one owner-approved historically missing V35 `room-runtime-classes.jar` reference to a byte-identical current Room2.8.4 AAR ZIP member; original missing fact, manifests and prior failures remain immutable. This is attributed to implementation-agent local checks, not fresh reviewer access to Windows sealed files. V39 no rebase/clean/reset/force; source candidate clean is agent-reported, GitHub published ref independently verified.

V39 improved on V38 by building and publishing a narrow same-wave correction and actually executing 73 focused tests, including previously blocked worker, seven SQL tests and genuine old-writer migrations; postpublication exact-SHA G5 was actually reached. Remaining defect in execution coverage is **one unclassified timeout and 150 original methods not executed**. Checklist evolution: require a test-observable pre-teardown snapshot distinguishing nullable job-not-yet-published from published-but-unfinished join, with bounded lifecycle/lock/row signals; preserve actual semantic completion instead of using a timeout/outer API return as effect proof. Nothing authorizes changing timeout thresholds or converting timeout into success.

The private NEXT_CHAT capsule is now stale and still points to V39 PERSISTED_NOT_STARTED despite immutable executed V39 Full Report and published implementation advance. This completion-review-only action does not authorize rewriting execution routing, launching V39 again or automatically creating a V40 remediation prompt. Continue via reviewer-owned root-cause classification and separately authorized lifecycle update; the executed report supersedes the stale V39 launch flag. No new prompt, source/device write, inventory change or handoff change was made in this review. Active canonical P2 count remains eight and both corrected roots remain OPEN pending full exact-SHA gate and independent closure.
