# Scheduler authority wave — build-margin reconciliation

Date: 2026-10-07

record_kind: IMPLEMENTATION_STOP_REVIEW_RECONCILIATION
record_status: FINAL
manual_review_run: NO

implementation_remote_head: eda6a7589af3a19a97eb38e869b47dabaf74388b
review_parent_sha: 4da38e9579823e528c35b6d8d044d55673f8b238
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d

active_findings:
- BUG-SCHEDULER-WINDOW-01
- BUG-SCHEDULER-RESTORE-01

## Latest reported recovery stop

The storage/schema recovery continuation reached its Phase 1 stop boundary.

Reported exact preserved state:
- implementation base:
  eda6a7589af3a19a97eb38e869b47dabaf74388b;
- scheduler dirty tree:
  7a99245b3468cf8738d2693eaf1ab858d5da72a0;
- 13 unstaged files;
- index empty;
- D: still reported 4,067,598,336 bytes short against the continuation's preflight build margin;
- all authorized worktree-local build/cache directories were absent;
- reclaimable bytes within that authorized scope: 0;
- no deletions;
- no source edits;
- no AVD changes;
- no builds or tests;
- no commits or pushes;
- 17,971 protected records verified unchanged;
- 341 new records sealed.

Reported sealed local stop report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-system-recovery-20261007-0ab70692/SCHEDULER_STORAGE_RECOVERY_SCOPE_LIMIT_STOP_REPORT.md

The local report and candidate are not GitHub-hosted and were not independently opened here. Their
reported identities are recorded for preservation.

## Reconciliation

The current 4,067,598,336-byte shortage is against a preflight operational margin.

Current evidence does NOT establish:
- an actual Gradle build ENOSPC;
- an OS write failure;
- a compiler/linker/package task reporting insufficient host storage;
- a platform/tool contract requiring that exact additional 4,067,598,336 bytes;
- a measured exact future build-output requirement equal to that margin.

No build has been attempted after the preserved 13-file candidate reached this stop.

The prior scheduler host-floor reconciliation established the same governing principle:
a conservative free-space floor is not, by itself, a semantic or platform correctness gate. When a
build is actually required, storage should be checked responsibly, but the workflow should stop on an
actual storage failure or on a concrete build-footprint requirement, not on an ungrounded margin alone.

Therefore the latest preflight margin is superseded as a hard gate for ONE bounded build attempt under
the controls below.

This does not assert that D: has sufficient storage. It authorizes an evidence-producing attempt that
can establish whether the real build path fits.

## Effective host-space policy for the next attempt

PREVIOUS_BUILD_MARGIN_SHORTAGE_BYTES=4067598336
PREVIOUS_MARGIN_AS_HARD_GATE=SUPERSEDED_FOR_ONE_BOUNDED_BUILD_ATTEMPT
ACTUAL_BUILD_ENOSPC_OBSERVED=NO
ACTUAL_REQUIRED_ADDITIONAL_BYTES=NOT_VERIFIED

Before the attempt:
1. re-measure D: and C: free bytes;
2. preserve exact scheduler candidate identity and all protected evidence;
3. route NEW regenerable Gradle state away from D: where supported without source changes:
   - GRADLE_USER_HOME on C:;
   - JVM/system temp on C:;
   - Gradle project cache on C: through supported command-line/runtime configuration;
4. do not move/copy/reconstruct the dirty scheduler source candidate;
5. do not delete anything merely to satisfy the superseded margin.

Then run exactly ONE required build/compile invocation from the original scheduler wave.

If it succeeds:
- the preflight margin is empirically disproved as necessary for this exact build path;
- continue the governed scheduler tests and schema-clean verification environment work without restoring
  that margin as a gate.

If it fails with actual ENOSPC/insufficient host storage:
- preserve the first failure;
- record the exact failing task/path/volume;
- record free bytes at failure;
- when observable, record exact bytes requested/short;
- STOP before retry or cleanup;
- do not rerun merely to seek green.

If it fails for a non-storage semantic/build reason:
- classify under the original scheduler prompt; do not mislabel it as storage.

## No broadened cleanup authorization

This checkpoint does NOT authorize:
- global Gradle cache deletion;
- Android SDK/system-image deletion;
- arbitrary Temp cleanup;
- unrelated build-output deletion;
- protected report/evidence deletion;
- FMT draft mutation;
- moving the scheduler dirty candidate;
- broad disk cleanup.

The absence of worktree-local generated state is sufficient proof that the previous cleanup route is
exhausted.

## Schema environment remains separately governed

The schema-64 emulator remains incompatible with the exact candidate's schema-63 verification basis.

After the bounded build succeeds:
- continue the already-authorized fresh same-image AVD / one designated-AVD wipe fallback;
- do not downgrade/edit schema-64 DB state;
- allow the exact candidate to create fresh schema-63 state normally.

No AVD mutation is required before the build itself unless the build command materially depends on it.

## Candidate disposition

SCHEDULER_DRAFT_STATUS=PRESERVED_UNCOMMITTED
SCHEDULER_DRAFT_TREE=7a99245b3468cf8738d2693eaf1ab858d5da72a0
SCHEDULER_DRAFT_FILE_COUNT=13_UNSTAGED
SCHEDULER_INDEX_STATE=EMPTY
SCHEDULER_PUBLICATION=NOT_STARTED
SCHEDULER_EXACT_SHA_CLOSURE=NOT_EXECUTED
PRODUCTION_FAILURE_FROM_LATEST_STOP=NOT_ESTABLISHED

Protected FMT candidate remains:
- tree b3e7718074f66e62413d9eaae745da2d26b41cb3;
- 16 unstaged files;
- empty index;
- publication NOT_STARTED;
- exact-SHA closure NOT_EXECUTED.

## Next governed action

Persist one continuation prompt that:
1. resumes the exact scheduler 13-file draft;
2. supersedes the 4,067,598,336-byte preflight shortage as a hard gate for one bounded build attempt;
3. routes regenerable Gradle cache/temp to C: where supported;
4. stops on first actual ENOSPC rather than retrying;
5. on successful build continues to clean schema-63 verification environment;
6. then resumes original scheduler tests -> publication -> exact-final-SHA closure in the same wave.

INDEPENDENT_REVIEW_REQUIRED=YES
