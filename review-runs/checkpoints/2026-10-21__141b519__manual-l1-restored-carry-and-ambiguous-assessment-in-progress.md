# Manual 3 continued: L1 durable replay, source/consumer closure, and four ambiguous assessments

date: 2026-10-21
checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: IN_PROGRESS
manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 8852071b921ef023daa939d4393883a4bbd80a32
review_expected_parent: 22b201408e3f1246711b6fc2773699802a735b44
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
pinned_master_plan_part_manifest: remediation/master-plan/README.md@065febdfdccbff12cc6a10a3f55db9bb160293cf
pinned_plan_part_read: 0001.part-0013.part_ALL_PINNED_BLOBS_MATCH
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
pinned_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
pinned_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
pinned_lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
pinned_lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
pinned_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
review_branch_ancestry_from_prior_final: AHEAD_1_BEHIND_0_AT_RESUME
prior_run_start_parent: 8852071b921ef023daa939d4393883a4bbd80a32
current_run_status: SAME_IN_PROGRESS_RESUMED_NOT_COMPETING
current_sha_deep_lenses: L3_PREVIOUS_FINAL,L1_THIS_RUN
primary_deep_lens: L1_DURABILITY_AND_RECOVERY
primary_deep_selection_reason: R1_OPEN_DURABLE_FORMAT_HANDOFF_AND_INCOGNITO_RECOVERY plus R2_PLATFORM_OLD_PERSISTED_AMBIGUITY
lens_coverage_current_sha: L1=DEEP_FAIL;L2=BASELINE_FAIL;L3=DEEP_FAIL_PREVIOUS_FINAL;L4=BASELINE_FAIL;L5=BASELINE_FAIL_EXECUTION_UNVERIFIED;L6=BASELINE_FAIL
remaining_not_yet_deep: L2,L4,L5,L6
next_not_yet_deep_lens: L2_IDENTITY_AND_PROVENANCE
next_lens_selection_reason: R1_BLANK_URL_VALID_COMMAND_AND_STALE_INC0GNITO_SNAPSHOT_PROVENANCE
run_completed_scope: PINNED_GOVERNANCE,REVIEW_AND_PRIVATE_FORWARD_ANCESTRY,WP1_03_STOP_SUPERSESSION,SAME_SHA_BASELINE_L1_L6,L1_DEEP,ALL_TRIGGERED_SOURCE_AND_AVAILABLE_TEST_EVIDENCE,FOUR_AMBIGUOUS_CURRENT_GATE_ATTEMPTS,THREE_OPEN_ROOT_REPRODUCIBLE_SOURCE_CHAINS,SESSION_CANDIDATE_REJECTED_PREVIOUS_CHECKPOINT
run_remaining_scope: RECONCILE_DERIVED_CANONICAL_INDICES,FINAL_FRESH_CHECK,APPEND_FINAL_CHECKPOINT
independent_verdict: NOT_CLEAN
independent_execution: NOT_EXECUTED
android_execution: NOT_EXECUTED
source_changed: NO
historical_checkpoint_mutated: NO
task_or_agent_routing_changed: NO
new_confirmed_roots_in_this_run: NONE
false_positive_removed_root: BUG-FORMAT-SESSION-FIRST-USE-RACE-01
active_discovered_real_roots: 9
active_confirmed_open_p2: 3
active_ambiguous_p2: 4
active_closed_p2: 2
active_unresolved_p2: 7
repository_discovered_real_roots: 151
repository_open: 52
repository_ambiguous: 4
repository_closed: 95
repository_unresolved: 56
canonical_clean: NO

## Exact baseline and deep L1 source proof

The material source delta from db339e1 to 141b519 is ahead 3/behind 0, adding Room64/65 schemas, durable FORMAT_BATCH payload/carrier, per-member authority and worker/recovery, trigger migration and converter changes; source-semantic review is mandatory even if prior tests pass. This run did not use diff-only reasoning.

