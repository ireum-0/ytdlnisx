# BUG-DOWNLOAD-DELETE-SNAPSHOT-01 — current-basis carry-forward revalidation

Date: 2026-09-11

## Exact basis
- CLEAN basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior checkpoint: `367a3ec53b584288a8e384ee0a90a16f89d15510` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Luna `BUG-KEYWORD-01` review-fix #2 is in progress; no implementation commit/diff newer than `5da8bc3354f6cbafd23d08dbc602530a983be6af` was inspected.

## Verdict
**NOT_CLEAN / existing P2 `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Intervening-change relation
The cumulative `aa1616a2... -> 9c5191c3...` F3 changes do not modify `DownloadRepository`, `DownloadDao`, or the cleanup deletion producer. No intervening source-authority change repairs the status-snapshot deletion contract.

## Exact current source
At `9c5191c3...`, status-scoped deletion still routes through the same shared helper:

- `deleteCancelled() -> deleteKnownUserRemoval(getCancelledDownloads())`
- `deleteScheduled() -> deleteKnownUserRemoval(getScheduledDownloads())`
- `deleteErrored() -> deleteKnownUserRemoval(getErroredDownloads())`
- `deleteQueued() -> deleteKnownUserRemoval(getQueuedDownloads())`

`deleteKnownUserRemoval(items)` still immediately reduces the earlier `DownloadItem` snapshots to bare numeric IDs:

`val ids = items.map(DownloadItem::id).distinct()`

The destructive transaction therefore does not carry the original status/generation predicate to the final delete boundary. The previously established race remains structurally possible:

1. cleanup/status caller reads old Error/Cancelled/Queued/Scheduled generation D;
2. another production path legitimately changes D to a newer current retry/running/reclassified generation using stronger CAS semantics;
3. the old deletion path later retains only D's numeric ID;
4. linked state and the current Download row can be deleted under stale snapshot authority.

Stronger cache ownership and retry CAS behavior elsewhere do not repair the already-destructive Room authority boundary.

## Correction boundary carried forward
- snapshot-derived/status-scoped deletion must retain a typed destructive precondition through the same transaction that mutates linked state and deletes the row;
- final mutation must revalidate expected status and the semantic generation fields/tokens necessary to prove the row is still the deletion-authorized generation;
- if the precondition changed, skip all stale linked-child/barrier/Download mutations;
- explicit UI semantics meaning “delete whatever row currently has this ID” must remain separate from automatic/status-scoped deletion;
- regressions should interleave old Error snapshot -> exact retry/reclassification -> stale delete boundary and prove the newer row/linkage survives, plus analogous reachable status races.

## Root reconciliation
This is the existing canonical P2 root, counted once. No new root or severity change is established.

INDEPENDENT EXECUTION: NOT EXECUTED
