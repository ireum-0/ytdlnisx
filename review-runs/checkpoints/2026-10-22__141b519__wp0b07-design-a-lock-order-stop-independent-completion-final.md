# WP0-B07 Design A local foundation — independent protected completion/lock-order STOP review

checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW_SOURCE_SEMANTIC_STOP
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
expected_review_parent: 4718da8fb30d63912a50557bacd0ec2de0426312
reviewed_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
private_full_report_commit: cf39450228b4a2ea5fe3988da4bf4d302996d89a
private_full_report_path: ytdlnisx/reports/2026-10-22__WP0B07__wp0b07-20261022-15183bd7__FULL_REPORT.md
private_full_report_blob: 19cc128ae3da1a7e06690e3221484fda61fb953b
task_instance_id: WP0B07_UNDO_TERMINAL_A_IMPLEMENT_20261022_141B519_01
prompt_origin_commit: aacc2a513dbaca7ae45608e6fe014d160b5bc4ab
prompt_blob: b300b05c998d3add19028f3f2213d12827bde642
start_intent_commit: ca21369f64ded42aa326228ec6883cf7b09b00e3
start_intent_blob: 6caad661c17b0dfa9d1f0a44334a77be86a5a334
start_claim_commit: 25eda6fdcf5918e1a595593cd3b9f817dd844b96
start_claim_blob: 087385b9031053e2507fd08a23cd58cea5ce19db
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
result: CONTRACT_GAP_SOURCE_SEMANTIC_LOCK_ORDER_STOP_CORROBORATED
source_publication: NO
source_changes_remote: NO
local_only_drafts: AGENT_REPORTED_FIVE_FILES
local_draft_exact_bytes_reviewer_accessible: NO
local_compilation_agent_report: PASS_EXIT_0
full_unit_agent_report: 813_PASS_0_FAIL_0_ERROR_0_SKIP
focused_kotlin_agent_report: 32_PASS
additional_lock_utility_agent_report: ONE_PASS
actual_test_methods_reexecuted_by_reviewer: ZERO
android_methods_executed: ZERO
exact_pushed_sha_closure: NOT_REACHED
full_implementation_ready: NO
first_stage_source_closed: NO
independent_source_cycle: R_THEN_L_AND_L_THEN_R_CONFIRMED_AT_EXACT_CALL_SITES
actual_android_deadlock_runtime_observed: NO
potential_new_cross_feature_root_alias_and_status: NOT_VERIFIED_OUTSIDE_THIS_COMPLETION_DISPOSITION
canonical_root_status_changes: ZERO
new_counted_root_count: ZERO
P2_OPEN: 4
P2_AMBIGUOUS: 4
P2_UNRESOLVED: 8
repository_OPEN: 53
repository_AMBIGUOUS: 4
repository_CLOSED: 95
CLEAN: NO
manual_L2_status: IN_PROGRESS_UNCHANGED
historical_wp0b05_index_cause: NOT_VERIFIED
new_implementation_prompt_authorized_by_completion_report: NO
private_handoff_write: NONE
new_prompt: NONE

## 1. Verified private lifecycle and source authority

Read current `ireum-0/private:ytdlnisx-review` at `cf39450228b4a2ea5fe3988da4bf4d302996d89a`, protocol and stale WP0B07 handoff, immutable prompt and archived Full Report. Read matching unique task intent and claim: exact task, claimant `15183bd7af11b8f358daa97f3f71c746`, prompt path/blob/origin/source/protocol identity, claim points to original intent commit/blob. GitHub compare from launch tip `8839b61d06fdc17ba9d602834c06a18b48f0753b` to `cf39450228b4a2ea5fe3988da4bf4d302996d89a` is ahead 3/behind 0, exactly three additions in normal one-parent sequence: intent, claim, Full Report. Current nontruncated GitHub tree has 479 entries and confirms the report path/blob `19cc128ae3da1a7e06690e3221484fda61fb953b`. GitHub compare shows NO modified or removed original paths. The agent's statement that 463 preceding private blobs remain unchanged is consistent with this remote compare, but reviewer did not individually hash all 463 file bytes.

