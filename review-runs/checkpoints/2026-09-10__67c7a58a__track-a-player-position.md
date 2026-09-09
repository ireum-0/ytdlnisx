# Independent Track A correctness checkpoint — playback position ordering

- Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`
- Implementation branch HEAD observed during this review: `8de0e0f8aa7c0f4294c41b4b6e958acf53a778fa`
- Review branch parent before this checkpoint: `df36eb26c3d552f9d62fb838d3c83f85c6ff2ade`
- Plan commit: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Authoritative ledger ref (not modified): `899328bc91e4008e39a658387396a0106c8666ec`
- Master Plan SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`

## Semantic decision established by source review

### P2-M / F21 `BUG-PLAYER-01` — CONFIRMED P2

Root invariant:

> The durable/resume playback position for a History item must represent the latest logical playback-position event, including `0` as an authoritative later event after completion/near-end reset.

Exact production path at the fixed Review Basis:

- `app/src/main/java/com/ireum/ytdl/VideoPlayerActivity.kt`
  - `onPositionDiscontinuity()` persists the old item's position when moving across media items.
  - `onPlaybackStateChanged(STATE_ENDED)` explicitly persists `0L` for the completed current History item.
  - `onPause()`, `onStop()`, `onDestroy()`, playback close, and playback replacement also call current-position persistence.
  - `savePlaybackPositionForHistoryId()` synchronously updates process state/cache, then launches an independent `lifecycleScope.launch(Dispatchers.IO)` Room `updatePlaybackPosition()` write. There is no per-History sequence/token/serialization contract tying Room completion order to logical submission order.
  - `cachePlaybackPosition()` writes the latest submitted value to SharedPreferences.
  - `resolveQueueStartPositionFromItems()` reads cache and Room-backed `HistoryItem.playbackPositionMs`, but filters both through `takeIf { it > 0L }` and then chooses `maxOf(...)`.

Concrete failure path requiring no hypothetical URL/file mismatch:

1. A History item has a prior durable positive resume position `P` in Room.
2. Playback reaches `STATE_ENDED`; the latest semantic event is `0L` and `savePlaybackPositionForHistoryId(id, 0L)` updates playback state/cache immediately while its Room update is merely launched on `Dispatchers.IO`.
3. Before Room is guaranteed to reflect that `0L` (including Activity destruction/cancellation/recreation windows), queue startup/rebuild obtains the History row with stale `P`.
4. `resolveQueueStartPositionFromItems()` discards authoritative cached `0L` because `takeIf { it > 0L }` converts it to null, accepts stale positive `P`, and resolves to `P`.
5. The completed item can therefore resume from an obsolete position instead of remaining reset.

There is a second ordering weakness under the same root: multiple logical saves are issued as independent lifecycle-scope IO coroutines without a per-History ordering token/serialized durable writer, so completion order of Room writes is not itself the semantic order. This checkpoint does not require that weaker race alone to establish the P2; the `0L`-discard path above is concrete in source.

Producer → authority → consumer/impact:

`player logical save events (including terminal 0)`
→ `process/cache latest value + independently scheduled Room update`
→ `queue/recreation resume resolution`
→ `zero is treated as absence and stale positive History value can win`
→ `completed media resumes at an obsolete position`.

## Acceptance condition

- Preserve a per-History logical ordering contract for playback-position submissions.
- A later `0L` reset is an authoritative value, not “no cached value.”
- Activity destruction/recreation cannot make an older Room value outrank a newer submitted reset.
- An older/delayed write must never overwrite a newer logical position event.
- Queue/cache/Room recovery must converge to the same latest logical value.
- Prefer an application-scoped ordered/coalescing writer or equivalent durable/serialized mechanism; a schema migration is not required if ordering can be proven without one.
- Required focused tests should include delayed earlier positive write followed by `0`, rapid saves, pause/stop/destroy, Activity recreation, and cache/Room convergence.

## Working count update

Canonical count after P2-L:

`P0 0 / P1 0 / P2 10`

After confirming P2-M:

`P0 0 / P1 0 / P2 11`

## Execution evidence

No JVM, instrumentation, emulator, or device execution was performed for this finding. The decision is based on exact-SHA source-level production-path evidence.

`INDEPENDENT EXECUTION: NOT EXECUTED`
