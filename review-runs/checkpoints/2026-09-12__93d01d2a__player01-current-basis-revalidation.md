# BUG-PLAYER-01 current-basis revalidation — 2026-09-12

## Scope

- Exact independently CLEAN review basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Root: existing P2 `BUG-PLAYER-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F21
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Latest isolated candidate: `c5364c667c31c6d7031c6ca406f97eb4d457d156` — previously reviewed NOT_CLEAN and not integrated
- Active Task 004 / BUG-CACHE-01 implementation diff was not inspected.

## Verdict

**OPEN / NOT_CLEAN for `BUG-PLAYER-01`.**

Count delta: `0`.

Canonical blocker count remains **P0 2 / P1 1 / P2 33**.

The independently CLEAN basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.

## Governing invariant

F21 requires the final durable playback position for a History item to equal the latest logical save. The preferred correction is an application/process-scoped ordered/coalescing owner per History ID. Completion/reset zero is a later ordered event and must not be overtaken. Activity destruction/recreation and cache/Room convergence are required review/test surfaces.

## Exact current-source evidence

### 1. Canonical source still uses Activity-lifecycle-owned unordered Room writes

At exact `93d01d2a...`, `VideoPlayerActivity.savePlaybackPositionForHistoryId()` does:

```text
playbackQueueState.recordPlaybackPosition(historyId, positionMs)
cachePlaybackPosition(historyId, positionMs)
lifecycleScope.launch(Dispatchers.IO) {
    historyDao.updatePlaybackPosition(historyId, positionMs)
}
```

There is no process/application-scoped writer and no per-History submission ordering.

`onStop()` calls `savePlaybackPositionForCurrentItem()`, and `onDestroy()` calls it again. Player transitions/end handling also submit position updates, including an explicit completion/reset `0`.

Therefore multiple logical saves for the same History ID can create independent IO coroutines whose Room updates are not serialized by submission order. A later logical save can commit before an earlier coroutine and then be overwritten by that stale earlier write.

The `onDestroy()` submission is additionally owned by the Activity `lifecycleScope`, whose lifetime ends with the destroyed Activity. The write can be cancelled before durable completion rather than being owned independently of Activity lifecycle.

This reproduces the original F21 root directly in current canonical source.

### 2. Explicit completion/reset zero is produced as a real later event

Current player code submits `savePlaybackPositionForHistoryId(currentHistoryId, 0L)` on `Player.STATE_ENDED` and also normalizes near-end saves to `0` in duration-aware paths.

Therefore zero is not merely a default/sentinel. It is a meaningful later logical playback-position event that must participate in ordering and resume authority.

### 3. Resume resolution discards an explicit cached zero

`cachePlaybackPosition()` persists every submitted value, including zero, to SharedPreferences. `getCachedPlaybackPosition()` can distinguish key absence from a stored zero by using `prefs.contains(key)`.

However `resolveQueueStartPositionFromItems()` immediately applies:

```text
getCachedPlaybackPosition(id)?.takeIf { it > 0L }
item.playbackPositionMs.takeIf { it > 0L }
maxOf(requested, itemSaved, cached)
```

So an explicit cached zero is converted to `null` even though the key exists. The resolver then chooses by numeric magnitude rather than logical recency/authority.

Concrete reachable sequence:

```text
Room playbackPositionMs = 15_000
later logical completion/reset submits 0
queue state becomes 0
SharedPreferences cache contains explicit 0
Room zero update is still pending/cancelled/overtaken
Activity is recreated and reloads stale Room 15_000
resolver discards cached 0 because !(0 > 0)
maxOf(...) selects stale 15_000
playback resumes from stale positive position
```

This is a same-process correctness failure and does not require process death.

A later logical value is also not necessarily numerically larger than an earlier one, so `maxOf()` is not a valid general recency rule even for two positive positions after seeking backward.

### 4. Relation to the isolated candidate

Candidate `c5364c66...` introduced a process-scoped per-History ordered writer and correctly addressed the first lifecycle/write-order half. Independent candidate review found it still left the resume-side zero/precedence problem above.

Because that candidate is not integrated, current canonical source contains **both**:

- the original unordered Activity-lifecycle persistence defect; and
- the zero/recency-incompatible resume resolver.

Do not split these into a new blocker ID. They are two consumer/authority sides of the same `BUG-PLAYER-01` contract: the latest logical playback-position event must remain authoritative through persistence and reconstruction.

## Root/count reconciliation

This is the existing P2 `BUG-PLAYER-01`, count delta `0`.

Do not merge it with unrelated current-player timeline/index crash families unless fresh evidence proves a shared state owner and correction invariant. The Master Plan explicitly tracks those separately.

## Required future correction boundary

A correct canonical implementation should preserve/reuse the strongest parts of isolated candidate `c5364c66...` but also close resume authority:

1. use one process/application-lifetime persistence owner rather than Activity `lifecycleScope`;
2. serialize accepted writes in logical submission order per History ID;
3. allow distinct History IDs to progress independently;
4. a later zero must remain a valid ordered event and cannot be overtaken by an earlier positive write;
5. failure of one write must not permanently strand later accepted saves;
6. define an explicit resume precedence contract for requested launch position, live/queue submitted state, SharedPreferences cache, and Room state;
7. distinguish explicit cached zero from cache absence;
8. when a newer local submission exists, it must outrank an older Room snapshot even when numerically smaller;
9. preserve intentional explicit requested/live-current precedence where product behavior requires it;
10. History-ID transitions must not cross-apply one item's pending/cache state to another;
11. once Room catches up, cache/Room reconstruction must converge without resurrecting an older value.

No schema migration appears inherently required. A process-scoped ordered writer plus an explicit same-process resume authority/precedence model is likely sufficient unless fresh implementation evidence proves otherwise.

## Required focused regression coverage

At minimum:

- delayed earlier positive Room write followed by later positive save converges to later logical save even if numerically smaller;
- delayed earlier positive followed by completion/reset zero converges to zero;
- Activity destruction after accepting a save does not cancel the persistence obligation;
- recreation while Room zero is delayed sees explicit cached/submitted zero and resolves to zero;
- after Room catches up, reconstruction still resolves to zero;
- rapid seek/pause/stop/destruction sequence preserves submission order;
- different History IDs remain independent;
- History-ID transitions do not cross-apply pending/cache state;
- explicit requested/live precedence remains intentional and tested.

INDEPENDENT EXECUTION: NOT EXECUTED