# WP0B08 — independent completion review of failed source-bound lock regression

checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
expected_review_parent: f09218d628e523455fb10e8253ac8e1f404a9840
reviewed_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
private_full_report_commit: 2de11cd81e4688d2f2f052b1f01c14fd53ec1ae8
private_report_path: ytdlnisx/reports/2026-10-22__WP0B08__wp0b08-20261022-dad6cda6__FULL_REPORT.md
private_report_blob: 801a5a6420ee1faa14d1c9ba017f08c381f73928
private_report_sha256_agent_asserted: 47287f3365d2eac096e163f69b13e0c67b2c2461ccfe6b18da7d698c47219fad
private_report_sha256_independently_recomputed: NO
task_instance_id: WP0B08_LOCK_ORDER_A_COMPLETION_20261022_141B519_01
prompt_origin: f13458ce00b54e510e3b7e726f0fc0905abd364e
prompt_blob: b0ca03a6b219ace458a6e6e54074fb5dec94091d
intent_commit: 95697fbf6a17bc7c1f4d6d30c24f7e22cbfe3348
intent_blob: 8a5ce1f22e6e3261e9ac94f11429bd1261343a1a
claim_commit: 9da49d2db26187f0d0f1a12f1b31c67e216c28f9
claim_blob: 302e1528108f0bc9e65cd2b34b018539c4e80fe9
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
result: STOP_CONFIRMED_AT_FAILED_MANDATORY_PRECOMMIT_GATE
independent_verdict: FAILED_LOCK_REGRESSION_NO_SOURCE_PUBLICATION_NO_CORRECTION_CLOSURE
native_gradle_exit_agent_reported: 1
kotlin_source_compile_agent_reported: COMPLETED
junit_tests_executed_agent_reported: 38
junit_tests_pass_agent_reported: 36
junit_tests_fail_agent_reported: 2
junit_tests_executed_independently: 0
local_candidate_files_reported: 9
local_candidate_fingerprint_agent_reported: 0cb40872fd86b764e3954ad0b0a78ea595fc3739d69d43b5bc59ad757a5cb285
local_candidate_exact_bytes_independently_observed: NO
actual_device_or_android_methods: 0
remote_implementation_commits: 0
remote_implementation_tip_changed: NO
exact_remote_pushed_sha_closure: NOT_REACHED
source_undo_terminal_writers_activated: NO
canonical_root_changes: ZERO
new_counted_roots: ZERO
P2_OPEN: 4
P2_AMBIGUOUS: 4
P2_UNRESOLVED: 8
repository_OPEN: 53
repository_AMBIGUOUS: 4
repository_CLOSED: 95
CLEAN: NO
manual_L2_status: IN_PROGRESS_UNCHANGED
wp0b08_instance: CONSUMED_NO_RELAUNCH
next_prompt_authorized: NO
handoff_updated: NO
historic_wp0b05_index_cause: NOT_VERIFIED

## 1. Independent archive and lifecycle verification

Read exact GitHub Full Report at private HEAD `2de11cd81e4688d2f2f052b1f01c14fd53ec1ae8`; its Git blob is `801a5a6420ee1faa14d1c9ba017f08c381f73928`. Compare from prelaunch private `52a89d05f87e412be886b3277e8d91a424a6486d` to report HEAD is ahead 3/behind 0, with exactly three additions and no modification/removal: one matching `run-intents`, one `run-claims`, one `reports` file. GitHub commit metadata separately verifies sole normal parents `52a89d05 → 95697fbf → 9da49d2d → 2de11cd8`, each sole newly added path. The nontruncated private tree contains the report exact path/blob. Intent and claim text match the persisted WP0B08 prompt path/blob and origin commit, source/protocol and claimant `dad6cda609cf01bbe518fbf15f21180a`; claim reuses original intent commit/blob, no competitor within this bounded history.

Report's exact old 467 blob preservation is corroborated as *no prior path modified in GitHub compare*; reviewer did not independently rehash all 467. Its separately supplied SHA256 is an agent assertion, not recomputed by reviewer. WP0B08 is consumed by own successful intent/claim and STOP Full Report. Stale `NEXT_CHAT.md` still says WP0B08 `PERSISTED_NOT_STARTED`, but report overrides it; never return its bootstrap or relaunch it. Reviewer lifecycle evidence-only checkpoint does not rewrite handoff or create any new task.

## 2. Test failure assessment, limited to observed evidence

