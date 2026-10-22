# WP0-B09 — independent completion review of failed alternating-lease diagnostic

checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
expected_review_parent: b9ba90498be0a6f7daad5da147ac5af35405ffc6
reviewed_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
private_full_report_commit: b70084aa16c74bf077f07ccb7f33f3195bf48ef4
private_full_report_path: ytdlnisx/reports/2026-10-22__WP0B09__wp0b09-20261022-ec2f6fa7__FULL_REPORT.md
private_full_report_blob: a2e9cb7409c69a0fbf7372eaa16732ee7665e6d9
task_instance_id: WP0B09_LOCK_FAILURE_ATTRIBUTION_A_COMPLETION_20261022_141B519_01
prompt_origin_commit: 88e897448b843eb196e166fcf46f73ebffe87237
prompt_blob: 693aaf78ec3051a1cf5ea17dcb79fd71b6a0dc48
private_launch_handoff_commit: 9484cbc75a1cc79e2306581dc349d64bc7bd4923
intent_commit: 29e66291bd97643cb13082c43ca379ec2af913ec
intent_blob: 4e8e603b811a008a39f8404a8b03a8bcff557cfc
claim_commit: 26210a13cd1b4177582d3a610ebe8eb9d9ef1ce3
claim_blob: 81229e7cd6676708e235450b8df27bb0ee0ee462
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
independent_verdict: STOP_VALID_FAILED_LOCK_DIAGNOSTIC_UNSHIPPED_LIVELOCK
source_production_published: NO
implementation_remote_tip_advanced: NO
local_candidate_reported_files: 10
local_candidate_fingerprint_agent_reported: b21f3eaef2f03a106fec0cda42d8444e223fa6a57c53a219a7bc0d46b015ea86
local_source_bytes_reviewer_observed_independently: NO
gradle_native_exit_agent_reported: 1
kotlin_production_and_test_compilation_agent_reported: COMPLETED
new_diagnostic_methods_agent_executed: 2
new_diagnostic_pass_agent_reported: 1
new_diagnostic_fail_agent_reported: 1
diagnostic_methods_reexecuted_independently: 0
device_android_observer_tests_executed: 0
full_design_A_runtime_writers_activated: NO
chained_exact_published_sha_closure: NOT_REACHED
full_implementation_ready: NO
canonical_root_status_changes: ZERO
new_counted_roots: ZERO
P2_OPEN: 4
P2_AMBIGUOUS: 4
P2_UNRESOLVED: 8
repository_OPEN: 53
repository_AMBIGUOUS: 4
repository_CLOSED: 95
CLEAN: NO
manual_L2_status: IN_PROGRESS_UNCHANGED
WP0B09_consumed: YES_NO_RELAUNCH
historic_wp0b05_index_cause: NOT_VERIFIED
handoff_updated: NO
next_implementation_prompt_persisted: NO

## 1. Independent GitHub lifecycle and archive integrity

At private `b70084aa16c74bf077f07ccb7f33f3195bf48ef4`, the complete WP0B09 Full Report is the blob `a2e9cb7409c69a0fbf7372eaa16732ee7665e6d9` and exact declared path. Compare from launch HEAD `9484cbc75a1cc79e2306581dc349d64bc7bd4923` is ahead 3/behind 0 and adds exactly own intent, matching claim and report, with NO prior path modified or deleted. Three verified normal sole-parent commits: `9484cbc7 -> 29e66291 -> 26210a13 -> b70084aa`; each adds only one expected path. The nontruncated private tree has 487 entries and exact report path/blob. Intent and claim files were independently fetched and match immutable task, prompt origin/path/blob, source/protocol, claimant `ec2f6fa7194211c81e8b9a4ff7a8f05e`, and referenced original intent receipt. No competing run is established by this bounded history. The user's 471-old-file preservation claim is CONSISTENT with no original paths touched; reviewer did not separately rehash 471 individual blobs or compute the raw full-report SHA256.

The private `NEXT_CHAT.md` still carries `WP0B09/PERSISTED_NOT_STARTED`, but exact matching own intent+claim+final STOP report supersede that stale status. WP0B09 has executed and is permanently consumed. Do not relaunch it or return bootstrap for it. No reviewer permission to silently write a new next-agent prompt from this completion report alone.

## 2. Exactly observed new diagnostic evidence and its limits

The immutable report lists one offline `gradlew.bat --offline ... :app:compileDebugKotlin :app:testDebugUnitTest --tests <two new exact methods>` invocation, with actual source/test compilation completed, native Gradle exit 1, BUILD FAILED. Method `WP0B09LockFailureDiagnosticTest.wp0b09ExceptionChainAndFiniteSuccessorAttribution` PASS (XML 0.385s). Method `WP0B09LockFailureDiagnosticTest.wp0b09WorkerFirstActorAndSignalAttribution` FAIL at 5000ms with joinAll timeout (XML 5.051s). One pass and one fail; none of the prior failed WP0B08 methods or unrelated tests were rerun. This is **agent-executed**, reviewer-inspected archived ledger, not reviewer independently executed tests. The 75 MB original trace and XML are reported retained locally, not independently retrieved, reread in entirety, or rehashed by the reviewer.

