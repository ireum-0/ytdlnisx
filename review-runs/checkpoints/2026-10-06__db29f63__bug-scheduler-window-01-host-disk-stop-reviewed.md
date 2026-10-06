# BUG-SCHEDULER-WINDOW-01 — host-disk stop review

record_kind: IMPLEMENTATION_STOP_REVIEW
record_status: FINAL
manual_review_run: NO

implementation_remote_head: db29f63ce169176b4c8ade4cec01f66cc0307ec8
review_tip_before_checkpoint: 15a6d6287413be4184382e0c7241ea850f3eaa1c
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
finding: BUG-SCHEDULER-WINDOW-01
severity: P2

## Stop classification

Valid stop-rule report with no implementation publication.

Reported evidence:
- C: AVD/evidence free bytes at completion: 29,856,530,432;
- D: worktree/build free bytes at completion: 10,837,168,128;
- exact additional D: bytes required by the governing 12 GiB build/worktree floor:
  2,047,733,760;
- designated AVD wiped once and configured for 16 GiB;
- guest /data: 16,729,985,024 total, 15,180,087,296 free;
- full guest identity remains NOT_VERIFIED because the AVD-name query returned empty output;
- APK pair total: 197,830,220 bytes;
- no APK installation attempted after host preflight failed;
- no new build/test/source edit/commit/push/cleanup;
- prior focused scheduler JVM evidence remains 20/20 PASS;
- exact dirty candidate and 13,637 protected evidence records reported preserved;
- sealed local report:
  C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-storage-resume-20261006-a055ebdb/BUG_SCHEDULER_WINDOW_01_HOST_DISK_BLOCK_STOP_REPORT.md.

## Independent disposition

BUG_SCHEDULER_WINDOW_01_STATUS=OPEN_P2
LOCAL_CORRECTION_STATUS=PRESERVED_UNCOMMITTED_NOT_GITHUB_AUTHORITATIVE
DEVICE_STORAGE_STATUS=RECOVERED_CAPACITY_SUFFICIENT
HOST_C_AVD_EVIDENCE_VOLUME_STATUS=PASS
HOST_D_WORKTREE_BUILD_VOLUME_STATUS=BLOCKED_BY_2047733760_BYTES
DEVICE_INSTALL_STATUS=NOT_EXECUTED_AFTER_HOST_PREFLIGHT
DEVICE_GATE_STATUS=NOT_EXECUTED
PUBLICATION_STATUS=NOT_STARTED
SOURCE_CLOSURE=NOT_ESTABLISHED
EXECUTION_CLOSURE=NOT_ESTABLISHED
CANONICAL_COUNTS_CHANGE=NONE

The AVD storage blocker is no longer the active cause. The next material boundary is host build-volume
capacity on D:.

The prior host-storage addendum explicitly required later reviewer/user authorization before deleting
otherwise disposable build outputs. This checkpoint grants a narrow reviewer authorization for
reclaiming D: space from proven generated, reproducible, non-protected build artifacts only.

## Narrow cleanup authorization

A continuation may delete generated build/cache material only after proving each selected path:
1. is not source, Git metadata, a dirty/untracked candidate, sealed report/evidence, or a protected
   worktree;
2. is reproducible from the preserved source/candidate and pinned toolchain;
3. is not the sole retained copy of an artifact needed as evidence;
4. does not contain or parent any protected nested worktree/evidence location.

Priority:
- project/module build intermediates and temporary compilation outputs belonging to the scheduler
  verification worktree;
- project-local generated build cache/state only when its regeneration does not require changing the
  pinned toolchain or source;
- preserve exact current APK pair unless first copied byte-for-byte with hashes into the C: sealed
  evidence volume.

Never run a broad parent-directory clean when protected worktrees may be nested beneath that parent.
Never run repository-wide cleanup, Git clean/reset, broad Windows cleanup, SDK cleanup, or unrelated
AVD deletion.

Delete only enough proven disposable material to establish the governing D: free-space floor with
reasonable execution headroom. Re-measure after each bounded deletion group and stop deleting once the
floor is established.

If no safely provable generated set can reclaim the needed space, stop and report exact candidate
paths/sizes rather than deleting ambiguous data.

## Remaining execution

After D: satisfies the host floor:
- verify complete designated guest identity using an authoritative emulator/adb property combination
  rather than relying solely on the empty AVD-name query;
- re-establish exact APK identity if any build output was regenerated;
- enforce both host and device storage preflight;
- install the exact debug/androidTest pair;
- run the scheduler device gate;
- on PASS continue the original publication and exact-final-SHA closure in the same wave.

INDEPENDENT_REVIEW_REQUIRED=YES