Live implementation remains exactly `141b5199592369ab953f52c55ebdb72ba300f1a4`, not an implementation WP0B07 publication. Review/plan/ledger basis was not advanced by the agent. All five changed/new local files are reported as uncommitted local-only candidates under `C:/Users/dh2/AppData/Local/Temp/ytdlnisx-wp0b07-20261022-15183bd7/candidate`. They are not present at remote implementation tip, so reviewer cannot independently inspect the uncommitted code or rerun its changed-source tests solely from GitHub. Report records typed versioned `UndoTerminalReceipt.kt`, entity adapter, DAO exact CAS, and two new JVM test files; the agent explicitly says producers/resolvers/Restore/startup were NOT integrated. Do not treat compilation as complete repair or infer an implemented source difference on the unchanged remote SHA.

The old `NEXT_CHAT.md` capsule still reports WP0B07 `PERSISTED_NOT_STARTED`, but independently verified matching one-shot intent+claim+STOP Full Report means the instance is CONSUMED. The original exact prompt MUST NOT be relaunched. Review-only completion permits append-only reviewer evidence but does not authorize a new prompt or rewriting workflow routing; immutable history and existing handoff preserved.

## 2. Actual test evidence strength

Agent Full Report specifies an initial failed compiler-launch bootstrap missing a cached kotlin-reflect jar; NO Kotlin test or source compiler ran in that first attempt, and the Java native exit was not recorded. Agent then fixed the scratch-owned compiler classpath (no production change) and reports an actual Kotlin JUnit 32-method run PASS, Gradle `:app:compileDebugKotlin :app:testDebugUnitTest` PASS with 813 methods and 95 XML classes, native command exit 0, and a separate one-method actual lock-utility test PASS, exit 0. The 32 focused tests overlap the later 813 suite; these are 846 method invocations, NOT 846 distinct regression behaviors. The prior WP0B06 SQLite model's 384 results are not part of the present 813.

The reviewer read the detailed method ledger inside the immutable Full Report, but did NOT receive independent local XML, candidate bytes, Android device, or runtime logs. The compiler exit codes and method outcomes above are **agent-reported test evidence with archived provenance**, not independently executed tests or production Android verification. The report's utility test drives actual `RestoreMutationAdmission.withRestoreMutation` and `withDownloadWorkerExecutionSideEffectLease` with deterministic coroutine start/deferred synchronization; two opposite waits remain blocked until the test cancels both jobs. It is utility-level corroboration, not a real Worker/UI/History filesystem deadlock execution. The stopwatch watchdog did not fire according to report. No Room lifecycle, Android SharedPreferences, process-death, old-version DB, connected test, APK assembly, install or exact-published-SHA closure occurred; source not published.

## 3. Independently corroborated production lock graph and wrong-effect risk

Read exact production sources at `141b5199592369ab953f52c55ebdb72ba300f1a4`, not solely the agent's proposed graph:

- `DownloadRepository.kt` git blob `8125ef4b1a09872c83be876e4f98f14981553246`, `cancelActiveQueuedWithResult` lines 4348ff: `withOrdinaryMutation` surrounds the whole `cancelActiveQueuedWithResultInternal`, whose per-snapshot loop obtains `withDownloadWorkerExecutionSideEffectLease` and then E lock. Therefore Clear Queue holds process R while awaiting L(d).
- `DownloadDao.kt` blob `1cfce5b3ef4674b0af55af6d2b24d309f1f5b611`, getActiveAndQueuedDownloadsList selects Active and PostProcessing as well as queued statuses. So the same worker d can be targeted.
- `DownloadWorker.kt` blob `d82c3c63fcf11779425721b00b4fcbda0995ba53`, `deleteReplacedHistoryMedia` runs `withOwnedExecutionLease(downloadItem)` then calls `deleteValidatedReplacementPaths`; the latter invokes `HistoryReferenceMutationCoordinator.withLockBlocking` while L(d) is still held. `withOwnedDownloadWorkerSideEffect` also documents lease before short E check with E released before effect.
- `HistoryReferenceMutationCoordinator.kt` blob `dfd8aca9953fe24b1110e5ad2fef2f68766f08d1`, `withLockBlocking` delegates to `RestoreMutationAdmission.withOrdinaryMutationBlocking` = SAME R; `RestoreMutationAdmission.kt` blob `6096839091d5326f9ca1f355c9b259db86c2154f` wraps mutex across block, with coroutine-context-only Held reentry. Independent tasks do not share Held reentry. `DownloadWorkerOwnershipRecovery.kt` blob `92301f672ef275bef664a7a60423957ade792c61` stores per-Download-ID Mutex leases, not token-only leases.
- `HistoryKeywordAssignmentRepository.kt` blob `3e528c696cd9fb2643ed98cc87ecb41c6c030af6` and `DownloadWorker.kt` final cleanup caller were also sampled. Worker History commit does not conclusively eliminate current Download's Active/PostProcessing ownership before the cleanup point, enabling overlap in the supplied source-reachable scenario.

