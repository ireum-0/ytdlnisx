# BUG-SCHEDULER-WINDOW-01 — direct ADB launch recovery authorization

record_kind: IMPLEMENTATION_STOP_REVIEW_RECONCILIATION
record_status: FINAL
manual_review_run: NO

implementation_remote_head: db29f63ce169176b4c8ade4cec01f66cc0307ec8
review_tip_before_checkpoint: e2aa6080f1e695fe35e7f6aca19e692824887db5
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
finding: BUG-SCHEDULER-WINDOW-01
severity: P2

## Latest reported stop

The implementation report states:
- persisted launch diagnostic ended at LAUNCH_FAILURE_INDETERMINATE;
- canonical adb version/connectivity controls passed;
- instrumentation creation failed with WinError 5 before a child started;
- bounded security-log inspection found no matching event;
- exact cause remains NOT_VERIFIED;
- focused JVM result remains 20 PASS / 0 FAIL;
- device tests executed: 0;
- exact four-file draft at db29f63 and empty index preserved;
- 14,871 prior protected records remained unchanged;
- 598 new evidence records were sealed and verified;
- no source edits, commits, publication, or exact-SHA closure occurred;
- sealed local report:
  C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-launch-diagnostic-20261006-9207d41c/BUG_SCHEDULER_WINDOW_01_LAUNCH_DIAGNOSTIC_STOP_REPORT.md.

The local report itself is not stored on GitHub and is not independently inspected in this checkpoint.
The continuation must read it locally before executing the authorized recovery path.

## Independent disposition

BUG_SCHEDULER_WINDOW_01_STATUS=OPEN_P2
LOCAL_CORRECTION_STATUS=PRESERVED_UNCOMMITTED_NOT_GITHUB_AUTHORITATIVE
FOCUSED_JVM_STATUS=20_PASS_0_FAIL_REPORTED
DEVICE_TEST_EXECUTED_COUNT=0
DEVICE_RUNTIME_VERIFICATION=NOT_VERIFIED
LAUNCH_DIAGNOSTIC_RESULT=LAUNCH_FAILURE_INDETERMINATE
HOST_PROCESS_CREATION_ROOT_CAUSE=NOT_VERIFIED
PRODUCTION_SEMANTIC_FAILURE=NOT_ESTABLISHED
PUBLICATION_STATUS=NOT_STARTED
SOURCE_CLOSURE=NOT_ESTABLISHED
EXECUTION_CLOSURE=NOT_ESTABLISHED
CANONICAL_COUNTS_CHANGE=NONE

Canonical adb executable creation and connectivity are already proven by the reported diagnostic.
Therefore it is unnecessary to require Gradle, Java, a shell wrapper, or another local launcher merely
to start Android instrumentation.

Under REVIEW_PROTOCOL Section 3.2 minimum-necessary gating and Section 16.1 harness-vs-production
classification, one direct canonical-adb instrumentation path is authorized.

## Authorized launch recovery path

Use the exact canonical adb.exe identity that passed version/connectivity controls.

Before execution:
1. read the sealed launch-diagnostic report;
2. verify the same adb path/hash/version from the passing controls;
3. verify exact target serial/emulator identity;
4. verify the already-installed test instrumentation registration using read-only adb commands, for
   example `adb -s <serial> shell pm list instrumentation`, and derive exact test package/runner from
   installed package state and the known androidTest artifact;
5. verify the exact scheduler test class/method target from the preserved test source/artifact contract.

Then launch instrumentation DIRECTLY with canonical adb.exe:
- no Gradle launcher;
- no Java launcher;
- no .bat/.cmd/PowerShell script wrapper;
- no `shell=true` host wrapper;
- no second local child helper for output capture;
- no alternate adb installation;
- pass argv directly to canonical adb.exe.

The intended form is semantically equivalent to:

`<canonical-adb.exe> -s <exact-serial> shell am instrument -w -r -e class <exact-test-target> <test-package>/<runner>`

Use the exact installed package/runner/test target discovered from the current artifact and device
state; do not guess identifiers from this checkpoint.

Capture stdout/stderr directly from that single adb process.

One direct launch attempt is authorized after the prechecks.

## Result handling

A. If canonical adb.exe itself fails CreateProcess/WinError 5 on this direct command while the same
exact executable can still run version/devices/read-only shell controls:
- preserve exact argv/cwd/environment and failure;
- STOP;
- classify as HOST_ARGUMENT_OR_PARENT_CONTEXT_DEPENDENT_CREATEPROCESS_DENIAL unless stronger evidence
  proves another cause;
- do not elevate, alter ACLs, disable security software, or change policy.

B. If adb launches and `am instrument` starts:
- the host WinError-5 blocker is discharged;
- run the exact scheduler instrumentation target;
- classify subsequent failure at the Android instrumentation/test boundary, not as host process
  creation failure.

C. If adb launches but Android reports test package/runner/class resolution failure:
- verify installed test package/runner/class identity;
- a non-mutating correction to command identifiers is authorized when exact installed state proves
  the prior identifier was wrong;
- do not modify source merely to match a guessed command.

D. If instrumentation executes:
- record exact tests run/pass/fail/skip;
- on PASS continue the original same-wave publication + exact-final-SHA closure;
- on valid same-root scheduler failure preserve first evidence and correct only within
  BUG-SCHEDULER-WINDOW-01;
- on new semantic root stop for independent review.

## Forbidden recovery

Do not:
- run as Administrator solely to make the command work;
- change file/directory ACLs;
- add antivirus/Defender exclusions;
- disable security controls;
- change PowerShell execution policy;
- reinstall/update platform-tools, SDK, Java, Gradle, or emulator;
- switch to an unverified adb binary;
- modify production/test source before a real instrumentation semantic failure is observed;
- retry multiple host launcher variants in search of green.

## Preservation

Preserve:
- exact four-file scheduler draft and empty index;
- installed APK identities;
- emulator/storage state;
- all 14,871 prior protected records plus the 598 sealed launch-diagnostic records;
- all previously protected scheduler/PO-token/updater/history evidence and worktrees.

INDEPENDENT_REVIEW_REQUIRED=YES
