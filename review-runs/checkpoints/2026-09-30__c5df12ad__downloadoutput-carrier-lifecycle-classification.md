# c5df12ad DownloadOutput carrier wait — BUG-DOWNLOAD-01 lifecycle representation classification

checkpoint_kind: COMPLETED_IMPLEMENTATION_RESULT_REVIEWER_CLASSIFICATION
review_parent_sha: 8b449abd2e0dbe9730b80fd3d5dec6e48f5478e1
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: c5df12ad37dc54aacdd6ba13262db23ac6f56beb
reported_local_candidate_parent: 411f759a91a795f59014f8252e2501f560c25eed
reported_local_candidate_tree: 3a4ef37f8be3de628fe622c9f28b6d58d0cabe17
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
production_p2: 13
tooling_p2: 1
independent_execution: NOT_EXECUTED

## Reported verification state

Protected local candidate:
c5df12ad37dc54aacdd6ba13262db23ac6f56beb

Reported focused verification:
- PAUSE03 late-admission method 1/1 PASS;
- FindingAProductionWiringTest 95/95 PASS;
- ResumeActivity notification wiring on API36 1/1 PASS;
- exact compile and detached diff gates PASS.

Restarted exact-candidate union:
- 211 tests executed;
- 190 PASS;
- 21 FAIL;
- zero skips/errors;
- gates 1-11 PASS;
- gate 12 DownloadOutput FAIL;
- gates 13-19 NOT EXECUTED;
- Complete-Wave NOT EXECUTED;
- publication NOT ATTEMPTED.

All 21 DownloadOutput failures reportedly stop at the same shared
WorkInfo.isFinished wait. The first unresolved method,
unavailableFfmpegRuntimeFailsHardSubBeforePublishingOutput, reached the real
claim/native path and the expected FINAL_FAILURE result before the shared
carrier wait timed out.

Failed-time durable row/owner/recovery state was not preserved before teardown,
so those semantic predicates remain NOT_VERIFIED.

## Classification

No new finding ID is created.

Classify the current stop as:

BUG-DOWNLOAD-01 TEST/HARNESS LIFECYCLE-REPRESENTATION / OBSERVABILITY GAP

with production correctness still NOT_VERIFIED for the failed methods until
their durable post-attempt state is asserted.

This is not yet evidence of a production liveness defect and is not permission
to weaken a semantic assertion.

The dominant evidence is that 21 otherwise distinct DownloadOutput scenarios
all fail at the same helper-level WorkManager completion assumption after the
first case has already reached its intended production failure path.

The shared helper on the authoritative remote source waits only for
WorkInfo.state.isFinished. The return value is not consumed by the visible
DownloadOutput call sites; the actual semantic assertions are performed after
that helper returns.

## Canonical basis

BUG-DOWNLOAD-01 already has a canonical lifecycle-versus-semantic precedent.

The checkpoint
review-runs/checkpoints/2026-09-28__29034eae__download01-outcome-b-last-focused-failure.md
requires tests to distinguish durable promised outcome from a representation-
specific WorkInfo RUNNING state. It explicitly permits a test-only correction
when exact production proof shows durable History/row/recovery authority is
correct while worker lifecycle representation remains RUNNING.

The canonical BUG-DOWNLOAD-01 invariant remains:
- authoritative uncertainty may not become false ownership loss;
- exact execution/recovery responsibility must remain discoverable when needed;
- a stale execution may not retain destructive authority;
- durable semantic outcome, not incidental worker representation, is the
  correctness object.

The current evidence has not yet proven those predicates for DownloadOutput
because teardown destroyed the failed-time state.

## Source-semantic observation boundary

Authoritative DownloadWorker source shows every per-download attempt executes
cleanupAttempt() from the attempt actor's finally block.

cleanupAttempt() performs the final per-attempt decisions relevant here:
- pending user-stop/history-finalization convergence;
- durable-stop recovery retention;
- terminal row reread;
- exact DownloadWorkerExecutionOwners release/retention;
- process-owner release when no native registry remains;
- workerDownloadIds / workerCleanupDownloadIds / workerExecutionIds cleanup;
- newer-execution isolation.