L1 durable producer/commit boundaries:
- `DownloadViewModel.kt:2923-2939` captures the Processing list, calls `FormatBatchHandoff.capture`, stages exact rows, and awaits scheduler `enqueueAndAwait(...).await()`.
- `FormatBatchHandoff.kt:58-105` persists transitioned rows and the matching `FORMAT_BATCH` request + `FormatBatchPayload` in one Room transaction; a failed transaction commits neither half. `FormatBatchMembership.kt:6-11` partitions only AFTER the too-wide status transition, retaining root BG01.
- `WorkManagerHandoffRecovery.kt:1217-1233,1958-1997,2047-2115,2118-2212,2320-2402` holds exact request UUID and generation identity, awaits WorkManager Operation result outside Restore's global mutex, retains PENDING_ENQUEUE on failing/not-yet-accepted publication, and advances the exact request generation on terminal-without-ack. The long SDK await is deliberately outside global admission.
- `WorkManagerHandoffRecovery.kt:1236-1262,1516-1594` discovers outstanding durable carriers at startup, does not infer old numeric-only payload owner, checks SDK WorkInfo by UUID; ACCEPTED format owner with missing WorkInfo is retained rather than falsely terminated.
- `WorkManagerHandoffRecovery.kt:1162-1215` reads Format recovery carriers during Restore RECONCILING, retries terminal SDK outcome with exact CAS, requires admission of new PENDING enqueue before finishing Restore; no false universal `reconcile()` call while active Restore.
- `FormatBatchHandoff.kt:117-219` checks every member's captured identity, persists PENDING/COMPLETE/TRANSFERRED/SUPERSEDED, refuses terminal notification while one selected member is PENDING; `UpdateMultipleDownloadsFormatsWorker.kt:52-104` returns retry on per-member incomplete effects. Historical exact-141b519 V39-source test provenance records PASS for terminal-without-ack, stopped/failed accepted, row reuse, lost result-after-completion, second member failing.
- `FormatBatchCancellation.kt:16-72` exact UUID cancellation is persisted, current carrier request CAS revoked, native SDK cancel awaited, missing SDK WorkInfo retains tombstone; `CancelWorkReceiver.kt:14-34` rejects broad format tag and dispatches current exact request.
- `FormatAuthority.kt:45-69` session map publication is serialized *inside* `RestoreMutationAdmission.withOrdinaryMutation` global mutex; prior claimed A/B cold-start race is REJECTED and is not a production root. Immutable specific reversal checkpoint is `2026-10-21__141b519__manual-l1-session-root-rejection-in-progress.md`.
- `FormatAuthoritySchema.kt:22-96` revised SQLite stamp source uses UPDATE then insert-if-absent and current-open trigger repair. `DBManager.kt:163-195` installs onOpen and migration v64->65. Actual v65 Room backend runtime outcome on final SHA is not independently established.
- `Converters.kt:63-73` now distinguishes SQL NULL and JSON null; old61/63/64/65 old-row reopening remains a final-SHA execution gate.
- `FormatRecoveryIssues.kt:85-150` stores unproven payload and exact acknowledged status transactionally; a legitimate source-less command misclassified as invalid by `FormatAuthority.proven` is a **real** user-facing false-recovery-issue root, not permission to drop the batch.

L2 baseline: command producer `DownloadCommandFragment.kt:96-98` intentionally converts .txt commands to blank URL, `FormatRequestPolicy.capture` supports command, yet `FormatAuthority.proven` rejects blank URL unconditionally; current full `FormatBatchHandoff.members` checks selected and unselected and worker fails. Source-less command root OPEN.

L3 baseline: one process-global RestoreMutationAdmission mutex protects session first-use; earlier S1/S2 interleaving disproved; remaining concurrency failures are BG01 selection-before-status mismatch and stale Processing incognito queue full-row re-publication, not this rejected candidate.

L4 baseline: unselected B in selected A/background job changes to Saved before membership split; source `saveCapturedProcessing` checks old execution/status but not selection. Incognito DAO selected update lacks status=Processing and queue full-row writer lacks incognito provenance.

L5 baseline: `UpdateMultipleDownloadsFormatsWorker` suspend setForeground is awaited; actual broadcast pending-intent receiver→native completion and v65 trigger/Room DAO/migration execution on final SHA are unverified. Existing historical unrelated worker foreground root is not silently closed.

L6 baseline: current command item from .txt, captured policy/URL, staged all-member format validation, worker, recovery issue, saved status, notification; separate Result chapters old writer→Room converter→repository consumers; queue incognito live edit→old snapshot→worker frozen claim all traced beyond changed helper.

