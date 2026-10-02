# BUG-UPDATER-04 device availability recovery authorization after host reboot

checkpoint_kind: BUG_UPDATER04_DEVICE_AVAILABILITY_RECOVERY
review_parent_sha: 79a2cf5ffc082e881549bf35f511e9be9c39780c
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Reported stop

The post-cleanup focused retry stopped before semantic execution:
- exit code 1;
- zero tests executed;
- launcher error: No connected devices!;
- protected ten-file BUG-UPDATER-04 draft remained hash-identical;
- worktree HEAD/tree/parent remained unchanged;
- git diff --check passed;
- no source correction, commit, publication, or process termination occurred;
- D: free space remained materially positive.

Durable local evidence was reported under:
D:/AndroidStudioProjects/ytdlnisx-f11/build/sol-remediation-20260930/build/remediation-agent/updater04-pc08-postcleanup/

## User-provided infrastructure cause

The user reported that the laptop had shut down/restarted and the Android emulator was therefore no longer
running when the retry was attempted.

This explains the observed "No connected devices!" pre-semantic failure and constitutes a concrete,
material device-availability precondition change distinct from the prior disk-capacity stop.

The reviewer does not independently infer any additional device defect.

## Classification

CLASSIFICATION=ZERO_TEST_NO_CONNECTED_DEVICE_AFTER_HOST_REBOOT
SEMANTIC_RESULT=NOT_EXECUTED
NEW_ROOT=NO
COUNT_CHANGE=0
BUG_UPDATER04_STATUS=OPEN_P2_RUNTIME_CORRECTNESS_NOT_VERIFIED

The failed focused attempt must remain preserved and must not be relabeled as a semantic failure.

## Authorized recovery

Authorize one bounded device-availability restoration:

1. Start the same previously approved Android emulator/AVD recorded in the existing verification handoff.
2. Do not create, clone, substitute, wipe, reset, snapshot-restore, reconfigure, upgrade, or otherwise alter
   the AVD/device definition.
3. Wait for normal boot completion and basic device readiness.
4. Perform only a read-only/ordinary health preflight sufficient to establish that the device is visible to
   the normal Android test tooling and ready for instrumentation.
5. Do not terminate or restart ADB, emulator, Gradle, Android Studio, Java, or other processes merely to
   green-seek. If the existing same-AVD launch cannot become normally available without such intervention,
   stop and report.
6. Re-verify the protected ten-file BUG-UPDATER-04 draft and implementation HEAD before retrying the test.
7. Retry exactly once the same focused BUG-UPDATER-04 gate that just failed with "No connected devices!".

Starting the same approved AVD after the host reboot is the material recovery action. The test retry is
therefore not an unchanged retry of the failed no-device precondition.

## Retry outcomes

If the focused retry executes nonzero tests and PASSes:
- resume the same BUG-UPDATER-04 wave under the governing root prompt;
- continue only through its existing verification/publication boundaries.

If the focused retry executes nonzero tests and FAILs:
- preserve the semantic failure;
- do not rerun unchanged;
- follow only explicitly authorized same-root continuation rules.

If the focused retry again executes zero tests or fails before semantic execution:
- preserve the infrastructure evidence;
- do not retry unchanged;
- stop for reviewer classification.

## Prohibited actions

No source/test/config edit is authorized before the focused retry.
No cleanup, deletion, Git mutation, worktree removal, stash mutation, gc/prune, AVD replacement, AVD wipe,
or process termination is authorized by this checkpoint.

This checkpoint does not close BUG-UPDATER-04 or change canonical counts.

INDEPENDENT_EXECUTION: NOT EXECUTED
