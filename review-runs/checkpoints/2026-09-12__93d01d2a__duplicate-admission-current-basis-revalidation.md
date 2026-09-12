# BUG-DUPLICATE-ADMISSION-01 current-basis revalidation

Date: 2026-09-12

## Exact review basis

- Fixed independently CLEAN exploratory basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Active Task 005 cache review-fix start: `9aebcb7e2eb3c87813d49ea6c68b35d365f41738`
- Active implementation diff inspected: **NO**
- Governing protocol: `ireum-0/private:ytdlnisx-review/ytdlnisx/REVIEW_PROTOCOL.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Original finding checkpoint: `review-runs/checkpoints/2026-09-10__c2294c87__duplicate-admission-race.md`

## Verdict

**OPEN / NOT_CLEAN for this root.**

`BUG-DUPLICATE-ADMISSION-01` remains a distinct P2 semantic root at the fixed basis.

Canonical blocker-count delta: `0`.

The current canonical count remains **P0 2 / P1 1 / P2 32** after the separately recorded current-basis closure of F19 `BUG-DUPLICATE-01`.

The independently CLEAN basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.

## Governing invariant

When duplicate prevention is enabled, duplicate observation and publication of the corresponding runnable Download must share one authoritative admission boundary. Two concurrent producers that resolve to the same corrected duplicate identity must not both pass stale pre-insert snapshots and publish separate runnable Download rows.

This root is separate from F19 `BUG-DUPLICATE-01`:

- F19 owns what media/configuration compares equal;
- this root owns atomic publication once that equality is known.

## Exact current-source evidence at `93d01d2a...`

### Comparator semantics are no longer the blocker

`DownloadConfigurationDuplicatePolicy` at the fixed basis supplies the corrected F19 identity/configuration relation. Supported YouTube URL forms canonicalize to a stable video ID while request-shaping configuration remains part of equality, and unknown providers are handled conservatively.

Manual and Observe config-mode duplicate checks both call this policy. The F19 comparator correction therefore does not itself close the later admission race.

### Observe production check -> insert gap persists

`ObserveSourceWorker` still builds/snapshots `activeAndQueuedDownloads`, evaluates duplicate policy against that in-memory snapshot, and only later publishes a new row through `downloadRepo.insert(it)`.

After insertion, the new item is added only to that worker's local `activeAndQueuedDownloads` list.

There is no shared duplicate-admission mutex, Room transaction, reservation row, semantic unique key, or final same-boundary duplicate revalidation spanning the comparison and the insert.

Two independent Observe producers can therefore both:

1. snapshot no semantically equivalent active/queued Download;
2. independently pass the corrected duplicate comparator;
3. both reach `downloadRepo.insert(...)`;
4. publish different Download primary keys for the same media/configuration.

The original finding's production reachability through independent Observe-source WorkManager namespaces is not disproved by current source.

### Repository/database boundary still publishes unconditionally

At the fixed basis, `DownloadRepository.insert(item)` is a thin wrapper around `downloadDao.insert(item)`.

It performs no duplicate-policy read, semantic reservation, compare-and-insert transaction, or producer generation check.

The insertion boundary therefore cannot reject a stale duplicate decision made by a caller.

### Manual producer remains relevant

`DownloadViewModel` config duplicate handling also observes current active/queued candidates through `DownloadConfigurationDuplicatePolicy.findMatch(...)` before later queue publication. The shared comparator defines equality, but publication is not converted into a single canonical duplicate-admission primitive.

The current architecture consequently still permits producer-to-producer stale-negative admission unless an individual caller happens to be serialized for unrelated reasons. Such incidental serialization is not the required cross-producer authority.

## Concrete impact

With duplicate prevention enabled, overlapping producers that request one canonical media/configuration can both publish runnable Download rows. They can later be claimed as independent executions, defeating duplicate-prevention policy and allowing duplicate native/output/History work.

The defect is concrete even though the comparator itself is now correct: a correct equality predicate used against two stale snapshots is not atomic admission.

## Root/count reconciliation

- F19 `BUG-DUPLICATE-01`: separately CLOSED at the current fixed basis by current-source revalidation; its canonical P2 decrement is not reversed here.
- `BUG-DUPLICATE-ADMISSION-01`: remains OPEN P2; this is the already-counted check-to-insert race, not a new finding.
- `BUG-OBSERVE-HANDOFF-01`: separate WorkManager/successor ownership root.
- Download execution ownership/publication roots: separate post-insert execution authority.

No new root is added in this checkpoint.

## Stable correction direction

A future correction should establish one authoritative duplicate-admission primitive for all producers that honor duplicate prevention. It should consume the corrected F19 semantic identity/configuration relation and make the final duplicate decision inseparable from durable row reservation/publication.

Acceptable shapes include a same-transaction semantic reservation or an equivalent exact admission authority, provided that:

- concurrent equivalent producers cannot both publish runnable rows;
- the losing producer receives an explicit duplicate/refused result;
- intentional redownload/replacement or duplicate-policy bypass semantics remain explicit and separate;
- manual, Observe, and any other normal duplicate-prevention producers use the same authority rather than independent check-then-insert flows;
- process death does not leave a permanent false reservation without a recovery owner;
- no broad raw-URL or title key replaces the corrected F19 identity contract.

## Consequence

`BUG-DUPLICATE-ADMISSION-01` remains OPEN P2 at `93d01d2a...`.

No implementation prompt is issued during the active Task 005 cache review-fix wave; the active implementation target remains the cache review-fix and its diff remains frozen from inspection.

INDEPENDENT EXECUTION: NOT EXECUTED