## Current root dispositions with exact attempted AMBIGUOUS resolution

- `BUG-FORMAT-BG-01: OPEN`. Producer exact nonempty selectedIDs[A], captured A/B Processing. `FormatBatchHandoff.stage` mutates A and B before partition; unselected B becomes Saved with no positive user selection. Historical passing `realProducerCapturesAAndBButLeavesLateCProcessing` actually expects B Saved.
- `BUG-INCOGNITO-01: OPEN`. `DownloadViewModel.areAllProcessingIncognito` ANY vs ALL; `DownloadDao.updateIncognitoByIDs` selected update lacks status fence; `queueProcessingDownloads` captures old DownloadItem and `updateForQueueIfSnapshot` whole-row write lacks incognito revision. WP1_03 report blob 52f8bb091c2734edc979fcf506e2259aa4f313b1 STOP, no source patch.
- `BUG-FORMAT-SOURCELESS-COMMAND-MEMBER-01: OPEN`. Existing valid command constructor may blank URL; all-member validation requires nonblank and produces `UNPROVEN_FORMAT_PAYLOAD` instead of selected format completion; separate from BG01.
- `BUG-FORMAT-BG-02: CLOSED`. Source exact receipt plus worker/restore retry and historically reported 35 handoff method PASS on final 141b519 equivalent suite, including `asyncOperationFailureRetainsPayloadAndAdvancesOnlyRequest`, `stagedPendingRestartDiscoversExactRequest`, `succeededWorkWithoutSemanticAckRetainsRetryResponsibility`. Attribution: prior implementation agent, reviewer did not run.
- `BUG-FORMAT-BG-03: CLOSED`. Source per-member durable PENDING/COMPLETE and retry/no premature final notification; historical `partialMemberFailureRetainsReceiptTruthAndOutstandingBatch` reported PASS and inspected published method.
- `BUG-FORMAT-BG-04: AMBIGUOUS`. Source exact incarnation/revision/cache actor/publishOwnedFormats looks fenced. Existing V39 prepublication 7 SQL trigger + 35 handoff tests reported PASS on predecessor 8f1e64; original V39 197-method postpublication test stopped at method47, and later V51/V52 197-method manifests do not include exact 141b519 `FormatAuthorityTriggerProductionWiringTest`. Final v65 Room/outer-OR-ABORT and reopen effect cannot be claimed from another SHA. One bounded external action: separately authorized final-SHA Android runner for seven named trigger tests, including `roomAbortUpsertAndEveryOuterConflictPolicyRevokeExistingOwner`, `deleteRecreateReplacementAndKeyMoveMintDistinctIncarnations`, `resultUrlChangesIdMovesAndRepeatedWritesFenceBothUrls`, `oldRoom65ProductionOpenPreservesDurableStateAndRepairsBeforeFirstDaoWrite`; target authorization remains absent.
- `BUG-FORMAT-BG-05: AMBIGUOUS`. `NotificationUtil.kt:975-1005` binds UUID URI and extra; manifest declares receiver nonexported, `CancelWorkReceiver.kt:14-34` calls exact cancel and suppresses broad tag. V39 request-level test PASS does not observe *delivered* external notification action through Android PendingIntent, receiver, Room CAS, WorkManager terminal and restart. Specific bounded evidence action is a safely authorized exact-final-SHA delivered Android action test; reviewer cannot invoke it on protected guest. No demonstrated current failure, no closed platform effect.
- `BUG-FORMAT-AUTHORITY-TRIGGER-01: AMBIGUOUS`. Old failing trigger source is replaced. The V39 immutable Full Report blob 61f14c6393c9212b73ec80978c30b2ce0d8a3a04 explicitly reports seven prepublication native SQL trigger tests PASS on 8f1e64, including conflict policies, old65 repair and interrupted repair; this was not noticed precisely enough in previous L3 report. It DOES NOT report those methods executed on current final 141b519. Same final-SHA native Room65/64 backend gate as BG04, but distinct historical original wrong-effect root; do not conflate source/runtime evidence with closure.
- `BUG-RESULT-NULL-CHAPTER-CONVERTER-01: AMBIGUOUS`. V39 Full Report explicitly records all seven prepublication Android converter tests PASS on 8f1e64 (SQL null / JSON null / older Room61/63/64/65/reopen); source now handles null correctly. Final SHA 141b519 named method execution not among actual later V51/V52 197-method manifest; without exact final-APK Room migration/reopen end effect do not declare CLOSED. One bounded external action: safely authorized `ResultChapterConverterProductionWiringTest` seven-selector run on current final SHA.
- `BUG-FORMAT-SESSION-FIRST-USE-RACE-01`: REJECTED FALSE POSITIVE, NOT A CURRENT ROOT, NOT CLOSED PRODUCTION DEFECT. Full mutex-scope source contradiction saved in prior current-run checkpoint, count one fewer independent root.

