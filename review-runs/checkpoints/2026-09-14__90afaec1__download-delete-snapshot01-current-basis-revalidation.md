# BUG-DOWNLOAD-DELETE-SNAPSHOT-01 — exact CLEAN-basis revalidation

Date: 2026-09-14

## Exact review state

- Independently CLEAN review basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Prior exact-basis checkpoint: `1c052d0ccb67d7f7039b2c0ff4bf66086ffcc877` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Active implementation wave is frozen from inspection; `checkpoint/pre-baseline-review` was independently confirmed still at its recorded start SHA `973424909fd97de758b62f639967c8bae7c0bad7`. No in-progress implementation diff is used here.
- A concurrent append-only final frozen-run checkpoint `48767aa287f58fdb4592511edcc190e3a2330ff5` changes no production source and does not supersede prior finding ownership/counts.

## Verdict

**NOT_CLEAN — existing P2 `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` remains OPEN / CONFIRMED at exact canonical CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 0 / P2 23**.
- CLEAN Review Basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

Exact compare `3616ae02e56995e795cc52f3074d8c3d1cd2e330 -> 90afaec157607669ea32fa41877e7f0efcdcca86` is 16 commits ahead. The range does modify `DownloadRepository.kt` (+104 additions, no deletions), principally for later duplicate-admission/backup-related work, so the exact final-basis destructive path was re-read rather than relying on diff absence.

The current deletion path still has the same authority widening described by the prior checkpoint.

## Exact current production evidence

### 1. Status-owned deletion still begins from a status-selected row snapshot

At exact `90afaec1...`:

- `deleteCancelled()` calls `deleteKnownUserRemoval(getCancelledDownloads())`;
- `deleteScheduled()` calls `deleteKnownUserRemoval(getScheduledDownloads())`;
- `deleteErrored()` calls `deleteKnownUserRemoval(getErroredDownloads())`;
- `deleteQueued()` calls `deleteKnownUserRemoval(getQueuedDownloads())`.

Those getters materialize `DownloadItem` rows selected by the corresponding status-owned DAO queries.

### 2. The exact snapshots are still collapsed to numeric IDs before the destructive transaction

`deleteKnownUserRemoval(items)` still performs:

`val ids = items.map(DownloadItem::id).distinct()`

before entering the Room transaction.

Inside that transaction it uses those IDs to:

1. `terminalizeLinkedChildren(downloadIds = ids, reason = REASON_USER_REMOVED, ...)`;
2. `historyReplacementBarrierDao.deleteForDownloadIds(ids)`;
3. `downloadDao.deleteAllWithIDs(ids)`.

There is still no in-transaction reload-and-match against the originally selected `DownloadItem` snapshot before linked-state/barrier deletion and current-row deletion.

Therefore authority still widens from:

`status-selected exact row/generation snapshot`

into:

`numeric Download id`

before the final destructive boundary.

### 3. Concrete stale-snapshot sequence remains reachable

1. A status-owned cleanup selects Download D in `Error`, `Cancelled`, `Queued`, `Scheduled`, or another cleanup-owned state and captures snapshot S.
2. Before `deleteKnownUserRemoval(...)` reaches its transaction, another valid production path changes D's current state/execution/operation/retry generation.
3. Cleanup enters the transaction with only D's numeric ID as authority.
4. Linked low-quality-redownload state may be terminalized and the History-replacement barrier may be deleted for that ID.
5. `deleteAllWithIDs(ids)` may delete the newer/current Download row.
6. Destructive authority from stale discovery snapshot S has therefore crossed into a generation/state that S did not authorize.

This remains a direct violation of v6 core invariants that discovery is not mutation authority and that destructive identity/granularity must be revalidated at the actual mutation boundary.

### 4. Current cache ownership hardening remains preserved but does not close the root

After the Room transaction, `deleteKnownUserRemoval(...)` still calls `deleteCache(items)`. Current cache deletion uses `DownloadCacheOwnership.deleteIfOwned(...)`, which is stronger than numeric-ID-only cache deletion and remains a preserved narrowing.

That does not repair the earlier Room mutation: the newer/current Download row and its linked durable state can already have been destroyed before cache cleanup is reached.

### 5. Explicit current-ID deletion remains a distinct contract

`delete(id)` / explicit current-ID removal is not reclassified by this finding. The root is the status/discovery-owned path whose earlier snapshot silently donates destructive authority after a material row-generation/state change.

A remediation must preserve the intentionally different contract of an explicit request to remove the currently identified Download ID.

## Root/count reconciliation

- This is the same existing P2 `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` root.
- Later duplicate-admission and backup changes do not close or split it.
- The stronger cache ownership helper remains preserved and is not a separate blocker here.
- Canonical count delta remains `0`.

## Stable correction boundary

A future correction still needs to:

1. carry the full status-selected row/generation as a revocable precondition;
2. inside the same Room transaction that mutates linked state and deletes the Download, reload the current row and require sufficient exact unchanged identity/state to prove it is still the selected generation;
3. skip all linked/barrier/row destructive mutation for a stale or missing candidate;
4. preserve mixed-batch sibling isolation;
5. preserve explicit current-ID deletion as a separate intentional contract;
6. preserve `DownloadCacheOwnership.deleteIfOwned(...)` after DB commit;
7. preserve cancellation, execution ownership, operation identity, retry-generation and low-quality-redownload semantics around concurrent claim/requeue paths.

Focused closure coverage should force stale Error -> Queued/new execution, Cancelled -> Active/new execution, stale Queued/Scheduled claim, mixed valid+stale batch, row disappearance, matching-snapshot success, explicit current-ID deletion, and preservation of cache ownership protection.

No Room schema migration is indicated by this root.

INDEPENDENT EXECUTION: NOT EXECUTED