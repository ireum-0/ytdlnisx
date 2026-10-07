# Scheduler owner-release continuation — bounded D: cleanup authorization

Date: 2026-10-07

record_kind: BOUNDED_STORAGE_CLEANUP_AUTHORIZATION
record_status: FINAL
manual_review_run: NO

implementation_remote_head: eda6a7589af3a19a97eb38e869b47dabaf74388b
review_parent_sha: 49fd0aed630f236b331f2ea4bc00d4a7b1bac452
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
canonical_count_change: NONE
canonical_status_change: NONE
implementation_source_change: NONE

## User authorization

The user explicitly requested that the next persisted continuation perform actual D: cleanup using the
already-completed YTDLnisX storage analysis, rather than producing another candidate table.

This authorization is limited to already-ranked, reproducible build/cache paths and does not authorize
repository/worktree deletion, evidence deletion, source deletion, Git cleanup, or broad disk cleanup.

## Existing audit basis

Local audit report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-D-storage-audit-20261007-b90ad081/STORAGE_REPORT_FINAL.md

The preserved audit measured 119,265,378,935 logical bytes (111.075 GiB) across:
- ytdlnisx-f11: 71.347 GiB
- ytdlnisx: 37.521 GiB
- ytdlnisx-f11-baseline: 2.207 GiB

It classified:
- REPRODUCIBLE_BUILD_OUTPUT: 47,675,484,762 bytes
- PROJECT_LOCAL_CACHE: 1,166,664,624 bytes
- PROTECTED_EVIDENCE_OR_DIRTY_STATE: 24,906,614,670 bytes
- SOURCE_OR_REQUIRED_PROJECT_CONTENT: 42,461,103,460 bytes
- UNKNOWN_DO_NOT_DELETE: 3,055,511,419 bytes

The report already ranked exact generated paths, primarily app/build/intermediates directories.
Do not repeat the 111 GiB recursive scan merely to select candidates again.

## Cleanup target

The earlier operational shortage was 4,067,598,336 bytes.
That value remains superseded as a build correctness gate, but it is a concrete, user-requested
housekeeping recovery target for this cleanup wave.

TARGET_MINIMUM_RECLAIM_BYTES=4067598336

Stop cleanup once either:
- measured D: free-space increase is at least 4,067,598,336 bytes relative to the cleanup start; or
- every eligible ranked generated/cache candidate has been exhausted.

Do not continue deleting after the target is reached merely to maximize free space.

## Eligible deletion scope

A path may be deleted only if ALL are true immediately before deletion:

1. the exact path appears in the preserved audit's Ranked cleanup candidates table, or is an exact
   PROJECT_LOCAL_CACHE path in the preserved audit analysis;
2. it is a generated/cache subtree such as app/build/intermediates or equivalent project-local
   cache, not a repository/worktree root;
3. the exact path is untracked/ignored/regenerable and contains no tracked source, Git metadata,
   sealed report/evidence, retained APK/artifact, logs required by current evidence, SDK/toolchain,
   AVD data, or user-authored content;
4. the owning worktree/repository is live-verified clean enough that deleting that generated subtree
   cannot consume dirty or staged source state;
5. the path is not within any currently protected worktree or protected ancestor listed below;
6. no current test/build process is using the path;
7. a pre-delete path/size/readback is recorded.

Delete candidates one at a time or in a small bounded batch, largest eligible first.
After each deletion/batch:
- remeasure D: free bytes;
- confirm scheduler and all protected dirty worktrees still have exact HEAD/tree/index/dirty-path identity;
- record actual free-space delta.

## Hard exclusions

Never delete or mutate:
- D:/AndroidStudioProjects/ytdlnisx-f11/build itself as an enclosing parent;
- any repository/worktree root or .git/common-dir/object/index data;
- any remediation-agent descendant unless a later explicit authorization names an exact generated child;
- current scheduler protected candidate or any generated child under it while its evidence is active;
- protected FMT BG-01/BG-02 worktree or any of its generated children;
- protected PO Token, updater03, history05 worktrees or their generated children;
- current/historical APKs explicitly retained by evidence;
- reports, logs, manifests, first-failure traces, sealed evidence directories;
- SOURCE_OR_REQUIRED_PROJECT_CONTENT;
- PROTECTED_EVIDENCE_OR_DIRTY_STATE;
- UNKNOWN_DO_NOT_DELETE;
- the missing-index/not-verified root:
  D:/AndroidStudioProjects/ytdlnisx-f11/build/debug-isolation-repo-df63c06ca819
  and all its descendants;
- Android SDK/system images/emulator binaries/AVD data;
- global Gradle caches;
- arbitrary Temp, Downloads, Documents or unrelated project content.

Named protected dirty candidates remain:
- scheduler: dirty tree 18b3d5d6567877bf6c0811007047f54b59d8523d, 15 unstaged, empty index;
- FMT: dirty tree b3e7718074f66e62413d9eaae745da2d26b41cb3, 16 unstaged, empty index;
- PO Token: 18 dirty files, empty index;
- updater03: 10 dirty files, empty index;
- history05: 2 dirty files, empty index.

In particular, audit-ranked generated paths under pot-mvp, fmt-producer, scheduler-authority,
history05 and updater03 are NOT eligible while those worktrees remain protected.

## Relation to scheduler remediation

Storage cleanup is operational housekeeping only. It must not:
- change scheduler source or tests;
- alter the classification of the 4 PASS / 1 FAIL owner-release gate;
- erase first-failure evidence;
- create a new reason to rerun an unchanged semantic failure.

After bounded cleanup completes, continue the scheduler owner-release diagnostic-first remediation
from the existing 15-file candidate under the governing same-root continuation rules.

If cleanup encounters ownership ambiguity, protected-state mismatch, permission/lock errors that require
process termination outside the authorized build/test process, or cannot safely reclaim the target,
stop cleanup and continue/report under the prompt's material stop rules. Do not broaden deletion scope.

INDEPENDENT_REVIEW_REQUIRED=YES
