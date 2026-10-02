# BUG-UPDATER-04 first valid focused semantic failure — mixed failure triage required

checkpoint_kind: BUG_UPDATER04_FIRST_VALID_FOCUSED_SEMANTIC_FAILURE
review_parent_sha: 33c1177d06ba3ec7c0a9ed1b312f1946f3145fa6
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Operator-run focused result

The user manually started the same approved AVD after host reboot and ran the focused
YtdlpRuntimeAuthorityProductionWiringTest class from the protected BUG-UPDATER-04 dirty worktree.

Reported result:
- 7 tests started;
- 7 failures;
- 0 skipped;
- task :app:connectedDebugAndroidTest failed;
- semantic execution was reached.

Reported failures include:
1. ownedUpdaterFailureAndCancellationReleaseForLaterConsumersAndRetry
   - AssertionError: expected 1 but was 2.
2. startupIdleObservationCannotAuthorizeMutationPastLateRealDownload
   - AssertionError: finite authority ordering wait.
3. promotionExcludesRealDownloadLaunchUntilPromotedRuntimeIsUsable
   - YoutubeDLException: arm64 libxml2 artifact loaded in x86_64 AVD environment.
4. overlappingUpdaterRequestsKeepCoalescingAndDesiredGenerationOrdering
   - same reported ABI mismatch signature.
5. independentRealDownloadNativeGenerationsRemainIsolatedOnException
   - TimeoutCancellationException after 30000 ms.
6. liveRealDownloadExcludesManualMutationUntilExactQuiescence
   - TimeoutCancellationException after 30000 ms.
7. realCustomSelfUpdateOwnsMutationWithoutRecursiveConsumerAdmission
   - same reported ABI mismatch signature.

The user previously reported:
- HEAD = 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
- dirty state = the expected 8 modified + 2 untracked BUG-UPDATER-04 files;
- git diff --check passed aside from line-ending warnings.

## Classification

CLASSIFICATION=FIRST_VALID_FOCUSED_SEMANTIC_FAILURE_MIXED_CAUSES_NOT_YET_ROOT_MAPPED
SEMANTIC_RESULT=FAIL
EXECUTED_TESTS=7
FAILED_TESTS=7
ZERO_TEST_INFRASTRUCTURE_BLOCK=CLOSED_FOR_THIS_RUN
RUNTIME_CORRECTNESS=NOT_VERIFIED

This result must not be rerun unchanged.

The observed failures are not yet safe to collapse into one root:
- at least three failures show an explicit ABI/runtime-package mismatch signature;
- assertion/count and finite-ordering failures may be production, harness, or cascade;
- timeout failures may be same-root liveness residuals, harness effects, or downstream cascade.

No new production root is created from the pasted result alone.
No BUG-UPDATER-04 correction is authorized until exact local dirty source/test semantics and preserved
test artifacts are mapped to the failures.

## Required next action

Perform a read-only semantic failure triage over:
- the exact dirty 10-file BUG-UPDATER-04 candidate;
- the focused test source;
- connected-test XML/logcat/UTP/test-result artifacts from this exact run;
- the app-private yt-dlp/aria2c package ABI selection path only as needed to explain the explicit mismatch;
- the runtime-authority producer/carrier/consumer/release paths implicated by the assertion and timeout failures.

For each of the seven tests, classify:
- SAME_ROOT_PRODUCTION_RESIDUAL;
- HARNESS_OR_PRECONDITION_DEFECT;
- ABI_OR_RUNTIME_ENVIRONMENT_DEFECT;
- CASCADE_FROM_EARLIER_FAILURE;
- NOT_VERIFIED.

Do not edit source/test, clear app data, reinstall runtime packages, rerun tests, restart ADB/emulator,
or otherwise change the environment during triage.

If and only if one or more failures are conclusively mapped to the already-authorized BUG-UPDATER-04
shared-runtime-authority root, derive the narrow correction allowed by the governing root prompt and
Protocol 4.2.1.

If ABI/runtime state is the blocker, identify the exact state producer and the smallest supported recovery
that materially changes the precondition without erasing correctness evidence.

INDEPENDENT_EXECUTION: NOT EXECUTED
