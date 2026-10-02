# YTDLnisX one-pass cleanup authorization — deep exact-file manifest plus root generated/cache

checkpoint_kind: STORAGE_ONE_PASS_CLEANUP_AUTHORIZATION
review_parent_sha: c138f43d41fd69d05e854213ac9a8d75241879f6
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Deep cleanup audit received

The implementation agent reported completion of the read-only deep cleanup audit and no mutations.

Reported authoritative closing refs:
- private ytdlnisx-review = 410e3f760d2d903cdfa940daf8b8ac1df0078e0c
- review/remediation = c138f43d41fd69d05e854213ac9a8d75241879f6
- checkpoint/pre-baseline-review = 256a5cf507b54adcca0342b82ddaf6e2d75a684e

Reported local audit artifacts:
- C:/Users/dh2/AppData/Local/Temp/ytdlnisx-deep-cleanup-audit-ba721b7b03424eedacb36bddef85fe95/REPORT.md
- C:/Users/dh2/AppData/Local/Temp/ytdlnisx-deep-cleanup-audit-ba721b7b03424eedacb36bddef85fe95/cleanup-candidates.csv

Reported deep-manifest facts:
- 6,110 exact candidate files;
- 59 candidate contexts;
- non-overlapping conservative reclaim total = 36,804,472,078 logical bytes;
- every candidate file has full SHA256 equality to one of 176 retained keeper files;
- candidate contexts are clean and source commits are represented in GitHub;
- no candidate overlaps a keeper or inventoried execution-evidence file;
- no hard-link/compression/sparse-file special allocation condition was reported;
- all 90 checked HEAD/tree/status records remained unchanged;
- the protected BUG-UPDATER-04 ten-file draft hashes still matched the stop report;
- whole-worktree release was NOT established;
- shared Git stores remain protected.

The reviewer does not independently claim direct filesystem inspection of the CSV.

## Reviewer decision

### CONDITIONAL RELEASE_SAFE — deep exact-file manifest

The 6,110 exact files enumerated in cleanup-candidates.csv are released from correctness-evidence
retention ONLY IF a cleanup preflight re-establishes every one of these conditions immediately before
deletion:

1. The manifest contains exactly 6,110 distinct candidate file paths.
2. The aggregate manifest logical size equals exactly 36,804,472,078 bytes.
3. Every candidate file still exists at the exact enumerated path and still has the exact SHA256 recorded
   in the manifest/report.
4. Every candidate file still has a full SHA256-equal retained keeper file, and the keeper file exists
   outside the deletion set.
5. No candidate path is also a keeper path.
6. No candidate path is part of the inventoried 63,913 evidence files.
7. No candidate path is inside the current BUG-UPDATER-04 dirty ten-file draft or one of its protected
   precommit materializations.
8. No candidate path is a Git common directory/backing store, .git storage, remediation-agent evidence,
   report/log/UTP/profile/sidecar/bugreport/first-failure artifact, or other explicitly protected path.
9. Candidate context HEAD/tree/status is unchanged from the deep-audit report where that context is
   Git-backed.
10. The current protected ten-file BUG-UPDATER-04 draft still matches the stop report before cleanup.
11. No manifest path resolves through a reparse point/symlink/junction outside the audited YTDLnisX
    context.
12. No candidate is added by wildcard or inference; only exact manifest rows are eligible.

If any condition fails or cannot be re-established, do not delete any deep-manifest file. Stop and report
the failed precondition.

### RELEASE_SAFE — prior exact root-level generated/cache boundaries

The prior exact root-level release remains valid and may be executed in the same cleanup wave:

Roots:
- D:/AndroidStudioProjects/ytdlnisx
- D:/AndroidStudioProjects/ytdlnisx-f11
- D:/AndroidStudioProjects/ytdlnisx-f11-baseline

Within each root only:
- app/build/intermediates
- app/build/generated
- app/build/kotlin
- app/build/kspCaches
- app/build/tmp/kotlin-classes
- .gradle

The deep audit explicitly excluded this prior root cleanup from its 36,804,472,078-byte total, so it may
be combined without intentional double counting.

## KEEP_PROTECTED

Remain protected:
- current BUG-UPDATER-04 dirty ten-file draft;
- all protected precommit snapshots;
- every retained keeper file;
- every inventoried unique/retained execution-evidence file;
- ytdlnisx-f11/build/tooling-0102b shared Git backing store;
- Git common directories and .git storage;
- whole worktrees/materializations not explicitly released;
- app/build/outputs/APKs except only exact deep-manifest files whose keeper equivalence and manifest
  preconditions pass;
- test/UTP/report/log/profile/sidecar/bugreport/first-failure/verifier/remediation-agent evidence;
- unresolved/ownership-blocked/UNKNOWN_KEEP material;
- any historical source/test state not proven duplicate;
- any path protected by NEXT_CHAT and not explicitly released by this checkpoint.

## One-pass cleanup boundaries

The cleanup wave may:
1. delete only exact deep-manifest files after the all-or-nothing deep-manifest preflight succeeds;
2. delete the prior exact root-level generated/cache directories after their independent path/protection
   checks succeed.

Do not delete parent historical worktree directories merely because many files inside are released.
Do not remove worktrees.
Do not run git clean/reset/gc/prune.
Do not terminate processes to force deletion.
Locked paths are skipped and reported rather than force-removed.

## Post-cleanup integrity

After cleanup:
- verify implementation HEAD remains 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
- re-verify all protected BUG-UPDATER-04 ten-file hashes against the stop report;
- verify keeper files used for deep-manifest equivalence still exist;
- report exact deleted/skipped paths and bytes;
- report free bytes before/after;
- do not run tests in this cleanup task.

This cleanup does not close BUG-UPDATER-04, alter canonical counts, or create a Known-Good Baseline.

After the cleanup report, reviewer must reconcile capacity and protected-state integrity before resuming
the previously blocked focused BUG-UPDATER-04 verification gate.

INDEPENDENT_EXECUTION: NOT EXECUTED
