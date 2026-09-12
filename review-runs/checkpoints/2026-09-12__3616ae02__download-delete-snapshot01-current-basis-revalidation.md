# BUG-DOWNLOAD-DELETE-SNAPSHOT-01 — exact CLEAN-basis revalidation

Date: 2026-09-12

## Exact review state

- Independently CLEAN review basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Prior current-basis checkpoint: `8fbfb184f874272c990d14d48a96e83956047139` at `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- Prior isolated candidate: `cf070671b22949471b6ae070bdfb92ac6d1fbfaa`, previously reviewed CLEAN FOR ROOT but never integrated into canonical implementation history.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Separate exact verification remains active for completed remote `checkpoint/pre-baseline-review@8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`; no post-`3616ae02...` implementation state is used as exploratory evidence here.

## Verdict

**NOT_CLEAN — existing P2 `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` remains OPEN / CONFIRMED at exact basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

Exact compare `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c -> 3616ae02e56995e795cc52f3074d8c3d1cd2e330` is three commits ahead and changes cache/download-worker/terminal/cache-maintenance code and tests, but does **not** modify `DownloadRepository.kt`.

Exact `3616ae02...` `DownloadRepository.kt` was nevertheless re-read directly.

## Exact current production evidence

### 1. Status-owned deletion still collapses discovery snapshots to numeric IDs

`deleteCancelled()` still obtains status-selected `DownloadItem` rows and passes them to `deleteKnownUserRemoval(...)`. The same helper remains the status-owned destructive boundary for the affected bulk cleanup paths.

Inside `deleteKnownUserRemoval(items)` the exact selected row snapshots are immediately reduced to:

`val ids = items.map(DownloadItem::id).distinct()`.

The transaction then uses those IDs to mutate linked low-quality-redownload state, delete History replacement barriers, and call `downloadDao.deleteAllWithIDs(ids)`.

No exact current row is reloaded and compared against the originally selected `DownloadItem` snapshot before those linked-state and row-deletion mutations.

Therefore the semantic authority still widens from:

`status-selected exact row snapshot S`

into:

`numeric Download id`

before the final destructive boundary.

### 2. Concrete stale-snapshot race remains reachable

A reachable sequence remains:

1. status cleanup selects Download D while D is `Error`, `Cancelled`, `Queued`, `Scheduled`, or another owned cleanup status and captures snapshot S;
2. before `deleteKnownUserRemoval(...)` reaches its Room transaction, another valid production path changes D's current status/execution/operation/retry generation;
3. cleanup enters the transaction carrying only D's numeric ID;
4. linked low-quality-redownload state and History replacement barrier state for that ID can be terminalized/deleted;
5. `downloadDao.deleteAllWithIDs(ids)` deletes the current row for that same numeric ID;
6. destructive authority from stale snapshot S has therefore crossed into a newer/current Download generation.

This remains a direct violation of checklist rules that discovery is not mutation authority and that destructive identity/granularity must be revalidated at the actual mutation point.

### 3. Later cache remediation improved a downstream sub-boundary, not the root

The `93d01d2a... -> 3616ae02...` range did strengthen cache ownership. At exact `3616ae02...`, `DownloadRepository.deleteCache(items)` no longer treats numeric membership as cache ownership; it calls `DownloadCacheOwnership.deleteIfOwned(cacheDir, item)` and leaves legacy/mismatched roots intact.

That is a real preserved improvement and narrows the downstream cache-deletion risk described in the earlier correction boundary.

It does **not** close this root because the primary Room transaction can still delete the newer/current Download row and its linked durable state using stale status-discovery authority before cache cleanup is reached.

The root therefore remains OPEN even though cache cleanup itself now has a stronger ownership predicate.

## Relation to explicit current-ID deletion

The prior review distinguished status-owned deletion from explicit current-ID deletion. That distinction remains important.

A future correction must not accidentally require an old status snapshot for an explicit user request whose semantic contract is intentionally "remove the currently identified Download id". The stale-status root is specifically about a prior status/discovery decision donating destructive authority after the row has materially changed.

## Stable remediation boundary

The previously reviewed correction boundary remains valid, with the cache sub-boundary updated to acknowledge the now-stronger ownership helper:

1. status-owned deletion must carry the full selected row/generation as a revocable precondition;
2. inside the same Room transaction that mutates linked state and deletes the Download, reload the current row and require exact unchanged identity/state sufficient to prove it is still the selected generation;
3. on mismatch or row absence, skip all destructive linked-state and row mutations for that candidate;
4. only candidates that pass final in-transaction revalidation may donate IDs to low-quality child/barrier cleanup and deletion;
5. preserve mixed-batch sibling isolation so one stale candidate does not block unrelated valid candidates;
6. preserve explicit current-ID deletion as a separate intentional contract;
7. preserve the current `DownloadCacheOwnership.deleteIfOwned(...)` protection after DB commit and do not regress cache cleanup back to numeric-ID authority;
8. preserve cancellation and existing execution/operation ownership semantics around concurrent claim/requeue paths.

Focused closure coverage should include stale Error -> Queued/new execution, Cancelled -> Active/new execution, stale Queued/Scheduled claim, mixed valid+stale batch, row disappearance before the destructive boundary, matching-snapshot success, explicit current-ID deletion, and preservation of the newer cache ownership guard.

No Room schema migration is indicated by this root.

INDEPENDENT EXECUTION: NOT EXECUTED