# BUG-PLAYER-01 current-basis revalidation — 2026-09-12

## Scope

- Exact independently CLEAN review basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Root: existing P2 `BUG-PLAYER-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F21
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior current-basis review: `cae1f66137c64ecf7f6a9e9c93ff64066ed295c0` at `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Completed duplicate-admission implementation state `8c5db3c7...` remains outside the CLEAN basis pending exact-SHA execution evidence; it was not used as this exploratory basis.

## Verdict

**OPEN / NOT_CLEAN for `BUG-PLAYER-01`.**

- Count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.

This is the existing F21/P2 root, not a new finding.

## Governing invariant

The final durable playback position for a History item must equal the latest logical save. Logical submission order, not coroutine completion order or numeric magnitude, owns playback-position authority. A later completion/near-end reset of `0` is a real ordered event and must not be overtaken or discarded.

## Exact-source revalidation

The intervening `93d01d2a... -> 3616ae02...` range does not modify the player persistence/resume surface. Exact `3616ae02...` source therefore still contains both previously confirmed authority gaps.

### 1. Activity-lifecycle-owned independent Room writes remain unordered

`VideoPlayerActivity.savePlaybackPositionForHistoryId()` still:

1. updates process-local `playbackQueueState`;
2. writes the SharedPreferences playback-position cache;
3. launches an independent `lifecycleScope.launch(Dispatchers.IO)` that calls `historyDao.updatePlaybackPosition(historyId, positionMs)`.

There is no application/process-scoped persistence owner and no per-History logical submission sequence applied to Room commits.

Production creates multiple saves for the same History ID from media-item transitions/discontinuities, `onStop()`, `onDestroy()`, and end/near-end handling. Two accepted logical saves can therefore commit to Room in the opposite order. `onDestroy()` also submits through the Activity `lifecycleScope`, so destruction can cancel the persistence obligation before durable completion.

### 2. Later zero remains a real producer event

`Player.STATE_ENDED` still submits `savePlaybackPositionForHistoryId(currentHistoryId, 0L)`. Duration-aware save paths also normalize a near-end position to `0L`.

That zero is a later logical save/reset, not absence or a default sentinel.

### 3. Resume reconstruction still discards explicit cached zero and uses numeric maximum as recency

`cachePlaybackPosition()` stores every value, including zero. `getCachedPlaybackPosition()` distinguishes absence from an explicit zero via `prefs.contains(key)`.

But `resolveQueueStartPositionFromItems()` still does:

- cached value -> `takeIf { it > 0L }`;
- Room/item value -> `takeIf { it > 0L }`;
- `maxOf(requested, itemSaved, cached)`.

A newer cached/submitted zero is therefore discarded even while an older positive Room snapshot is retained. Likewise, a legitimate later backward seek can be numerically smaller than an older save, so `maxOf()` is not a logical-order rule.

Concrete same-process failure remains:

1. Room contains `15_000` for History H;
2. later completion/reset for H submits `0`;
3. queue state and SharedPreferences cache reflect `0`;
4. Room zero write is delayed, cancelled, or later overwritten by an earlier coroutine;
5. Activity recreation loads stale Room `15_000`;
6. resolver discards explicit cached zero and chooses `15_000`;
7. playback resumes from stale positive position despite the later logical reset.

## Root/count reconciliation

Unordered durable writes and stale resume reconstruction are two authority sides of the same F21 contract: latest logical playback-position authority must survive persistence and reconstruction. Do not split them into separate blocker IDs.

No evidence in `93d01d2a... -> 3616ae02...` closes or reclassifies this root.

## Stable correction boundary

A future implementation should:

- use a process/application-lifetime persistence owner rather than Activity `lifecycleScope`;
- serialize/coalesce accepted writes in logical submission order per History ID;
- preserve independence across different History IDs;
- treat later zero as an ordered value, not absence;
- ensure one failed write does not permanently strand later accepted saves;
- define explicit resume precedence across launch-requested position, live/queue submission state, SharedPreferences cache, and Room snapshot;
- distinguish cache absence from explicit cached zero;
- allow newer values to outrank older Room values even when numerically smaller;
- preserve resume threshold, near-end reset, seek/pause/stop, PiP/background behavior, queue transitions, and Activity recreation semantics;
- avoid a schema migration unless focused implementation evidence proves in-process ordering/authority is insufficient.

Focused regressions should include delayed earlier positive -> later smaller positive, delayed earlier positive -> later zero, pause/stop/destruction, recreation while Room is stale, Room/cache convergence, rapid seeks, History-ID transitions, and different-ID independence.

## Review disposition

`BUG-PLAYER-01` remains an existing P2 blocker with count delta `0`. No CLEAN-basis advance is justified.

INDEPENDENT EXECUTION: NOT EXECUTED