A source-feasible interleaving exists: W obtains L(d) during an eligible worker History replacement cleanup; C begins Clear Queue, obtains R and lists d, blocks trying L(d); W then calls blocking R while still owning L(d). C cannot exit its R body without acquiring L, and W cannot exit its lease body without acquiring R. This is the classic opposing-wait deadlock topology `C: R→wait L(d)`, `W: L(d)→wait R`. It is **confirmed as a production SOURCE risk** and **verified at the shared utility level per archived agent test**, but no Android device runtime capture or complete call-path schedule execution is claimed. A new canonical independent deadlock root and alias/reachability classification is NOT silently assigned in this completion checkpoint; that cross-feature decision would require own complete root/alias adjudication to avoid double counting. This is not evidence that WP0B07 *introduced* these preexisting source edges.

There is no safe permission to extend every Undo selected-first-choice/stronger-revoker transition under existing R while a conflicting L may be owned. Conversely, exempting those revokers without atomic accepted-identity capture leaves WP0B06's fallback-only RESTORE-vs-stronger-owner race unresolved. A no-schema-change terminal codec alone cannot settle either side. This risk is material to the proposed implementation lock order, not a generic test-coverage excuse.

## 4. Rejected overclaims and bounded actionable recommendation

The report does NOT show a completed Design A:
- no production entrypoints create versioned receipts;
- no actual effect+terminal Room transaction wiring across 19+1 direct deletes and stronger linked/History revokers;
- no ACTIVE-only `restoreRemovalSnapshot` once-only CAS;
- no original accepted identity carried through fallback and recovery, no F exact raw cleanup/ACK and cold discovery;
- no final M→R prepublication selection inventory, legacy Room58–65 v1 negative compatibility, finite replay-fenced GC, Android tests or exact published-SHA closure.

Report's local codec/DAO CAS foundation, raw file identities and asserted protected isolation may be useful if an eventual separately authorized continuation independently validates the scratch bytes/hashes. The existing five-file local dirty candidate must NOT be implicitly incorporated into a future source branch or declared equivalent to any new remote source; never reset/clean/discard this protected evidence.

Next recommendation **only** (not task authorization): one separately authorized, cohesive bounded lock-order reconciliation plus Design A integration and real Kotlin/Room/Restore verification, starting with exact source proof of EVERY R/L/E path and callback/thread ordering. Explore a correction that never blocks acquiring R while holding L and never blocks for L while holding R; any change must preserve lease owner and Restore-before-publication/selected-first priority and revalidate after releasing/reacquiring. The report's suggested nonblocking `try` is only a candidate, not an approved replacement API or proof; existing `tryOrdinaryMutationBlocking` still blocks. No new lock/global primitive, migration or Worker architecture change without separate authority; stop if unavoidable. Cover source-first exact R/L diagnostic plus real Clear Queue/History actor regression, status-transition reachability, cancellation/preference/recovery/History filesystem effects, selected F-only failure and stronger parent, and unchanged CLOSED notification/duplicate guarantees. Keep one comprehensive task, not multiple model-only waves, but do not automatically issue its prompt from the current report.

Canonical P2 4 OPEN + 4 AMBIGUOUS = 8 unresolved, repository 53 OPEN/4 AMBIGUOUS/95 CLOSED, NOT_CLEAN, manual L2 IN_PROGRESS as previously counted; this is a completion evidence checkpoint, not a manual `3` run. Independent completion report `CONTRACT_GAP` and `FULL_IMPLEMENTATION_READY=NO` are supported. WP0B07 task is consumed and stopped. Original historical WP0B05 index mismatch remains NOT_VERIFIED.
