# BUG-DOWNLOAD-01 direct-output-hook child blocked by BUG-TOOLING-01 evidence-path infrastructure

checkpoint_kind: COMPLETED_IMPLEMENTATION_INFRASTRUCTURE_STOP_RECONCILIATION
review_parent_sha: 2168ea0a6c88bde8021a1863d60f5c766b244305
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: ff3a111a784fc50c43aed0aafc0f62776e7e0bd4
reported_local_candidate_parent: a27074d6ea8b56be01a9cfb3159a73c018d1b5d0
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Completion classification

BLOCKED_BY_TOOLING_INFRASTRUCTURE_BEFORE_CONNECTED_EXECUTION

The implementation agent reports that the authorized test-only child was
created exactly on top of a27074d6 and changed only
DownloadWorkerCleanupProductionWiringTest.kt in
realWorkerKeepsCommittedHistoryAuthoritativeAfterFinalizationFailure so the
synthetic output is created inside the production-supplied output directory.

The exact candidate is unpublished. GitHub cannot resolve
ff3a111a784fc50c43aed0aafc0f62776e7e0bd4, so its local source/tree and exact
diff are agent evidence only until publication.

## Device and verifier result

The same authorized AVD was cold-started without wipe:
- AVD: Medium_Phone_API_36.1
- serial: emulator-5554
- boot completed
- PackageManager responded

The connected verification wrapper then stopped before a confirmed ADB probe or
Gradle start while attempting to create:
device-health/.../adb-devices-...stdout.log

Reported exception class:
System.Management.Automation.MethodInvocationException

Reported verification state:
- BLOCKED_BY_TOOLING_INFRASTRUCTURE
- gradleStarted=false
- executed tests=0
- no confirmed ADB probe
- no focused PASS
- compile gate has no PASS result
- detached diff gate has no PASS result
- union not started
- Complete-Wave not run
- publication not attempted

Therefore this result contains no new BUG-DOWNLOAD-01 semantic evidence.

## Tooling root reconciliation

This failure shape matches the already-canonical BUG-TOOLING-01 evidence-path
residual recorded at:
review-runs/checkpoints/2026-09-28__77a3a177__tooling01-evidence-path-residual.md

That prior root established:
- per-gate device-health/log path materialization can fail before adb.exe
  launches;
- such a stop is tooling/infrastructure bootstrap failure, not observed device
  health;
- the correction boundary is deterministic bounded filesystem artifact tokens
  while preserving full semantic gate IDs in evidence.

A later local lineage was reported to pass 17/17 tooling acceptance and a long
connected-gate acceptance after a bounded-path correction, but that correction
was never published and is not independently reviewable on the current
ff3a111a lineage through GitHub.

Therefore:
- no new tooling finding ID is created;
- BUG-TOOLING-01 remains OPEN P2 / SAME-ROOT;
- tooling P2 remains 1;
- BUG-DOWNLOAD-01 remains OPEN P2;
- production P2 remains 13.

## Remaining ambiguity

GitHub cannot determine whether the exact ff3a111a local lineage:
A. does not contain the previously reported bounded evidence-path correction;
B. contains it but still has a same-root residual on a path not covered by that
   correction;
C. is failing for a materially different tooling path cause.

Do not authorize another tooling edit until this lineage/source fact is
established.

## Next action

Read-only exact-local tooling provenance/source trace on ff3a111a.

Required classification:
A. PRIOR_BOUNDED_PATH_CORRECTION_ABSENT_FROM_LINEAGE
B. PRIOR_BOUNDED_PATH_CORRECTION_PRESENT_SAME_ROOT_RESIDUAL
C. DIFFERENT_TOOLING_CAUSE
D. INSUFFICIENT_EVIDENCE

The trace must:
- verify exact HEAD/parent/tree and clean behavior-relevant worktree state;
- inspect ancestry around the prior 77a3a177 -> 29034eae tooling correction
  lineage without rewriting anything;
- compare exact local tooling source against the persisted correction contract;
- identify the exact path construction that threw;
- state whether the failing path uses the bounded artifact token or the raw/full
  gate ID;
- identify exact changed tooling files/commit if the prior correction is
  present;
- make no edits, no commit, no rerun, no union, no publication.

## Canonical state

BUG-DOWNLOAD-01 remains OPEN P2 / FOCUSED_NOT_EXECUTED_TOOLING_BLOCKED.
BUG-TOOLING-01 remains OPEN P2 / SAME_ROOT_EVIDENCE_PATH_LINEAGE_UNRESOLVED.

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.

INDEPENDENT EXECUTION: NOT EXECUTED
