# YTDLnisX storage-retention provenance audit authorization

checkpoint_kind: STORAGE_RETENTION_PROVENANCE_AUDIT_AUTHORIZATION
review_parent_sha: a2497ce3b434420ada21be635d5cfcc3e494f350
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Storage report received

The user supplied a completed read-only storage report covering only local YTDLnisX-related Android
Studio folders.

Reported totals:
- aggregate YTDLnisX-related logical size: 120.842 GiB;
- ytdlnisx-f11: 73.402 GiB;
- ytdlnisx: 45.166 GiB;
- ytdlnisx-f11-baseline: 2.274 GiB.

The report classified:
- 111.674 GiB / 92.41% as PROTECTED_EVIDENCE_OR_DIRTY_STATE;
- 8.056 GiB / 6.67% as REPRODUCIBLE_BUILD_OUTPUT;
- 119.153 MiB as PROJECT_LOCAL_CACHE;
- 1006.231 MiB as SOURCE_OR_REQUIRED_PROJECT_CONTENT;
- 13.280 MiB as UNKNOWN_DO_NOT_DELETE.

Major reported concentrations include:
- ytdlnisx-f11/build/sol-remediation-20260930: 42.810 GiB;
- ytdlnisx-f11/build/p2-batch: 25.966 GiB;
- multiple historical ytdlnisx root worktree/scratch directories around 1.5-2.5 GiB each.

No cleanup was performed.

## Reviewer disposition

The storage report does not by itself authorize deletion.

Existing NEXT_CHAT protected-state carry-forward explicitly protects historical remediation worktrees,
candidate ancestry, first-failure artifacts, verifier artifacts, and build/remediation-agent evidence.
Those protections remain in force until a reviewer explicitly releases a concrete path/evidence set.

The immediate next action is therefore a read-only provenance/retention inventory, not cleanup.

The implementation/diagnostic agent may gather local facts sufficient for the independent reviewer to
decide, path by path, whether a large historical container is:
- still uniquely required as correctness evidence;
- fully represented by immutable GitHub commits/checkpoints plus separately retained evidence;
- reproducible build/cache output within an otherwise protected container;
- ambiguous and therefore KEEP_PROTECTED.

The agent must NOT make the authoritative RELEASE_SAFE / KEEP_PROTECTED decision itself.

## Audit priority

Prioritize storage boundaries with the largest potential payoff:

1. ytdlnisx-f11/build/sol-remediation-20260930
2. ytdlnisx-f11/build/p2-batch
3. ytdlnisx root historical worktree/scratch boundaries reported around 2 GiB each, including
   wave-f10-f18-f20, .reconcile-arity, .f4-backup-04, .canonical-keyword04, .queue-*, and .tmp_f10_*
   families
4. large packaged/debug outputs and ordinary intermediates outside uniquely retained evidence

For each boundary, collect only the minimum facts required for reviewer disposition.

## Required facts per candidate boundary

Where applicable record:
- exact path and logical size;
- whether it is a Git worktree/repository, plain directory, build output, evidence container, or mixed;
- Git HEAD/tree/branch/worktree relation and dirty/untracked status;
- whether its commit/tree is present in or reachable from current GitHub refs, or otherwise named by
  immutable GitHub review/checkpoint history;
- exact NEXT_CHAT/checkpoint references that still require preserving it;
- local-only commits, dirty source, test harness changes, first-failure artifacts, verifier outputs, or
  diagnostics that are not durably represented elsewhere;
- whether build/remediation-agent or equivalent evidence is embedded in the boundary;
- whether reproducible build outputs can be separated from unique evidence without changing the
  protected source/evidence container;
- any ambiguity that would make deletion unsafe.

Do not recursively hash millions of ordinary build files. Use targeted metadata, Git object identity,
explicit evidence references, and storage-category boundaries.

## Safety

READ ONLY.

No deletion, movement, compression, cleanup, reset, restore, checkout, edit, build, test, commit, push,
stash mutation, worktree removal, gc/prune, or process termination is authorized.

The current dirty BUG-UPDATER-04 draft and its stop report remain protected and out of cleanup scope.

The audit report is evidence only. After it is returned, the independent reviewer will issue any
path-specific RELEASE_SAFE / KEEP_PROTECTED decision and any later cleanup authorization.

INDEPENDENT_EXECUTION: NOT EXECUTED
