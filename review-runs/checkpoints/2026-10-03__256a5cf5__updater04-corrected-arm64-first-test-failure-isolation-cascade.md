# BUG-UPDATER-04 corrected ARM64 focused run — first-test failure plus isolation cascade

checkpoint_kind: BUG_UPDATER04_CORRECTED_ARM64_FOCUSED_FAILURE
review_parent_sha: 3ca3cf57bf21e6ae0ce88b5c7e213764b873ae67
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Operator corrected ARM64 focused result

The operator ran the corrected focused class exactly once on an ARM64 Samsung SM-A546E target.

Focused class:
com.ireum.ytdl.util.YtdlpRuntimeAuthorityProductionWiringTest

Reported result:
- 7 tests started;
- 0 pass;
- 7 fail;
- semantic execution reached on the ARM64 target;
- no reported AArch64-vs-x86_64 linker mismatch occurred in this run.

Reported failures:

1. ownedUpdaterFailureAndCancellationReleaseForLaterConsumersAndRetry
   - java.lang.AssertionError
   - no assertion message shown in console excerpt.

2-7. Every remaining test failed in setUp at YtdlpRuntimeAuthorityProductionWiringTest.kt:156:
   - AssertionError: Previous real-worker isolation did not complete; shared state is retained

Affected blocked tests:
- startupIdleObservationCannotAuthorizeMutationPastLateRealDownload
- promotionExcludesRealDownloadLaunchUntilPromotedRuntimeIsUsable
- overlappingUpdaterRequestsKeepCoalescingAndDesiredGenerationOrdering
- independentRealDownloadNativeGenerationsRemainIsolatedOnException
- liveRealDownloadExcludesManualMutationUntilExactQuiescence
- realCustomSelfUpdateOwnsMutationWithoutRecursiveConsumerAdmission

## Classification

CLASSIFICATION=CORRECTED_ARM64_FIRST_TEST_FAILURE_WITH_HARNESS_ISOLATION_CASCADE
SEMANTIC_RESULT=FAIL
EXECUTED_TESTS=7
INDEPENDENT_TEST_FAILURES_PROVEN=1_OR_UNKNOWN
CASCADE_BLOCKED_TESTS=6
ABI_MISMATCH_REPRODUCED=NO_IN_REPORTED_CONSOLE_OUTPUT
RUNTIME_CORRECTNESS=NOT_VERIFIED

The six setUp failures are not independent BUG-UPDATER-04 production failures. They are fail-closed harness
isolation blocks caused by retained shared-state/isolation status from the preceding test.

The first test's bare AssertionError cannot be classified from the console excerpt alone.

Do not rerun this class unchanged.

## Required next action

Perform read-only triage of the exact corrected local androidTest source and preserved ARM64 run artifacts.

For the first test:
- identify the exact assertion that failed;
- identify whether a real DownloadWorker was launched;
- identify whether afterAttemptCleanupForTesting fired for the exact downloadId/executionId;
- identify which isolation state/flag remained incomplete;
- distinguish production semantic failure from test-harness cleanup/isolation failure.

For the six blocked tests:
- identify the exact shared isolation guard at line 156;
- trace what state from the first test caused it to fail;
- prove whether the retained state represents a still-live worker/recovery/native owner or merely a stale
  harness flag/observation not cleared after an otherwise completed attempt.

Inspect:
- corrected YtdlpRuntimeAuthorityProductionWiringTest.kt;
- exact JUnit XML;
- per-test logcat;
- UTP/test-result metadata;
- WorkManager/worker cleanup observations;
- DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting observations;
- relevant row/owner/native/recovery state.

Do not edit source/test/config.
Do not rerun tests.
Do not clear app data.
Do not reset hooks/environment.
Do not commit or publish.

If the first failure is conclusively a harness/isolation defect, derive the narrow androidTest-only correction.
If it is conclusively a BUG-UPDATER-04 production residual, identify the exact producer/carrier/consumer proof
before requesting any production edit.

BUG-UPDATER-04 remains OPEN P2.
Canonical counts remain 0/0/4.

INDEPENDENT_EXECUTION: NOT EXECUTED
