# BUG-DOWNLOAD-DELETE-SNAPSHOT-01 current-basis revalidation — 2026-09-12

## Scope

- Independently CLEAN review basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Root: existing P2 `BUG-DOWNLOAD-DELETE-SNAPSHOT-01`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior isolated candidate: `cf070671b22949471b6ae070bdfb92ac6d1fbfaa`
- Prior candidate disposition: CLEAN FOR ROOT / not integrated
- Active canonical implementation wave: Task 004 `BUG-CACHE-01`; its in-progress diff was not inspected.

## Verdict

**OPEN / NOT_CLEAN.**

- Count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 33**.
- CLEAN basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.

This is the already-counted P2 root, not a new finding.

## Exact current-source evidence

At exact `93d01d2a...`, `DownloadRepository` still owns status-scoped deletion through stale pre-transaction snapshots.

`deleteCancelled()`, `deleteScheduled()`, `deleteErrored()`, and `deleteQueued()` first query rows by status, then pass those `DownloadItem` snapshots to `deleteKnownUserRemoval(...)`.

`deleteKnownUserRemoval(...)` does not carry the full snapshot as a final precondition. It immediately reduces the candidates to distinct numeric IDs before entering its Room transaction. Inside the transaction it:

1. terminalizes linked low-quality-redownload children for those IDs;
2. deletes History replacement barriers for those IDs;
3. calls `downloadDao.deleteAllWithIDs(ids)`.

No current row is reloaded and compared to the status-query snapshot before linked-state mutation or row deletion.

`deleteSaved()` and `deleteProcessing()` similarly capture status-selected rows before their transactions and use their IDs to delete barriers plus status-scoped DAO rows without a full current-generation precondition tied to the originally selected row.

`deleteAllWithIDs(ids)` currently queries current rows first and then routes those snapshots through the same `deleteKnownUserRemoval(...)` helper. That explicit-ID path has a different semantic contract from status-owned deletion; a future correction must preserve that distinction rather than accidentally turning an explicit current-ID request into a stale-status contract.

## Concrete failure boundary

A reachable status-owned race remains:

1. cleanup queries Download D while it is `Error`/`Cancelled`/`Queued`/`Scheduled` and captures snapshot S;
2. before `deleteKnownUserRemoval(...)` reaches its transaction, another valid production path changes D into a newer/current generation or otherwise materially changes its execution/operation/retry/status state;
3. cleanup enters its transaction carrying only D's numeric ID;
4. linked low-quality-redownload state and History replacement barrier state for that ID may be terminalized/deleted;
5. `deleteAllWithIDs(ids)` deletes the current row identified by the same numeric ID;
6. destructive authority from stale snapshot S has therefore crossed into the newer/current generation.

This violates checklist invariants that discovery is not mutation authority and that destructive identity/ownership must be revalidated at the actual final mutation boundary.

## Relation to the isolated candidate

The isolated candidate `cf070671...` remains useful reviewed evidence. It introduced a typed full-row precondition and, inside the same destructive Room transaction, reloaded the current row and required exact equality with the captured candidate before touching linked state or deleting the row. It also separated explicit current-ID deletion semantics from status-snapshot deletion.

That candidate was independently CLEAN FOR ROOT but was never integrated into canonical history. Current `93d01d2a...` therefore still contains the original authority gap.

No new blocker ID is warranted.

## Stable future correction boundary

A canonical replay/reimplementation should preserve the reviewed candidate's semantic boundary while adapting it to then-current source:

- status-owned deletion must carry the full selected row/generation as a revocable precondition;
- inside the same Room transaction that mutates linked state and deletes the Download, reload the current row and require exact unchanged identity/state sufficient to prove it is still the selected generation;
- on mismatch or row absence, skip all destructive mutations for that candidate;
- only rows that pass final revalidation may donate IDs to linked-child/barrier deletion and later cache cleanup;
- preserve mixed-batch sibling isolation so a stale candidate does not block valid siblings;
- preserve explicit current-ID deletion as a separate intentional contract;
- cache deletion after DB commit must retain its own exact current-generation/ownership guard and may not rely on numeric ID alone.

Focused regressions should cover stale Error -> Queued/new execution, Cancelled -> Active/new execution, stale Queued/Scheduled claims, mixed valid+stale batch, row deletion before boundary, cancellation before destructive boundary, matching snapshot success, and explicit current-ID deletion semantics.

No Room schema migration is indicated by this root.

INDEPENDENT EXECUTION: NOT EXECUTED