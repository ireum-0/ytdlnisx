# F21 / BUG-PLAYER-01 — exact CLEAN-basis revalidation

Date: 2026-09-14

## Exact review state

- Independently CLEAN review basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Prior checkpoint: `d97c711e4da69c067e204adbebad3fff4b5353ae` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F21 `BUG-PLAYER-01`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Active F10+F16+F17 implementation remains frozen from inspection. No in-progress implementation diff was used.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `BUG-PLAYER-01` remains valid at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 0 / P2 23**.
- CLEAN Review Basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Governing invariant

The final durable playback position for one History item must follow the latest logical save. Logical submission order, not coroutine completion order or numeric magnitude, owns playback-position authority. A later reset/near-end `0` is a real ordered value and must not be discarded as absence.

## Intervening-range verification

Exact compare `3616ae02... -> 90afaec1...` is 16 commits ahead and does not modify `VideoPlayerActivity.kt`. `HistoryDao.kt` changes elsewhere in the range, so exact final DAO source was reread; `updatePlaybackPosition(id, positionMs)` remains an ordinary scalar update with no submission generation/order predicate.

Exact final `VideoPlayerActivity.kt` was reread directly.

## Exact current production evidence

### 1. Accepted playback saves still use independent Activity-lifecycle Room coroutines

`savePlaybackPositionForHistoryId(historyId, positionMs)` still:

1. records the position in process-local `playbackQueueState`;
2. stores it in SharedPreferences cache;
3. launches an independent `lifecycleScope.launch(Dispatchers.IO)` that calls `historyDao.updatePlaybackPosition(historyId, positionMs)`.

There is no application/process-lifetime persistence owner, no per-History serialized submission queue, and no logical submission generation checked at the Room write.

Production submits saves for the same History ID from position/media transitions, `onStop()`, `onDestroy()`, and end/near-end handling. Two logical saves can therefore commit to Room in reverse order. Activity destruction can also cancel a lifecycle-owned persistence coroutine before its durable write completes.

### 2. Later zero remains a real producer event

`Player.STATE_ENDED` still calls `savePlaybackPositionForHistoryId(currentHistoryId, 0L)`.

Duration-aware saves also normalize a near-end position to `0L` before submitting it.

The zero is therefore an explicit later playback-position decision, not an absent/default value.

### 3. Resume reconstruction still discards explicit cached zero and uses numeric magnitude as recency

`cachePlaybackPosition()` stores every value, including zero, and `getCachedPlaybackPosition()` distinguishes key absence from an explicit stored zero using `prefs.contains(key)`.

However `resolveQueueStartPositionFromItems()` still:

- discards cached zero via `takeIf { it > 0L }`;
- discards Room/item zero via the same predicate;
- resolves `maxOf(requested, itemSaved, cached)`.

Numeric maximum is not logical submission order. A newer backward seek may be smaller than an older persisted value, and a newer completion/reset may be zero.

Concrete sequence remains:

Room H = 15_000
-> later logical reset submits 0
-> queue/cache now hold 0
-> zero Room write is delayed/cancelled, or an earlier positive write commits after it
-> recreation reads stale Room 15_000
-> resolver discards explicit cached 0
-> numeric max selects 15_000
-> playback resumes from stale positive position despite the later reset.

## Root/count reconciliation

Unordered/cancellable durable writes and stale resume reconstruction remain two authority sides of the same F21 contract: latest logical playback-position authority must survive persistence and reconstruction. Do not split them into separate roots.

Count delta remains `0`.

## Stable correction boundary

A future correction still needs a process/application-lifetime persistence owner, per-History serialization/coalescing by logical submission order, independent progress across distinct History IDs, explicit ordered-zero handling, retry after write failure, and a resume precedence model that distinguishes absence from zero and lets newer values outrank older Room state even when numerically smaller.

Preserve resume threshold, near-end reset, seek/pause/stop, PiP/background behavior, queue transitions, and Activity recreation semantics.

INDEPENDENT EXECUTION: NOT EXECUTED