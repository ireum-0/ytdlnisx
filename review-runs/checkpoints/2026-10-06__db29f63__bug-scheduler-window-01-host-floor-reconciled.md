# BUG-SCHEDULER-WINDOW-01 — host-floor reconciliation

record_kind: IMPLEMENTATION_STOP_REVIEW_RECONCILIATION
record_status: FINAL
manual_review_run: NO

implementation_remote_head: db29f63ce169176b4c8ade4cec01f66cc0307ec8
review_tip_before_checkpoint: 53a06887cb9adece856edbff743d8f40541b91a0
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
finding: BUG-SCHEDULER-WINDOW-01
severity: P2

## Reconciliation

The prior host-storage addendum imposed a project-operational 12 GiB free-space floor on the D:
worktree/build volume. That number was not an Android platform requirement, Gradle contract, source
invariant, or finding-specific semantic gate.

Current reported facts:
- D: free bytes: 10,837,168,128;
- C: AVD/evidence free bytes: 29,856,530,432;
- guest /data total/free: 16,729,985,024 / 15,180,087,296;
- exact APK pair total: 197,830,220 bytes;
- scheduler compilation and APK artifact proof already passed;
- focused JVM scheduler tests already passed 20/20;
- no new build is required merely to perform the pending install/device gate;
- candidate/evidence remain preserved.

Under REVIEW_PROTOCOL Section 3.2 minimum-necessary gating, requiring an extra 2,047,733,760 bytes on
D: before the already-built APK installation is an artificial gate and is superseded.

The bounded cleanup authorization in the immediately preceding checkpoint remains unused and is not
required for this continuation.

## Effective storage gate

HOST_D_CURRENT_FREE_BYTES=10837168128
HOST_D_12_GIB_POLICY=SUPERSEDED_AS_ARTIFICIAL_FOR_CURRENT_INSTALL_GATE
HOST_C_AVD_EVIDENCE_STATUS=SUFFICIENT
GUEST_DATA_STATUS=SUFFICIENT
APK_PAIR_TOTAL_BYTES=197830220
HOST_CLEANUP_REQUIRED_BEFORE_INSTALL=NO

For the current install/device gate:
- do not delete host files merely to satisfy the superseded 12 GiB number;
- preserve the current exact APK pair and candidate;
- proceed with installation after emulator identity is established.

For any later new build triggered by a valid same-root source change:
- re-check D: free space immediately before the build;
- current 10,837,168,128 bytes is sufficient to attempt the already-established scheduler build path;
- do not run a broad clean first;
- if an actual ENOSPC/insufficient-storage failure occurs, preserve first-failure evidence and stop;
- do not invent a larger arbitrary threshold in advance.

For exact-final-SHA closure when source remains unchanged until commit:
- reuse the normal incremental/focused build path;
- stop on actual host storage failure, not a speculative fixed floor.

## Guest identity

The empty AVD-name query is insufficient by itself to prove wrong-device identity. Before install,
establish the target using the strongest available combination of emulator serial/process mapping and
stable guest API/ABI/build/system properties. Stop only if multiple possible targets remain materially
ambiguous.

## Next action

Resume the preserved scheduler candidate:
1. establish exact designated emulator identity;
2. install the exact matching debug/androidTest APK pair;
3. run the scheduler device gate;
4. on PASS continue normal-forward publication and exact-final-SHA closure in the same wave;
5. on valid same-root semantic failure, preserve evidence and correct only within the scheduler root;
6. on actual host/device storage failure, preserve first failure and stop.

BUG_SCHEDULER_WINDOW_01_STATUS=OPEN_P2
SOURCE_CLOSURE=NOT_ESTABLISHED
EXECUTION_CLOSURE=NOT_ESTABLISHED
CANONICAL_COUNTS_CHANGE=NONE
INDEPENDENT_REVIEW_REQUIRED=YES