Every AMBIGUOUS has: current source evidence, earlier precise version and test-method evidence, reason neither OPEN nor CLOSED is justified, and exactly one bounded next executable proof action blocked by protected Android/host authority for this reviewer. No evidence of fresh target admission, no executed Android or JVM tests and no pretext of a generic lack-of-tests. No test-only failure was promoted to a new source root.

## Triggered modules and fault/semantic matrix

A FOREGROUND & platform: TRIGGERED, awaited setForeground source seen; Android final effect unverified. B WORKMANAGER acceptance/terminal: TRIGGERED, origin/receipt UUID and terminal no ack covered source + attributed V39 PASS. C SQL identity and native authority: TRIGGERED, v65 revised trigger source and old65 native execution gap AMBIGUOUS. D UI/resource package: TRIGGERED, format issue UI/More entry and required references source-backed; no real rendered device claim. E generated artifact: TRIGGERED, Room64/65 committed schemas and DBManager 65 link source-supported; real v65 opened-migration gate lacking current SHA. F old persisted carrier/migration: TRIGGERED, legacy WorkManager numeric IDs retain debt; old Room61/63/64/65 converter and 65 trigger repair unverified at current final SHA. G key/ID reuse: TRIGGERED, exact Download/Result stamp/UUID and live-authority source/tracked tests; no new root. H frozen configs: TRIGGERED, `FormatRequestPolicy.capture` preference/command materializes bounded strings/booleans; selected set checked too late (BG01). I maintenance/cancel/live owner: TRIGGERED, exact cancel/tombstone, new request CAS vs SDK and Restore quiescence; delivered platform effect unverified. Cross-feature consumer closure: TRIGGERED and traced to source-less command, privacy queue, Result/History/notification and restore.

Fault matrix: T0 stage rollback → no orphaned half receipt (source + V39 PASS); pending enqueue crash → retained carrier (source + V39 PASS); accepted SDK no ack → request recovery (source + V39 PASS); member A complete/member B failing → B pending and retry, no false success (source + V39 PASS); R1 terminal vs R2 UUID → exact generation guard (source + V39 PASS); Restore active → ordinary admission deferred and format RECONCILING, no unsupported clearance (source); mixed selected A/unselected B → incorrect Save of B (OPEN); historical selected incognito edit E2 vs captured E1 → incorrect full-row republish (OPEN); command blank URL chosen/unselected → invalid false issue (OPEN); two first-use sessions A/B pre-cache → blocked by same mutex, earlier candidate REJECTED; 65 trigger/reopen and delivered notification click exact SHA → NOT_VERIFIED, AMBIGUOUS.

Retrospective: earlier reviewer overlooked process-global mutex when asserting cold-session ABA; also incorrectly collapsed V39 prepublication seven+seven native tests into `no test results`. Prepublication results are real but do not establish executed final-SHA gate. Preserve genuine attributed successes and specific missing final-SHA evidence separately; passing test `realProducerCapturesAAndBButLeavesLateCProcessing` reflects an incorrect semantic expectation and must not close BG01.

Checklist evolution proposal only: negative first-use interleaving test must assert real admission-lock scope before being added; correct selected/unselected Processing/BG01 test oracle; add source-less command positive authorization with chosen/unselected batch; add current-SHA v65 trigger/old-row and delivered action gate to exact executed method inventory. NO checklist, Plan or protocol governance mutation. No implementation prompt or task-routing permission is inferred from this manual review.