The Full Report says single isolated offline Gradle execution completed `:app:compileDebugKotlin` and `:app:compileDebugUnitTestKotlin` and then failed `:app:testDebugUnitTest` with native exit 1 and `BUILD FAILED`. Exactly 38 source-bound JUnit method rows in report §8: 32 typed receipt codec/policy PASS; six lock-order methods with 4 PASS and 2 FAIL. Current-wave passes are 36, failures 2, none skipped. These are agent-attributed local Gradle/JUnit/XML records; reviewer did not access or reexecute the old Windows candidate/JUnit artifacts.

Fail 1: `com.ireum.ytdl.database.repository.UndoTerminalLockOrderingTest.originalWorkerFirstInterleavingReleasesAdmissionBeforeLeaseWait` timed out at `withTimeout(5000)` / line 42 in uncommitted local test file, with XML elapsed 5.103 seconds. It is a failed progress requirement; neither exact suspended actor/lock nor fixture-source causality is proved. Fail 2: `exceptionDoesNotRetainEitherProductionLock` AssertionError at candidate test line 168, method line 159, with elapsed 0.015 seconds: a `caught === expected` reference-identity assertion failed. Neither resulting thrown Throwable class/cause nor retained R/L state after failed assertion is recorded. Neither failure is a safe PASS, proven Android deadlock, proven coroutine exception-wrapper artifact, or license to inflate watchdog/relax identity checks without evidence. An assertion before the successor probe means lock release property remains unproved on this path.

Four passed lock tests support only their narrow utility assertions: cancel-first progress, cancellable partial batch release, continuation retaining L after R exit, and contended batch refreshing observation. No Android Worker/Room/prefs/file/native lifecycle was executed. The 32 codec tests are representation-only, not proof real first-choice writers, terminalizers or recovery.

The failed mandatory precommit test gate correctly blocked implementation source publication. Same-SHA chained verification never began. No unchanged test retry, further test collection, A/B/C substitution, device or Android execution, test expectation weakening, force, reset, cleanup or source push is reported. Local nine-file draft and initial XML/evidence remain attributed as preserved, not remotely present.

## 3. Independent source-and-candidate semantic strength

At original production SHA `141b5199592369ab953f52c55ebdb72ba300f1a4`, previous independently inspected source establishes the original `Clear Queue R→L(d)` versus `Worker History L(d)→blocking R` cycle (WP0B07 independent review). That pre-existing defect is still in remote code: remote implementation tip is unchanged. The WP0B08 archived report §12 contains a *readable diff representation* of the nine-file locally edited candidate, not a commit or verified Git tree. It uses the existing R admission and per-ID L mutexes: adds `tryAcquire/release`, tries L without blocking while holding R, waits outside R after Busy, discards/re-observes batch selection, and converts specific Worker History blocking adapters to suspending ones with the R context. The full candidate after effects, total reachable lock graph, local test fixture text and external Actor final states are not independently available as a checked-out implementation revision. It is reasonable to describe the intended narrower fix, not to certify it cycle-free or safe.

The failure could stem from an actual remaining R/L cycle, assertion/fixture schedule, or an unaccounted lock lifetime. No one cause was selected without the exact suspended actor/acquisition trace. Reference identity failure similarly requires actual captured exception/cause chain; it does not automatically prove retained lock or faulty app behavior. Failure source snapshot, first native results and nine-file fingerprints must remain immutable as historical evidence. The current remote source is STILL uncorrected. WP0B08's local Design A typed codec/DAO foundation was reused under reported byte-hash checks, but live producer/consumer/terminal-effect pathways were explicitly NOT integrated. All 20 carrier direct deleters and stronger revokers, terminal-only startup, raw fallback ACK and M→R selected/unselected admission remain unresolved. No v65 schema/migration changed.

A candidate-only failing test is not enough to create a distinct **new** canonical shipped root: previous baseline already contained a source-proven lock-order risk, and this draft was never shipped. Do not double count alias/candidate. Canonical P2 remains 4 OPEN/4 AMBIGUOUS = 8 unresolved, repository 53 OPEN/4 AMBIGUOUS/95 CLOSED, NOT_CLEAN, manual L2 independently IN_PROGRESS.

## 4. Narrow next evidence recommendation without authorization

One bounded separate diagnostic *if the user authorizes it*: preserve the original failing XML and candidate unchanged, first instrument/read-only reconstruct exact R/L actor state and coroutine scheduling at the specific worker-first cut, and capture actual thrown Throwable class, identity and cause for the exception cut. Record exact source snapshot identity and test method definitions; do not simply rerun unchanged failed tests or raise timeout. Only after a newly justified source correction may a different authorized new task test corrected call sites with actual production ownership / History external effect, and then continue the same long-source Design A integration. New WP0B09 prompt or active lifecycle handoff is NOT authorized by receipt of WP0B08 report alone; existing WP0B08 prompt exhausted. No implementation branch changes, private handoff edits or protocol/governance edits are authorized within this completion assessment.
