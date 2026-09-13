# F21 / BUG-PLAYER-01 — current-state reconciliation at exact CLEAN basis

Date: 2026-09-14

## Scope

- Exact independently CLEAN basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Existing F21 exact-basis revalidation checkpoint: `7a2cea5d51eccaec4a052bca47853e50af9f2780`.
- Earlier F21 checkpoint: `d97c711e4da69c067e204adbebad3fff4b5353ae` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Current completed-wave canonical review state is P0 2 / P1 0 / P2 21, with F16 and F17 closed at `f6e7cf72e00c013ee9b770bf748cba7f38848256` and F10 remaining OPEN.
- Active implementation wave is F10 review fix + F18 + F20 from exact start `f6e7cf72...`; no post-start implementation diff was inspected or used.

## Reconciliation

The existing checkpoint `7a2cea5d...` correctly revalidated the F21 source semantics at `90afaec1...`, but its printed blocker total `P0 2 / P1 0 / P2 23` predates the later canonical F16/F17 closure reconciliation. That historical count is superseded by the later completed-wave review state.

F21 itself had count delta `0`, so updating the surrounding canonical total does not change F21's root disposition.

## Exact-source confirmation

Exact compare `3616ae02... -> 90afaec1...` is 16 commits ahead and does not modify `VideoPlayerActivity.kt`.

Exact `VideoPlayerActivity.kt@90afaec1...` still shows:

- `savePlaybackPositionForHistoryId()` updates queue state and SharedPreferences, then launches an independent `lifecycleScope.launch(Dispatchers.IO)` Room update;
- `onStop()` and `onDestroy()` both submit playback-position saves through that Activity-owned mechanism;
- `Player.STATE_ENDED` submits an explicit later `0L` reset;
- duration-aware saves normalize near-end positions to `0L`;
- `getCachedPlaybackPosition()` distinguishes absent key from explicit zero;
- `resolveQueueStartPositionFromItems()` nevertheless discards cached/item zero with `takeIf { it > 0L }` and resolves by numeric `maxOf(requested, itemSaved, cached)`.

Exact `HistoryDao.kt@90afaec1...` still persists playback position with a plain scalar `UPDATE history SET playbackPositionMs = :positionMs WHERE id = :id`, with no per-History logical submission ordering predicate.

Therefore the previously proven production failure remains reachable: a later smaller-positive or zero logical save can be delayed/cancelled/overtaken at Room persistence, and recreation can then prefer an older numerically larger Room value because the newer explicit zero is discarded and numeric magnitude is treated as recency.

## Verdict

**OPEN / CONFIRMED — existing P2 `BUG-PLAYER-01` remains valid at exact CLEAN basis `90afaec1...`.**

- F21 count delta: `0`.
- Current canonical blocker count remains **P0 2 / P1 0 / P2 21**.
- Overall remains `NOT_CLEAN`.
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- No new root is added.

## Stable correction boundary

The existing focused correction boundary remains valid:

- process/application-lifetime persistence owner rather than Activity `lifecycleScope`;
- per-History serialization/coalescing by logical submission order;
- distinct History IDs remain independently progressable;
- later zero is a real ordered value;
- earlier delayed writes cannot overtake later smaller-positive/zero saves;
- one failed write must not strand later accepted saves;
- resume precedence must distinguish cache absence from explicit zero and must not use numeric magnitude as recency;
- preserve resume threshold, near-end reset, seek/pause/stop, PiP/background behavior, queue transitions, History-ID transitions, and Activity recreation;
- avoid schema migration unless focused implementation inspection proves application-scoped ordering insufficient.

Master Plan F21 remains `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA`.

INDEPENDENT EXECUTION: NOT EXECUTED