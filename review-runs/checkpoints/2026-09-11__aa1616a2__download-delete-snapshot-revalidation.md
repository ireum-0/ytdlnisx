# BUG-DOWNLOAD-DELETE-SNAPSHOT-01 — stale Download deletion authority revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while the `BUG-OBSERVE-01` review-fix prompt is issued but has not been explicitly started.
- No post-`aa1616a2...` implementation commit/diff was used as evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Finding: `BUG-DOWNLOAD-DELETE-SNAPSHOT-01`

The exact CLEAN basis still lets a status/batch deletion caller convert an earlier `DownloadItem` snapshot into bare numeric IDs and then delete the current rows by ID without atomically revalidating that those rows still have the expected status and semantic generation. A newer retry/current generation can therefore be deleted by an older Error/Cancelled/Queued/Scheduled snapshot.

## Production cleanup still reads a status snapshot before deletion

Exact source: `CleanUpLeftoverDownloads.kt@aa1616a2...`.

The cleanup worker still performs:

- `downloadRepo.deleteCancelled()`;
- `downloadRepo.deleteErrored()`.

Exact `DownloadRepository.kt@aa1616a2...` shows these status-scoped methods still call the same shared primitive:

- `deleteCancelled() = deleteKnownUserRemoval(getCancelledDownloads())`;
- `deleteErrored() = deleteKnownUserRemoval(getErroredDownloads())`;
- `deleteScheduled() = deleteKnownUserRemoval(getScheduledDownloads())`;
- `deleteQueued() = deleteKnownUserRemoval(getQueuedDownloads())`.

The destructive primitive receives full earlier snapshots but immediately reduces them to:

`val ids = items.map(DownloadItem::id).distinct()`

It then enters a Room transaction using only those IDs for the destructive authority set.

## Destructive transaction does not revalidate current Download generation

Inside `deleteKnownUserRemoval()` the current implementation:

1. terminalizes linked low-quality children for the bare ID set;
2. deletes History-replacement barriers for the bare ID set;
3. calls `downloadDao.deleteAllWithIDs(ids)`.

Exact `DownloadDao.kt@aa1616a2...` defines that final deletion as:

`DELETE FROM downloads WHERE id in (:list)`

There is no expected-status predicate and no `executionId`, `operationId`, `retryAttempt`, issue/revision, or equivalent semantic-generation predicate at the destructive boundary.

The transaction provides atomicity among the linked mutations and row delete, but it does not establish that the original status snapshot is still current.

## Exact retry path proves the stale-snapshot race is concrete

The current retry/reconfiguration path in `DownloadViewModel.kt@aa1616a2...` already recognizes the opposite requirement: when transitioning an Error row back toward Processing/Queued, it captures an Error snapshot and uses `DownloadDao.updateForQueueIfSnapshot(...)` with expected values including status, execution ID, operation ID, retry attempt, and issue identity.

Therefore the following production interleaving remains possible:

1. cleanup reads Download D in `Error` through `getErroredDownloads()`;
2. retry/reconfiguration reads the same Error generation and successfully wins its exact `updateForQueueIfSnapshot(...)` CAS, producing a newer current Queued/Processing retry generation;
3. cleanup later enters `deleteKnownUserRemoval(oldErrorSnapshot)`;
4. the helper retains only D's numeric ID;
5. linked child/barrier state is terminalized/deleted for that ID and `DELETE FROM downloads WHERE id = D` removes the newer retry row despite Error no longer being current deletion authority.

The same structural problem applies to other callers that pass an earlier status/selection snapshot into the shared helper: numeric identity alone survives while the status/generation precondition is discarded.

## Cache cleanup does not repair Room authority

Post-transaction cache cleanup has stronger ownership checks in the current architecture, but that cannot restore the already-deleted current Room row or linked state. The correctness defect is the destructive database primitive itself, not merely physical cache deletion.

## Required correction boundary carried forward

1. Status-scoped and snapshot-derived deletion must carry a typed destructive precondition, not only numeric IDs.
2. Inside the same transaction that terminalizes linked children/barriers and deletes the Download, re-read/compare the current row and require the expected status plus the semantic generation fields needed to prove it is still the same deletion-authorized generation.
3. At minimum review `executionId`, `operationId`, retry attempt/strategy or another immutable retry generation, and relevant issue/revision fields for the affected status transitions; do not copy a field list mechanically if exact architecture supplies a stronger generation token.
4. A row whose precondition changed must be skipped entirely: no Download delete, no linked-child terminalization, no barrier deletion under the stale snapshot.
5. UI/batch deletion semantics that intentionally mean “delete whatever row currently has this ID” must remain explicit and separate from automatic/status-scoped deletion; do not silently use old selection snapshots as authority over a newer running/retry generation.
6. Regression coverage must interleave `Error snapshot -> exact retry CAS -> cleanup delete boundary` and prove the newer row/linkage survives. Also cover stale Cancelled/Queued/Scheduled or batch snapshots where their production callers can race with reclassification.

## Root/count and basis reconciliation

- `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` was already counted as one P2 root.
- This is exact-current reconfirmation, not a new root.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- The contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- Existing stronger retry CAS behavior is preserved evidence, not a separate finding.
- The active `BUG-OBSERVE-01` review-fix prompt/start state is unchanged.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
