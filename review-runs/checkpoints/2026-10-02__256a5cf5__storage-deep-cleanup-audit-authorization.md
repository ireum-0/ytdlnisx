# Deep cleanup audit authorization — large YTDLnisX historical containers

checkpoint_kind: STORAGE_DEEP_CLEANUP_AUDIT_AUTHORIZATION
review_parent_sha: 1a8f1c124e8ab6cb9a58011d2e5c69b5e878428d
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Superseded immediate cleanup

The previously persisted exact-root generated/cache cleanup prompt has NOT started.

The user explicitly chose to defer the small ~4.184 GiB cleanup in favor of one deeper retention audit
aimed at identifying materially larger safe reclaim opportunities before any deletion.

Therefore:
- the prior RELEASE_SAFE classification remains factually valid for its exact root-level paths;
- that cleanup is not the current next action;
- no deletion is authorized by this checkpoint.

## Deep-audit objective

The storage report and first provenance audit show the dominant storage is concentrated in:
- ytdlnisx-f11/build/sol-remediation-20260930
- ytdlnisx-f11/build/p2-batch
- historical worktree/scratch families under the main ytdlnisx root

The deep audit must separate, within these large containers:
1. KEEP_UNIQUE_EVIDENCE
2. KEEP_ACTIVE_OR_DIRTY_STATE
3. DELETE_REPRODUCIBLE_OUTPUT_CANDIDATE
4. DELETE_DUPLICATE_PUBLISHED_WORKTREE_CANDIDATE
5. UNKNOWN_KEEP

The agent gathers facts only. It must not make authoritative release decisions and must not delete anything.

## Priority targets

1. ytdlnisx-f11/build/sol-remediation-20260930
2. ytdlnisx-f11/build/p2-batch
3. ytdlnisx root historical worktree/scratch families, including:
   - wave-f10-f18-f20
   - .reconcile-arity
   - .f4-backup-04
   - .canonical-keyword04
   - .queue-*
   - .tmp_f10_*
4. nested app/build trees inside retained worktrees
5. APK/output duplication across clean published materializations
6. generated/intermediate/cache material nested inside otherwise protected containers

## Required proof

For every candidate reclaim boundary, determine:
- exact path and logical size;
- Git HEAD/tree/branch/worktree relation when applicable;
- clean/dirty/untracked state;
- whether source tree is exactly represented by an immutable GitHub commit;
- whether the directory contains any unique local source/test change;
- whether the directory contains any unique first-failure/verifier/UTP/log/profile/sidecar/APK/bytecode evidence;
- exact files/subdirectories that carry unique evidence;
- whether reproducible output can be deleted while preserving those evidence subpaths;
- whether the entire worktree/materialization is duplicate of a published GitHub tree and has no unique local evidence;
- whether Git common-dir/backing-store relationships make whole-directory removal unsafe;
- potential reclaim bytes for each candidate boundary without double counting.

Do not use broad name-based assumptions. A build directory inside a protected worktree is not automatically
releasable; prove that the exact candidate does not contain unique evidence.

## Safety

READ ONLY.

No deletion, movement, compression, source edit, build, test, Git mutation, worktree removal, stash
mutation, gc/prune, process termination, or cleanup action is authorized.

The current BUG-UPDATER-04 dirty draft, stop report, shared Git backing store, and every explicitly
protected evidence object remain protected.

## Expected report

Return a ranked table with:
- candidate path;
- size;
- classification proposal;
- exact unique-evidence blockers;
- exact GitHub-equivalence proof;
- separable reclaim size;
- whole-boundary reclaim size if fully duplicate;
- double-counting parent/child relationship;
- reviewer questions/ambiguities.

Also report:
- total unique reclaimable bytes under the conservative separable-output view;
- total additional reclaimable bytes if duplicate published worktrees are later released;
- total bytes that must remain protected;
- minimum set of exact reviewer release decisions needed for one large cleanup pass.

INDEPENDENT_EXECUTION: NOT EXECUTED
