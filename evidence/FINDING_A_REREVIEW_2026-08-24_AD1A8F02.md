# Finding A Re-review — checkpoint `ad1a8f026a7a05f3e1489775a74d8106dbfa510e`

Date: 2026-08-24 (Asia/Seoul)

## Purpose

This evidence records an independent full-scope re-review of Finding A after the remediation implementation completed. The semantic decisions below were established by review before this ledger record was created; this file does not introduce a new blocker classification or new defect ID.

## Reviewed production truth

- Review / pushed checkpoint HEAD: `ad1a8f026a7a05f3e1489775a74d8106dbfa510e`
- Semantic implementation commit: `243344dd4596c279518e97c82caf71a42e5672dc`
- Implementation starting checkpoint reported by the implementer: `805722d7167f59d82eb9ea1bba33e515dd18c463`
- The commits after `243344dd4596c279518e97c82caf71a42e5672dc` through `ad1a8f026a7a05f3e1489775a74d8106dbfa510e` change only `docs/codex/TASKS.md`; no Android production/test/Gradle semantics were changed by that tail.
- Finding B was not included in this remediation and was not started by the reviewed production diff.

## Governing scope

Historical Finding A blocker ownership remains the frozen A1–A12 set in `evidence/FINDING_A_REMAINING.md`. This re-review evaluated the original scope rather than only the implementation diff.

## Verdict

**Finding A: NOT CLEAN**

The remediation materially closes most of the frozen blocker set at source level, but three semantic blocker groups remain:

1. A2 — worker cleanup sibling/fault isolation still lacks guaranteed durable recovery responsibility after a second-stage cleanup/requeue failure.
2. A8 — same-pattern stale full-row History writers remain production-reachable.
3. A9/A12 — scheduled shutdown can still reclassify a committed History replacement back to `Queued`, so post-commit finalization / late-stop semantics remain unsafe.

Execution closure is also not met because required focused JVM/Android-test/instrumentation evidence is incomplete.

## A1–A12 status matrix

| ID | Re-review status | Source-level conclusion |
| --- | --- | --- |
| A1 | SOURCE-LEVEL FIXED | Low-quality cancellation vs no-candidate terminalization now has transactional/CAS winner semantics; late loser cannot overwrite the established terminal fact. |
| A2 | **NOT CLEAN** | Cleanup is sibling-isolated, but a row whose durable cleanup/requeue step throws can remain `Active`/`PostProcessing`; the exact process-local owner is then released and no generic durable recovery carrier is created. |
| A3 | SOURCE-LEVEL FIXED | Download notification cancellation no longer deletes/mutates Terminal rows by colliding numeric ID. |
| A4 | SOURCE-LEVEL FIXED | Destructive replacement filesystem work is protected by the per-Download exact side-effect lease and ownership revalidation. |
| A5 | SOURCE-LEVEL FIXED | Reviewed worker/cancel/scheduled-shutdown paths no longer hold the global execution lock while waiting for the per-Download side-effect lease; multi-ID leases are deterministically ordered. |
| A6 | SOURCE-LEVEL FIXED | Low-quality cancellation has same-process convergence retry plus restart reconciliation rather than one-shot phase-2 cleanup only. |
| A7 | SOURCE-LEVEL FIXED | Durable terminal-convergence debt prevents retry/reconfigure/claim from erasing an established terminal fact. |
| A8 | **NOT CLEAN** | `HistoryDao.update()` rereads the current row under `HistoryReferenceMutationCoordinator`, but then writes a stale caller snapshot while preserving only `keywords`, `downloadId`, `downloadPath`, and `type`. Bulk artist/keyword editors still call this generic full-row writer, so replacement-owned metadata such as title/thumb/duration/format/filesize can be rolled back by a stale snapshot. |
| A9 | **NOT CLEAN** | Committed replacement protection exists in many repository/UI paths, but `CancelScheduledDownloadWorker` directly calls `requeueActiveDownload()` for an execution-token row. That DAO CAS does not reject `history.downloadId == download.id`, so a semantically committed replacement can be changed from `Active/PostProcessing` to `Queued` before final deletion. |
| A10 | SOURCE-LEVEL FIXED | Reviewed History-reference mutation paths use coordinator-before-Room ordering or avoid waiting on the process mutex while holding the inverse Room order. |
| A11 | SOURCE-LEVEL FIXED | Low-quality coordinator terminal/revocation paths revalidate linked executions under the shared ownership protocol before terminal authority is applied. |
| A12 | **NOT CLEAN** | Same scheduled-shutdown branch as A9 remains a late-stop path that can reclassify the post-commit carrier into a pre-commit queue state. |

## Residual blocker details

### A2 — cleanup failure can release the only live owner without creating a new carrier

`DownloadWorker.cleanupStoppedWorker()` now performs process cleanup and durable row cleanup per sibling, preserving the first failure while continuing other rows. That is a substantive improvement.

However, if the second-stage owned cleanup/requeue/convergence block throws, the row is added to `recoveryEligibleIds`. The later owner-release section removes the worker's exact execution token and releases `DownloadWorkerExecutionOwners` for any ID in `releasedIds || recoveryEligibleIds`.

For committed History replacements, the function separately detects committed finalization debt and requests a fresh Download worker. For an ordinary `Active/PostProcessing` row whose requeue itself failed, there is no equivalent durable debt/carrier creation in this function. The log message describes the row as released for startup recovery, but startup recovery runs only when a later Download worker is actually created. The exceptional path rethrows the original failure rather than returning `Result.retry()`.

Therefore the production-reachable terminal state remains:

`E1 exceptional exit -> durable cleanup/requeue throws -> row remains Active/PostProcessing -> E1 process-local owner released -> no newly established recovery carrier`.

This does not satisfy A2's required invariant that owner release occur only after a durable non-running/superseded/removed state or explicit durable recovery responsibility exists.

### A8 — locking without field ownership still permits stale metadata rollback

`HistoryDao.update()` now enters `HistoryReferenceMutationCoordinator.withLockBlocking` and rereads the row. It then calls `updateRaw(item.copy(...))`, replacing only four fields with current values: `keywords`, `downloadId`, `downloadPath`, and `type`.

Production bulk History editing still constructs `updated = item.copy(...)` from the UI's earlier `HistoryItem` snapshot and passes it through `HistoryViewModel.update()` / `HistoryRepository.update()` / `HistoryDao.update()`.

A replacement can therefore commit newer metadata while the bulk editor is open, after which the bulk writer obtains the coordinator and writes the stale snapshot back for every field it did not explicitly preserve. The coordinator prevents simultaneous mutation but does not establish field ownership or refresh all non-owned fields.

Required closure remains: a writer changing one semantic field must reread inside the canonical boundary and update only the fields it owns, rather than performing a compatibility full-row update from a stale snapshot.

### A9/A12 — scheduled shutdown bypasses committed-replacement protection

`CancelScheduledDownloadWorker` now fixes the previous AB/BA lock ordering: it snapshots under the short global lock, releases it, acquires the per-Download side-effect lease, and briefly revalidates under the global lock.

The semantic branch is still incomplete. For an exact execution-token row without a refusal barrier, `cancelAndRequeue()` executes:

`dao.requeueActiveDownload(latest.id, latest.executionId)`.

`DownloadDao.requeueActiveDownload()` requires the exact execution token and a running status, and rejects target-deleted/refusal-barrier cases, but it does not reject a History replacement whose semantic commit already occurred (`history.downloadId == downloads.id`).

Thus this ordering remains possible:

`History semantic replacement commit -> Download row still Active/PostProcessing pending final deletion -> daily scheduled shutdown -> exact lease/revalidation succeeds -> native process stop -> requeueActiveDownload -> status becomes Queued and executionId is cleared`.

That violates both A9 (committed replacement is finalization debt, never a fresh pre-commit attempt) and A12 (late stop must not reclassify the primary result after semantic commit).

The legacy blank-execution branch uses `setStatusMultipleFromStatus()`, whose runnable predicate does contain the committed-History guard; the residual specifically applies to the modern execution-token branch that calls `requeueActiveDownload()` directly.

## Verification evidence

Reported implementation verification:

- `git diff --check`: **PASS**
- `:app:kspDebugKotlin`: **PASS**
- final `:app:compileDebugKotlin -x lint`: **ATTEMPTED, NOT COMPLETED**
- focused JVM tests: **ATTEMPTED, NOT COMPLETED**
- Android-test compilation: **ATTEMPTED, NOT COMPLETED**
- instrumentation tests: **NOT EXECUTED**

The incomplete compile/test runs hit the known Kotlin compiler hotspot around `DownloadWorker.kt`. `ATTEMPTED, NOT COMPLETED` and `NOT EXECUTED` are not PASS.

Source review confirmed focused test additions for several repaired invariants, including no-candidate/cancellation winner ordering and ownership/recovery/sibling behavior, but the residual A2, A8, and A9/A12 paths above do not have executed production-path evidence proving closure.

## Re-review self-check before ledger recording

Before this file was written, the residual conclusions were independently rechecked against the exact `ad1a8f026a7a05f3e1489775a74d8106dbfa510e` production files rather than copied from the first review pass:

- `DownloadWorker.kt` — `cleanupStoppedWorker()` recovery/owner-release behavior;
- `HistoryDao.kt` and `HistoryViewModel.kt` plus production History bulk-edit call sites — stale full-row field ownership;
- `CancelScheduledDownloadWorker.kt` and `DownloadDao.kt` — modern exact-token scheduled-shutdown requeue path.

The recheck did not overturn any of the three residual blocker conclusions.

## Registry effect

This re-review does **not** add a new defect ID and does **not** change the effective active-defect count. A2, A8, A9, and A12 are already owned by the frozen Finding A blocker registry. This evidence only records their current remediation status at the new checkpoint.
