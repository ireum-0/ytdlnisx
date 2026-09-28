# BUG-DOWNLOAD-01 destination-fixture child — focused verification blocked by ADB availability

checkpoint_kind: IMPLEMENTATION_AGENT_STOP_RULE
review_parent_sha: f426590fef33451b128e5a52cbb16a38c727a742
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: a27074d6ea8b56be01a9cfb3159a73c018d1b5d0
reported_local_candidate_parent: bd755355ce832db6cb14bcf246fe8c3d01e63ef5
reported_local_candidate_tree: 71ccd919c0400a65cd518d29183354a34b8b1aa6
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Agent completion classification

STOP_RULE_INFRASTRUCTURE_BLOCKER_BEFORE_FOCUSED_TEST

The authorized test-only child was created locally, but no focused Gradle/test
execution occurred because the authorized AVD never became available through
ADB after the attempted cold restart.

This checkpoint does not treat the test child as verified.

## Reported local child

The implementation agent reports:
- commit: a27074d6ea8b56be01a9cfb3159a73c018d1b5d0
- parent: bd755355ce832db6cb14bcf246fe8c3d01e63ef5
- tree: 71ccd919c0400a65cd518d29183354a34b8b1aa6
- changed path only:
  app/src/androidTest/java/com/ireum/ytdl/database/DownloadWorkerCleanupProductionWiringTest.kt
- the fixture now uses a unique writable external-cache destination;
- expected output is removed during cleanup;
- committed-History fault injection and durable assertions remain intact;
- exact-range diff contains only that file;
- git diff --check passes.

The child is unpublished and is not independently source-reviewed here because
it is not available on GitHub.

## Device stop evidence

Authorized AVD:
- name: Medium_Phone_API_36.1
- expected serial: emulator-5554

Reported sequence:
- pre-stop health passed;
- adb emu kill returned KO: unknown command;
- console authentication was required and its token was not read;
- guest sync returned exit code 0;
- only the QEMU process proven to own ports 5554/5555 was stopped;
- the same AVD was relaunched with -no-snapshot-load;
- no wipe or AVD configuration change was performed;
- during the five-minute readiness window, 57 probes reported not_listed;
- boot readiness and PackageManager readiness remained false;
- the agent stopped without running any Gradle verification.

Preserved evidence:
D:/AndroidStudioProjects/ytdlnisx-f11/build/p2-batch/build/remediation-agent/a27074d6ea8b56be01a9cfb3159a73c018d1b5d0/coldboot-targeted-20260928T134346861Z-c243d3d9/device-coldboot.json

The first helper attempt was separately preserved as blocked by PowerShell
execution policy.

## Verification state

NOT EXECUTED on a27074d6:
- Android-test Kotlin compile
- full DownloadWorkerCleanupProductionWiringTest
- focused finalization predicates
- detached exact-SHA diff gate
- restarted full union
- Complete-Wave Check
- publication

Therefore no semantic conclusion about the child is supported yet.

## Next governed action

Preserve a27074d6 exactly.

Do not edit production source.
Do not edit the test again.
Do not create another commit merely to recover device readiness.
Do not reset/rewrite/squash/amend.
Do not publish.

Once the same authorized AVD becomes ADB-visible and satisfies fresh boot plus
PackageManager readiness, resume verification on exact candidate
a27074d6ea8b56be01a9cfb3159a73c018d1b5d0:
1. verify exact HEAD/tree/parent and single-file diff;
2. run required Android-test Kotlin compile;
3. run the full DownloadWorkerCleanupProductionWiringTest;
4. require nonzero execution and zero failures/errors;
5. report exact finalization-hook and durable-state predicates for
   realWorkerKeepsCommittedHistoryAuthoritativeAfterFinalizationFailure;
6. run detached exact-SHA diff gate;
7. only after focused PASS, restart the full exact-final-SHA union from
   partition 1;
8. stop at first valid semantic or infrastructure failure;
9. only after full union + Complete-Wave Check may publication be considered
   under the governing CAS path.

## Canonical state

BUG-DOWNLOAD-01 remains OPEN P2.

No new production finding is created.
No finding is closed.

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.
Overall verdict remains NOT_CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED
