# BUG-SCHEDULER-WINDOW-01 — bounded multi-path launch recovery authorization

record_kind: IMPLEMENTATION_STOP_REVIEW_RECONCILIATION
record_status: FINAL
manual_review_run: NO

implementation_remote_head: db29f63ce169176b4c8ade4cec01f66cc0307ec8
review_tip_before_checkpoint: 356be45a18bedda8b0410f006d38a850c43e8a71
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
finding: BUG-SCHEDULER-WINDOW-01
severity: P2

## Latest reported stop

Reported direct-adb recovery result:
- classification: HOST_ARGUMENT_OR_PARENT_CONTEXT_DEPENDENT_CREATEPROCESS_DENIAL;
- the one authorized direct adb instrumentation launch failed with spawn EPERM before child creation;
- the same canonical adb executable passed read-only controls;
- underlying cause remains NOT_VERIFIED;
- device tests executed: 0;
- focused JVM remains 20 PASS / 0 FAIL;
- exact dirty four-file candidate and empty index preserved;
- 15,469 prior protected records unchanged;
- 391 new records sealed and verified;
- no source edits, commits, publication, or exact-SHA closure;
- sealed local report:
  C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-direct-adb-20261006-6d4516e1/BUG_SCHEDULER_WINDOW_01_DIRECT_ADB_CREATEPROCESS_DENIAL_STOP_REPORT.md.

The latest manual L5 checkpoint at review tip 356be45a18bedda8b0410f006d38a850c43e8a71 is
forward-compatible with this scheduler wave. It adds BUG-APP-UPDATE-02 only and does not change the
BUG-SCHEDULER-WINDOW-01 contract.

## Independent disposition

BUG_SCHEDULER_WINDOW_01_STATUS=OPEN_P2
LOCAL_CORRECTION_STATUS=PRESERVED_UNCOMMITTED_NOT_GITHUB_AUTHORITATIVE
FOCUSED_JVM_STATUS=20_PASS_0_FAIL_REPORTED
DEVICE_TEST_EXECUTED_COUNT=0
DEVICE_RUNTIME_VERIFICATION=NOT_VERIFIED
LATEST_LAUNCH_RESULT=HOST_ARGUMENT_OR_PARENT_CONTEXT_DEPENDENT_CREATEPROCESS_DENIAL
HOST_LAUNCH_ROOT_CAUSE=NOT_VERIFIED
PRODUCTION_SEMANTIC_FAILURE=NOT_ESTABLISHED
PUBLICATION_STATUS=NOT_STARTED
SOURCE_CLOSURE=NOT_ESTABLISHED
EXECUTION_CLOSURE=NOT_ESTABLISHED
CANONICAL_COUNTS_CHANGE=NONE_FOR_SCHEDULER

The prior workflow authorized one launcher variant at a time. That repeated stopping is no longer
proportionate under REVIEW_PROTOCOL Section 3.2 because multiple non-destructive harness-only recovery
paths can be safely preauthorized without changing source, security policy, privileges, or device
contents beyond an ephemeral test-launch helper.

## One-wave bounded recovery ladder

A continuation is authorized to try the following in order, continuing automatically on ordinary
harness failure and stopping only after all applicable non-mutating paths fail or a material boundary
is reached.

### Path A — persistent canonical-adb interactive shell

Use the exact canonical adb.exe identity that passed read-only controls.

Launch only:
`adb -s <exact-serial> shell`

Then write the exact validated
`am instrument -w -r -e class <exact-test-target> <test-package>/<runner>`
command through that process's stdin.

Requirements:
- no host shell wrapper;
- exact serial/package/runner/class derived from installed state;
- capture shell/instrumentation output from the same adb process;
- one attempt.

This avoids placing the instrumentation command on the host CreateProcess argv.

### Path B — device-side ephemeral launch script

If Path A is unavailable because the harness cannot maintain stdin, or it fails before Android
instrumentation starts, use canonical adb only to:
1. create a tiny host TEMP text file outside every repository/worktree/evidence directory containing
   only the exact sanitized `am instrument ...` command;
2. push it to a unique path beneath `/data/local/tmp/`;
3. execute it using:
   `adb -s <exact-serial> shell sh <device-temp-path>`;
4. capture exact instrumentation output;
5. remove the device temp script and host temp file after evidence capture.

The temp script is execution-harness material, not repository source.

Do not modify APK contents, app data, system partitions, security settings, or package identities.

### Path C — existing ADB server socket, no new adb child

If host process creation itself prevents Paths A/B while the already-running adb server remains
reachable, one direct localhost ADB-server protocol path is authorized.

Constraints:
- connect only to the existing local adb server endpoint already proven by canonical adb;
- do not start/stop/replace the adb server;
- select the exact target serial explicitly;
- use only the minimum transport + shell service needed to execute the exact validated
  `am instrument ...` command;
- do not enumerate or mutate unrelated devices;
- capture raw instrumentation output;
- do not persist a new repository tool/harness;
- any temporary diagnostic code must live outside protected repositories and be deleted after sealed
  evidence is written;
- if the server protocol/response cannot be handled unambiguously, abandon this path without mutation.

This path exists specifically to avoid a new Windows child process while still using the already
running, authenticated adb server/device connection.

## Continuation semantics

Do not stop merely because Path A or B fails. Proceed to the next authorized path when the failure is
purely harness-level and preservation remains intact.

As soon as Android instrumentation actually starts, stop trying launcher variants and treat all
subsequent results at the Android test/runtime boundary.

If the scheduler device gate passes, continue directly in the same implementation run through:
- required pre-publication checks;
- narrow normal-forward scheduler commit;
- fast-forward publication;
- exact-final-SHA focused + neighboring regressions;
- exact-final-SHA scheduler device gate through the proven launch path;
- clean worktree/index and remote equality.

If a real same-root scheduler semantic failure appears, preserve first evidence and correct only
within BUG-SCHEDULER-WINDOW-01, then rerun the required gates in the same wave.

## True stop boundaries

Stop only when:
- all applicable non-mutating Paths A/B/C fail before instrumentation starts;
- recovery requires Administrator/elevation, ACL/ownership change, Defender/antivirus exclusion,
  execution-policy/security-policy mutation, SDK/platform-tools/Java/Gradle/emulator reinstall or
  update;
- target/device/package identity becomes ambiguous;
- protected evidence/worktree state cannot be preserved;
- instrumentation establishes a new semantic root outside BUG-SCHEDULER-WINDOW-01;
- incompatible Git/review/history movement occurs.

Do not stop between ordinary non-mutating recovery paths.

## Forbidden

Do not:
- edit production/test source merely to bypass host launch restrictions;
- run as Administrator;
- change ACLs/security policy/antivirus;
- disable security controls;
- install/update toolchains;
- use an unverified adb binary;
- repeatedly retry the same failed launch path;
- convert zero executed tests into PASS.

## Preservation

Preserve:
- exact four-file scheduler draft and empty index;
- exact installed APK identities;
- designated emulator/storage state;
- all 15,469 prior protected records plus 391 newly sealed direct-adb diagnostic records;
- all other protected scheduler/PO-token/updater/history evidence and worktrees.

INDEPENDENT_REVIEW_REQUIRED=YES