Report §§6 and 11 provide exact bounded trace cuts. W first owns L(917) and awaits a driver-controlled release signal. C owns R, attempts L, gets Busy, releases R, awaits L outside R. Driver successfully acquires/releases R and emits signal; W releases L. W then gets R, fails L try against queued C, exits R to await L; C acquires and immediately releases L, reenters R and fails L try against queued W. W then acquires/releases L, returns to R and fails L try against C. The alternating handoff repeats; reported 43,154 C observations and 43,153 acquisitions for each actor, zero semantic mutations before timeout. Cause inference is **livelock / no progress contract in unshipped try/release/reobserve algorithm**, not stranded test signal or a proven Android execution hang. Because the original WP0B08 failed test lacked that trace, do NOT equate its exact old schedule with this new evidence retroactively. No fairness fix is shown.

Exception test reports original IllegalStateException at identity 1197251633, caught a different IllegalStateException reference at 644082020 with identical message, and the original instance in its cause chain. Test observed R Held and acquisition scope exit, L(925) release, and finite successor R then L acquisition and semantic test mutation. Exact evidence supports **utility-level lock release and exception recovery identity distinction** for that test path. It does not establish all throw/cancellation cases, real Room transaction, native/History filesystem, Worker process exit or app-level recovery.

Archived snapshot fingerprint `b21f3eaef2f03a106fec0cda42d8444e223fa6a57c53a219a7bc0d46b015ea86` is local-only ten-file raw-source evidence as reported, not independently available as source commit. The same nine WP0B08 drafts were reportedly hash-verified read-only before reuse; two files acquired diagnostic-only hooks and one new diagnostic test was added. Neither hooks nor typed DAO/codec constitute a production fix. Failure correctly triggered STOP without attempted source correction, repeated methods, additional test runs or source publish.

## 3. Independent actual pinned production source semantics

Independently re-read exact `141b5199592369ab953f52c55ebdb72ba300f1a4` from GitHub, not only report:
- `database/RestoreMutationAdmission.kt` blob `6096839091d5326f9ca1f355c9b259db86c2154f`, `withHeld` uses one process R mutex and coroutine-context Held reentrancy; `tryOrdinaryMutationBlocking` is NOT a true nonblocking mutex tryLock.
- `database/repository/DownloadRepository.kt` blob `8125ef4b1a09872c83be876e4f98f14981553246`, `cancelActiveQueuedWithResult` wraps entire `cancelActiveQueuedWithResultInternal` in ordinary R and then obtains per-download L(d) and E.
- `work/DownloadWorker.kt` blob `d82c3c63fcf11779425721b00b4fcbda0995ba53`, History replacement cleanup holds L(d) via owned execution lease while `deleteValidatedReplacementPaths` calls blocking History reference admission R.
- `util/storage/HistoryReferenceMutationCoordinator.kt` blob `dfd8aca9953fe24b1110e5ad2fef2f68766f08d1` maps that blocking call to the same R. `work/DownloadWorkerOwnershipRecovery.kt` blob `92301f672ef275bef664a7a60423957ade792c61` uses per-Download-ID Mutex leases.
- `database/dao/PendingUndoCarrierDao.kt` blob `8bad32582b00b12cd81e5b08526fd1f8e5251991` retains presence-based `get/getAll` and no typed terminal effect CAS at source HEAD. Original producer/reader/20 deletion and stronger revoker/Restore priority obligations are unchanged.

The original production-source lock inversion `C: R→L(d)` versus `W: L(d)→R` is STILL present. The new alternating handoff comes only from the uncommitted post-WP0B08 diagnostic candidate and does not prove the shipped code acquired that pattern. No separate new canonical root is counted from unshipped draft. The source evidence does NOT support marking Design A impossible or complete; it remains an unintegrated proposed implementation with legacy/accepted-first-choice/fallback exact raw cleanup/terminal co-commit/cold startup/Restore pointer and actual Room58–65 obligations open.

## 4. Conservative next boundary, no new task issued

Independent verdict: `STOP_VALID_FAILED_LOCK_DIAGNOSTIC_UNSHIPPED_LIVELOCK`; `FULL_IMPLEMENTATION_READY=NO`, `SOURCE_LOCK_FIX_NOT_PROVEN`, `DESIGN_A_SOURCE_INTEGRATION_NOT_STARTED`. NO canonical root status change; existing P2 4 OPEN+4 AMBIGUOUS=8, repo NOT_CLEAN, separate manual L2 run IN_PROGRESS. Prior historical WP0B05 index mismatch cause remains NOT_VERIFIED.

If the user separately authorizes a new implementation continuation, next **single integrated source correction** should incorporate the observed no-progress counterexample, not re-diagnose the same loop. Require an actual ownership-compatible and finite-progress admission approach with no R-held blocking L, no L-held blocking R, no instant release/contended alternating handoff; prove cancellation/History final semantic operation under exact token/status+execution generation and Restore M→R admission and replay. Test both original opposing-order and worker-first handoff adversarial schedules, fair successor completion and exception chain/release with deterministic source-bound regression and real production observers when available. Only if those gates pass may same-task Design A integration and the exact posted-SHA tests continue, within newly authorized scope. Do not use random sleeps, only raising timeouts, an unrelated global lock, reduced History guard, false terminal COMMIT, or source publication with two-sided consumer gaps. New task must have distinct one-shot instance/prompt/handoff; WP0B09 is consumed.

This evidence-only reviewer checkpoint is the only authorized current write. No local protected scratch edits, production source edits, private handoff changes, or replacement prompt requested. Pending explicit authorization, remain at this boundary.
