# 9aa6d972 repeated zero-test attach failure — same-AVD cold restart authorized

checkpoint_kind: INFRASTRUCTURE_RECOVERY_AUTHORIZATION
review_parent_sha: ad798c1402c9433c8429d895a0aa1bf43e193e62
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 9aa6d972eafd885046cc6b2643f7f3c053c2527e
reported_local_candidate_parent: c5df12ad37dc54aacdd6ba13262db23ac6f56beb
reported_local_candidate_tree: bad5a70d1887d18d90c4c484a39292cccad03123
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
count_change: 0

## Classification

The post-repair full DownloadOutput class run again executed zero tests.

Observed verifier state:
- connected gate FAILED_EXIT_CODE;
- instrumentationStarted=true;
- executedTests=0;
- failureCount=0;
- errorCount=0;
- no semantic test method executed;
- Gradle reached connectedDebugAndroidTest;
- Android reported instrumentation run failed because the target process failed to attach.

This is a repeated target-process attach infrastructure failure, not a
production semantic failure.

The prior SDK-location failure was separately corrected by session-scoped
ANDROID_HOME / ANDROID_SDK_ROOT and is not the cause of this run.

## Reviewer decision

An unchanged third instrumentation retry is not authorized.

A materially different infrastructure precondition is now required.

Canonical review history already permits one host-level cold restart of the
SAME AVD when the current emulator process is no longer trustworthy, provided:
- exact AVD identity is established;
- userdata is not wiped;
- no Quick Boot snapshot is loaded;
- no second parallel emulator is started;
- recovery is attempted only once;
- core Android services are revalidated before instrumentation.

Authorize that same narrow recovery here for the exact AVD currently backing
the approved device.

## Authorized recovery

1. Record exact AVD identity from non-destructive evidence, preferably:
   `adb -s emulator-5554 emu avd name`.
2. Require the identity to be `Medium_Phone_API_36.1`.
3. Stop the current emulator instance once using the narrowest locally valid
   mechanism for that exact instance.
4. Relaunch exactly `Medium_Phone_API_36.1` once with a cold boot that does
   not wipe userdata and does not load a Quick Boot snapshot.
5. Do not create, clone, substitute, reconfigure, or wipe an AVD.
6. Do not run a second recovery attempt if the cold restart does not restore a
   healthy Android runtime.
7. Wait boundedly for:
   - adb online;
   - sys.boot_completed=1;
   - system_server present;
   - PackageManager responsive;
   - ActivityManager responsive.
8. Preserve exact candidate 9aa6d972; make no source/test commit for recovery.

## Verification after successful recovery

After healthy same-AVD cold restart, exactly one complete
`com.ireum.ytdl.database.DownloadOutputProductionWiringTest` run is authorized
through the canonical verifier on candidate 9aa6d972.

Session-scoped Android SDK environment variables are allowed so the detached
exact-candidate worktree can resolve the local SDK. Do not copy local.properties
into the detached worktree and do not change persistent SDK configuration.

PASS requires nonzero execution, exact class/SHA/tree binding, and zero
unexplained failures/errors.

On PASS, run the detached exact-SHA diff gate, then resume the canonical 19-gate
union from partition 1.

On another zero-test attach failure after this cold restart:
- preserve evidence;
- classify SAME_AVD_COLD_RESTART_ATTACH_INVALID;
- do not repeat instrumentation or cold restart;
- stop for reviewer routing.

On a valid semantic failure:
- preserve first failure;
- apply the existing bounded semantic diagnostic policy;
- do not green-seek.

## State

BUG-DOWNLOAD-01 remains OPEN P2.
No finding is closed.
Repository-wide CLEAN is unsupported.
RESUME-01 API24/25 real-device proof remains a separate publication gate.
