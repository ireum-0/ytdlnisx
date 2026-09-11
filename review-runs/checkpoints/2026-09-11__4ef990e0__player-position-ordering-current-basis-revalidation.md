# BUG-PLAYER-01 — playback-position ordering current-basis revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Prior exact-basis root checkpoint: `0a206815b3c6e8a17d592ef5a4d5f794b61a1c05` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Active implementation wave: P2 `BUG-METADATA-02` / F13, started from `4ef990e0...`
- Moving implementation diff inspected or relied on: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F21
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Overlap guard

Exact compare `aa1616a2... -> 4ef990e0...` contains only F3/F12 source-authority and automatic-keyword production-wiring changes. `VideoPlayerActivity`, `HistoryDao`, and playback-position ownership code were untouched.

The prior F21 disposition therefore carries forward after narrow exact-source confirmation.

## Verdict

**NOT_CLEAN / existing P2 `BUG-PLAYER-01` remains OPEN at `4ef990e0...`.**

Count delta: **0**.

Canonical blocker count remains **P0 2 / P1 2 / P2 25**.

CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.

## Exact current-source confirmation

`VideoPlayerActivity.kt@4ef990e0...` still implements `savePlaybackPositionForHistoryId(historyId, positionMs)` as:

1. synchronously update `PlaybackQueueState`;
2. synchronously update the SharedPreferences playback cache;
3. independently launch `lifecycleScope.launch(Dispatchers.IO)` and unconditionally write Room through `historyDao.updatePlaybackPosition(historyId, positionMs)`.

There is still no per-History sequence, actor, mutex/serialization owner, coalescing durable writer, or expected-version/CAS guard around those writes.

The production event paths still include:

- position discontinuity saving an old item's latest position;
- `Player.STATE_ENDED` submitting `0L` as the later logical completion event;
- ordinary current-item saves through the same helper;
- `onDestroy()` calling `savePlaybackPositionForCurrentItem()` immediately before Activity teardown.

Because every call submits an independent Activity-lifecycle IO coroutine, an earlier nonzero Room write can still finish after a later logical save, including after completion `0L`. The final `onDestroy()` submission also remains owned by a lifecycle scope that is being destroyed rather than by an application-scoped/lifecycle-safe persistence owner.

`HistoryDao.updatePlaybackPosition(id, positionMs)` remains a plain unconditional `UPDATE history SET playbackPositionMs = :positionMs WHERE id = :id`, with no stale-write rejection.

## Governing F21 invariant

The pinned Master Plan F21 requires the final durable playback position for a History item to equal the latest logical save. It prefers an application-scoped ordered/coalescing writer with per-History logical ordering, with completion zero treated as a later ordered event that cannot be overtaken.

Current source still violates that invariant.

## Root reconciliation

- This is the already-counted P2 `BUG-PLAYER-01`; no new root is introduced.
- Keep the separate player timeline/index crash family distinct unless a future exact-source correction genuinely shares one owner/invariant and proves both.
- F12 `BUG-KEYWORD-01` remains CLOSED at `4ef990e0...`; no shared-domain interaction was found.
- Active F13 implementation state is unchanged and was not inspected.

## Required correction boundary carried forward

1. Establish one lifecycle-safe ordered/coalescing persistence owner.
2. Serialize Room persistence per History ID by logical submission order.
3. Preserve immediate queue/cache update at submission.
4. Later logical saves must never be overwritten by earlier delayed writes.
5. Completion `0L` must not be overtaken by an older nonzero save.
6. Activity destruction must not cancel ownership of the final submitted logical position.
7. Preserve independent ordering across different History IDs and existing playback/queue semantics.
8. Add deterministic coverage for delayed earlier writes, completion zero, rapid saves, destruction/recreation, per-ID transitions, and cache/Room convergence.

INDEPENDENT EXECUTION: NOT EXECUTED