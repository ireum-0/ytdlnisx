# BUG-DUPLICATE-ADMISSION-01 current-basis revalidation

Date: 2026-09-12

## Exact review basis

- Current contiguous independently CLEAN basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Prior revalidation basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Original current-basis checkpoint: `8eccaec100562ec5de8a2af4e3c032e3c114b022`

## Verdict

**OPEN / NOT_CLEAN for this root.**

`BUG-DUPLICATE-ADMISSION-01` remains a distinct P2 semantic root at exact CLEAN basis `3616ae02...`.

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Overall project state remains `NOT_CLEAN`.

## Intervening-range verification

The cumulative cache range `93d01d2a... -> 3616ae02...` modifies cache/Terminal/Download-execution authority files, but it does **not** modify the producer-side duplicate-admission consumers `ObserveSourceWorker.kt`, `DownloadViewModel.kt`, or `DownloadRepository.kt`.

Exact final-source was nevertheless re-read rather than relying only on diff absence.

## Governing invariant

When duplicate prevention is enabled, semantic duplicate observation and publication of the corresponding runnable Download must share one authoritative admission boundary. Two concurrent producers that resolve to the same corrected F19 duplicate identity must not both pass stale pre-insert snapshots and publish separate runnable rows.

This remains separate from closed F19 `BUG-DUPLICATE-01`:

- F19 owns **what compares equal**;
- `BUG-DUPLICATE-ADMISSION-01` owns **atomic publication after equality is known**.

## Exact final-source evidence at `3616ae02...`

### Observe check -> insert gap remains

`ObserveSourceWorker` still:

1. reads `downloadRepo.getActiveAndQueuedDownloads()` into a local mutable `activeAndQueuedDownloads` snapshot;
2. evaluates duplicate prevention against that snapshot, including `DownloadConfigurationDuplicatePolicy.findMatch(...)` for config mode;
3. only later calls `downloadRepo.insert(it)` for a new Download;
4. adds the inserted item only to that worker's local `activeAndQueuedDownloads` list.

There is still no shared duplicate-admission mutex, Room compare-and-insert transaction, semantic reservation row, unique semantic key, or final same-boundary duplicate revalidation spanning the comparator and durable insert.

Two independent Observe producers can therefore both observe no equivalent row, both pass the corrected comparator, and both publish different runnable Download primary keys.

### Repository boundary remains unconditional

`DownloadRepository.insert(item)` at exact `3616ae02...` remains a thin wrapper around `downloadDao.insert(item)`.

It does not consume duplicate policy, semantic identity, an expected snapshot/generation, or a reservation token. Therefore the durable insertion boundary cannot reject a stale negative duplicate decision made by a caller.

### Manual producer remains outside one common authority

`DownloadViewModel` still invokes `DownloadConfigurationDuplicatePolicy.findMatch(...)` against an observed active/queued set before later queue publication. The corrected comparator is shared, but manual and Observe producers do not publish through one atomic duplicate-admission authority.

## Concrete impact

With duplicate prevention enabled, overlapping producers requesting one canonical media/configuration can both publish runnable Downloads. Those rows can later be claimed as independent executions, defeating duplicate prevention and allowing duplicate native/output/History work.

A correct comparator against stale snapshots is not atomic admission.

## Root/count reconciliation

- F19 `BUG-DUPLICATE-01`: remains CLOSED; this revalidation does not reopen it.
- `BUG-DUPLICATE-ADMISSION-01`: remains the already-counted P2 check-to-insert race; no new root is added.
- `BUG-OBSERVE-HANDOFF-01`: separate WorkManager/successor-ownership root.
- Download execution claim/cache ownership roots: separate post-insert execution authority and remain unaffected by this classification.

## Stable implementation boundary

The repair boundary is now implementation-ready:

- establish one authoritative duplicate-admission primitive used by every normal producer that honors duplicate prevention;
- consume the corrected F19 semantic media/configuration relation at that boundary;
- make the final duplicate decision inseparable from durable reservation/publication;
- concurrent equivalent producers must yield at most one runnable Download;
- the losing producer must receive an explicit duplicate/refused result rather than silently inserting another row;
- preserve explicit duplicate-policy bypass, intentional redownload/replacement, History replacement and other privileged flows that are not ordinary duplicate-prevention admission;
- do not replace semantic identity with raw URL, title, coarse status or numeric row ID;
- if a durable reservation is introduced, process death must leave it discoverable/recoverable and must not create a permanent false reservation;
- preserve F19 canonical-equivalent YouTube identity and meaningful request-configuration distinctions;
- add deterministic concurrency coverage spanning at least two independent producers and the real durable insertion boundary.

INDEPENDENT EXECUTION: NOT EXECUTED
