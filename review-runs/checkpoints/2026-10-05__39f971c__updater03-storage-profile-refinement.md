# BUG-UPDATER-03 — repeated emulator storage blocker diagnostic refinement

checkpoint_kind: INFRASTRUCTURE_RECOVERY_REFINEMENT
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: 816bc988a4ca025873f091fc11b0f7b80b2eed13
implementation_sha: 39f971cdca7712e60046dbdf346847ce9c74924a
implementation_remote_changed: NO
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
overall_verdict: READ_ONLY_STORAGE_PROFILE_REQUIRED_BEFORE_BOUNDED_RECOVERY
canonical_p0: 0
canonical_p1: 0
canonical_p2: 2
canonical_open_roots: BUG-UPDATER-03,BUG-HISTORY-05
clean_review_basis_advance: NO
independent_execution: NOT_EXECUTED

## Rationale

INSTALL_FAILED_INSUFFICIENT_STORAGE has now occurred repeatedly on the same authorized API 36 x86_64 emulator across remediation waves.

A repeated infrastructure blocker should not be treated as an opaque transient condition. Before deleting anything, the implementation agent must collect a bounded read-only storage profile that distinguishes at least:
- an undersized/full emulator /data partition;
- accumulated YTDLnisX debug/test package data or APKs;
- accumulated install/staging artifacts such as /data/local/tmp;
- other large unrelated consumers that explain the pressure but are not authorized for deletion.

This diagnostic is infrastructure evidence only. It does not alter BUG-UPDATER-03 semantic status and does not authorize broad cleanup.

## Required read-only profile

Before cleanup or install retry, while preserving the same emulator:

1. Re-verify:
   - emulator serial;
   - API level;
   - ABI;
   - AVD identity if available.

2. Record filesystem capacity/free-space evidence for:
   - /data;
   - /data/local/tmp when separately reportable;
   - any package-install relevant filesystem exposed by the emulator.

Use read-only platform commands such as df/dumpsys diskstats or an equivalent non-mutating source.

3. Record YTDLnisX package inventory:
   - debug target package path/identity when installed;
   - matching instrumentation/test package path/identity when installed;
   - package-owned data size when it can be measured read-only, including run-as for the debuggable package when supported.

4. Record test-owned temporary/staging usage:
   - /data/local/tmp entries attributable to this verification attempt or Android package installation tooling where ownership can be proven;
   - sizes, not just filenames.

5. Record AVD capacity metadata from the host when available:
   - configured data-partition size;
   - userdata image logical size and host physical allocation;
   - do not resize, compact, wipe, recreate or edit the AVD.

6. Produce a before-cleanup summary:
   - total /data capacity;
   - used/free;
   - proven YTDLnisX debug/test footprint;
   - proven temporary/staging footprint;
   - any large unrelated footprint visible read-only;
   - whether the evidence points to accumulation versus structurally insufficient AVD capacity.

If shell permissions prevent measuring a subtree, record NOT_OBSERVABLE rather than escalating privileges or guessing.

## Recovery authorization after profile

The existing bounded recovery remains the only authorized mutation:
- uninstall identity-proven YTDLnisX debug/test packages;
- remove only identity-proven temporary/staging artifacts for this verification attempt;
- restart the same emulator only for package-manager readiness;
- no wipe-data;
- no AVD recreation/resize;
- no global cache trim;
- no unrelated package mutation;
- no release-package mutation.

Record the same storage measurements after recovery and calculate the actual space reclaimed.

Only after measurable recovery may the single install retry occur.

## Decision boundary after the single retry

If installation succeeds:
- continue the persisted BUG-UPDATER-03 verification flow;
- preserve before/after storage profile as infrastructure evidence.

If insufficient storage persists:
- STOP;
- do not perform a second cleanup/retry cycle;
- report whether the profile indicates:
  A. remaining test-owned reclaimable storage,
  B. unrelated occupancy not authorized for deletion,
  C. AVD /data capacity structurally too small,
  D. cause not observable.

AVD resize/recreation/compaction or deletion of unrelated data requires a separate authorization decision.

## Long-term interpretation

If repeated profiles show the same emulator naturally returns to a near-full /data state despite removing only test-owned artifacts, treat emulator capacity as a harness-maintenance issue rather than repeatedly spending semantic-review waves on ad hoc cleanup.

Any later durable harness change must be reviewed separately from production correctness and must preserve the accepted API/ABI/device-test contract.

INDEPENDENT EXECUTION: NOT EXECUTED
