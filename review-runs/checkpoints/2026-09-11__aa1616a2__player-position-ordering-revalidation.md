# BUG-PLAYER-01 — ordered playback-position persistence revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while a separate Luna `BUG-OBSERVE-01` implementation wave is active.
- The active Luna implementation branch HEAD, commits, and diffs were not inspected, compared, reviewed, or relied upon for this decision.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Defect: `BUG-PLAYER-01` / F21

The fixed CLEAN basis still submits playback-position persistence through independent Activity-scoped IO coroutines with no per-History ordering owner. The in-memory queue/cache is updated synchronously, but Room writes are unconditional and may complete out of logical order. The final `onDestroy()` save is launched from the same lifecycle scope at the point that scope is being destroyed, so the last logical position also lacks durable completion ownership.

This is the historical ordered playback-position persistence root. It is distinct from the separately tracked player timeline/index crash family.

## Exact-source evidence

### 1. Every position save launches an independent Room writer

`app/src/main/java/com/ireum/ytdl/VideoPlayerActivity.kt` implements:

`savePlaybackPositionForHistoryId(historyId, positionMs)`

by first updating `PlaybackQueueState` and the SharedPreferences cache, then launching:

`lifecycleScope.launch(Dispatchers.IO) { historyDao.updatePlaybackPosition(historyId, positionMs) }`

There is no per-History sequence number, actor, mutex/serialization owner, coalescing writer, compare-and-set version, or other ordering authority around those Room writes.

If logical save A is submitted before logical save B but A's IO coroutine completes after B, the older value can become the final durable Room value.

### 2. Completion reset is exposed to the same stale-overwrite race

When playback reaches `Player.STATE_ENDED`, the activity records `0L` for the current History item. Position discontinuities and ordinary lifecycle saves also call the same persistence helper.

Because every call launches an independent unconditional DB update, a slower earlier nonzero save may complete after the later logical completion reset and restore a stale resume position. This directly violates the F21 requirement that completion zero is a later ordered event that cannot be overtaken.

### 3. Activity destruction does not own completion of the final durable write

`onDestroy()` calls `savePlaybackPositionForCurrentItem()` and only then releases player/activity resources and calls `super.onDestroy()`.

That save again creates a new `lifecycleScope` IO coroutine. `lifecycleScope` is tied to the Activity lifecycle and is cancelled when the lifecycle is destroyed; there is no application-scoped or otherwise durable owner awaiting/serializing that final persistence. Therefore Activity destruction may cancel the last submitted Room write even though the in-memory/cache copy was already updated.

This is the exact lifetime mismatch described by F21.

### 4. DAO update provides no stale-write guard

`HistoryDao.updatePlaybackPosition(id, positionMs)` is a simple unconditional:

`UPDATE history SET playbackPositionMs = :positionMs WHERE id = :id`

The DAO has no sequence/generation condition that could independently reject an older logical write arriving late.

### 5. Queue state protects in-memory lookup, not durable ordering

`PlaybackQueueState.recordPlaybackPosition(id, positionMs)` updates an in-memory map synchronously. This is a useful immediate state/cache behavior but does not serialize Room persistence.

The exact-basis `PlaybackQueueStateTest` verifies owned queue ordering/lookups and in-memory `recordPlaybackPosition`, but it does not exercise concurrent/delayed Room writes, lifecycle destruction, or terminal zero ordering.

## Preserved positive behavior

A correction should preserve:

- immediate in-memory/cache update at logical submission so current UI/resume lookup sees the latest logical value;
- resume threshold / near-end reset semantics;
- the current completion-to-zero behavior as a logical event;
- queue transitions, shuffle/repeat, seek/pause/stop and background/PiP behavior unless exact source proves a required interaction;
- current History ID scoping.

The defect is persistence ordering/lifetime ownership, not queue index semantics.

## Governing correction boundary

The Master Plan F21 contract remains applicable:

1. Establish one application-scoped or otherwise lifecycle-safe ordered/coalescing persistence owner.
2. Serialize durable Room persistence per History ID according to logical submission order.
3. Update queue/cache state at submission so readers observe the latest logical position immediately.
4. A later logical save must never be overwritten durably by an earlier delayed write.
5. Completion `0L` is a later ordered event and must not be overtaken by an earlier nonzero save.
6. Activity destruction must not cancel ownership of the final submitted logical position merely because the Activity lifecycle ends.
7. Preserve independent ordering across different History IDs without unnecessarily serializing unrelated items globally if the current architecture can avoid it.
8. Do not introduce a schema migration unless focused design proves sequencing cannot be established safely without one; F21 explicitly prefers application-scoped serialization/coalescing first.
9. Add semantic regression coverage for at least:
   - delayed earlier write followed by later position -> later wins;
   - delayed earlier nonzero write followed by completion zero -> zero wins;
   - rapid seeks/submissions -> final durable value equals latest logical save;
   - pause/stop/destruction after submission -> final save remains owned;
   - Activity recreation -> old Activity writer cannot overwrite later new-Activity logical state;
   - transitions between History IDs preserve per-ID order;
   - cache/in-memory and Room converge after writer drain.
10. Keep the newer player timeline/index crash family distinct unless exact source proves a single owner/correction genuinely closes both.

## Root/count reconciliation

- This is a revalidation of existing canonical P2 root `BUG-PLAYER-01`, not a new root.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- Contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- The separate active Luna `BUG-OBSERVE-01` implementation was not inspected or relied upon.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
