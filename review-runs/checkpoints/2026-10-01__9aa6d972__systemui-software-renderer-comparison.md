# 9aa6d972 same-AVD System UI instability — software-renderer comparison authorized

checkpoint_kind: INFRASTRUCTURE_RECOVERY_AUTHORIZATION
review_parent_sha: 34ba91e206360dea5cd220bbe686b6ba3ade8ad9
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 9aa6d972eafd885046cc6b2643f7f3c053c2527e
reported_local_candidate_parent: c5df12ad37dc54aacdd6ba13262db23ac6f56beb
reported_local_candidate_tree: bad5a70d1887d18d90c4c484a39292cccad03123
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
count_change: 0

## New infrastructure evidence

The exact same AVD Medium_Phone_API_36.1 completed a no-snapshot cold boot and
core services became available, but the user reports frequent System UI
not-responding freezes.

The cold-boot startup log also reported:
- Emulator 36.3.10;
- host graphics path selected;
- bundled software OpenGL load failure followed by fallback to system OpenGL;
- both NVIDIA discrete and Intel integrated host GPUs detected.

This evidence does not establish an application semantic failure.

## Reviewer decision

Do not run the DownloadOutput verifier while System UI/runtime responsiveness is
unstable.

Authorize one comparison launch of the SAME AVD using a non-persistent
software-rendering launch flag:
- same AVD Medium_Phone_API_36.1;
- no userdata wipe;
- no snapshot load;
- no AVD edit;
- no SDK/emulator update;
- no source/test/config commit;
- use command-line `-gpu swiftshader_indirect` for this Emulator 36.3.10
  comparison.

This is a materially changed graphics-runtime precondition, not an unchanged
instrumentation retry.

## Acceptance boundary

After launch, require bounded healthy runtime evidence:
- adb online;
- sys.boot_completed=1;
- system_server present;
- PackageManager responsive;
- ActivityManager responsive;
- no recurring System UI ANR during a short idle/interaction observation.

Only after that may one complete DownloadOutputProductionWiringTest run be
attempted on exact candidate 9aa6d972.

If System UI still repeatedly ANRs under SwiftShader, do not keep cycling GPU
modes or rerunning instrumentation. Preserve the evidence and stop for a new
infrastructure decision.

If the software-renderer launch is stable but the next test again executes zero
tests with failed-to-attach, preserve it and stop; do not retry.

## State

BUG-DOWNLOAD-01 remains OPEN P2.
No finding is closed.
Repository-wide CLEAN is unsupported.