Therefore the end of cleanupAttempt() is a production-faithful observation
boundary for "this exact Download attempt finished its cleanup decision".
It is narrower and semantically stronger than waiting for the containing
WorkManager carrier to become globally FINISHED.

## Authorized narrow correction

Authorize one forward BUG-DOWNLOAD-01 verification/fixture child on
c5df12ad37dc54aacdd6ba13262db23ac6f56beb.

Allowed files:
- app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt
- app/src/androidTest/java/com/ireum/ytdl/database/DownloadOutputProductionWiringTest.kt

DownloadWorker.kt may change ONLY to add one inert internal test observation
hook at the very end of cleanupAttempt(), after all owner/recovery/map cleanup
decisions for that exact attempt have executed.

Suggested shape:
afterAttemptCleanupForTesting(downloadId, executionId)

Requirements:
- null/no-op by default;
- no production behavioral change while unset;
- no Room, recovery, WorkManager, scheduler, native, notification, or ownership
  mutation from the hook;
- no changed lock/lease ordering;
- invoke only after cleanupAttempt has completed its existing semantic work;
- reset by test setup/teardown.

DownloadOutputProductionWiringTest.kt may:
- reset/install the hook;
- bind it to the exact download ID/execution observed by each real worker;
- change the shared enqueue helper so it waits for either:
  a) normal WorkInfo terminal state, or
  b) the matching exact attempt-cleanup observation;
- preserve the real WorkManager DownloadWorker carrier;
- preserve every method's existing post-wait semantic assertions.

Do not replace the real worker with a direct helper claim.

Do not remove or weaken any output/history/destructive-boundary assertion.

Do not treat the cleanup hook itself as proof of correctness. It is only a
deterministic point after which the test can read and assert durable state.

## Required durable assertions

Before accepting a fixture-only correction, the focused run must prove that the
first failing method and representative success/failure methods observe state
consistent with their existing semantic contract after exact attempt cleanup.

For unavailableFfmpegRuntimeFailsHardSubBeforePublishingOutput, record and
assert at minimum:
- Download row existence/status/executionId;
- History/output publication state;
- exact DownloadWorkerExecutionOwners state;
- DownloadWorkerProcessOwners state;
- DownloadExecutionRecovery disposition/phase/carrier presence;
- DownloadProducerRecovery identity/phase/blocking state where applicable;
- native-process registry/debt where applicable;
- WorkInfo state as diagnostic metadata, not the sole pass condition.

If the row remains Active/PostProcessing without a valid exact owner/recovery
carrier, if stale ownership remains after terminal failure, if required
recovery responsibility disappears, or if output/history effects violate the
method contract, classify that as an existing BUG-DOWNLOAD-01 production
residual and STOP before papering it over with fixture changes.

If durable semantics are correct and only WorkInfo remains RUNNING, the
fixture-only lifecycle correction is supported by the canonical precedent.

## Verification

Before commit:
- verify exact parent c5df12ad;
- inspect exact diff;
- git diff --check;
- confirm only the two authorized files changed;
- confirm DownloadWorker change is test-observation-only.

Run:
1. exact compile gate;
2. unavailableFfmpegRuntimeFailsHardSubBeforePublishingOutput;
3. complete DownloadOutputProductionWiringTest;
4. detached exact-SHA diff gate.

Require nonzero execution and zero unexplained failures/errors.

If focused DownloadOutput passes:
- restart the complete exact-final-SHA 19-gate union from partition 1;
- preserve first-failure evidence;
- resume the existing long-run bounded-diagnostic remediation policy for later
  failures.

If focused DownloadOutput still fails:
- preserve the first semantic failure;
- capture the durable state listed above before teardown;
- map it to an existing root if supported;
- do not make an unbounded second speculative edit.

## Publication gate remains unchanged

No publication is authorized until:
- complete exact-final-SHA union PASS;
- Complete-Wave PASS;
- required RESUME-01 API24/25 real-device verification PASS;
- fresh implementation/review ref checks;
- protocol-authorized expected-old strictly-forward CAS publication;
- remote equality verification.

API24/25 unavailability remains a separate publication blocker.

## Canonical state

BUG-DOWNLOAD-01 remains OPEN P2.
No new root is created.
Canonical counts remain unchanged.
No root is FIXED-CLOSED.
Repository-wide CLEAN is unsupported.
