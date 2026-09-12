# Task 007 / BUG-PLAYER-01 candidate sequential independent review

Date: 2026-09-12

## Review basis and candidate isolation

- Canonical independently CLEAN basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Overnight queue base: `36b43464b8106d90d672ba94718ccee58f38974f`
- Reported queue summary/handoff SHA was malformed/truncated: `c5364c667c31c6d7031c6ca406f97d156`
- Resolved candidate branch: `candidate/overnight-20260912-player01`
- Exact candidate: `c5364c667c31c6d7031c6ca406f97eb4d457d156`
- Exact candidate is one commit ahead of queue base.
- Compare against canonical CLEAN basis is diverged by one commit on each side with merge base exactly `36b43464b8106d90d672ba94718ccee58f38974f`; candidate is isolated and not integrated.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F21 `BUG-PLAYER-01`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN for `BUG-PLAYER-01`. Do not replay/integrate this candidate as-is.**

Count delta: **0**. The finding below is residual scope of the already-counted P2 `BUG-PLAYER-01`, not a new root.

Canonical blocker count remains **P0 2 / P1 1 / P2 34**.

Canonical CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## What the candidate fixes correctly

The candidate meaningfully fixes the original write-order/lifecycle-owner defect:

- `PlaybackPositionPersistence` owns writes in a process/application-lifetime coroutine scope rather than the Activity lifecycle scope;
- `OrderedPlaybackPositionWriter` maintains a separate submission tail per History ID;
- accepted writes for one History ID are serialized in logical submission order;
- different History IDs remain independent;
- a later accepted write proceeds even if an earlier write fails;
- `VideoPlayerActivity.savePlaybackPositionForHistoryId()` still updates in-memory queue state and the SharedPreferences cache synchronously at submission, then delegates Room persistence to the ordered process owner;
- the focused tests prove delayed earlier write -> later value, later completion zero, rapid submission ordering, caller-scope destruction, recreation sharing one writer, independent IDs, failure continuation, and zero as a valid immediate queue value.

These pieces should be preserved.

## Residual blocker: production resume resolution discards newer zero reset authority

The Master Plan explicitly requires completion/reset zero to be a **later ordered event that cannot be overtaken**, and calls for Activity-recreation plus cache/Room convergence coverage.

The candidate only changes the write owner. Existing production start-position resolution remains incompatible with that invariant.

### Deterministic same-process recreation race

`savePlaybackPositionForHistoryId(historyId, 0)` does the following in submission order:

1. records `0` in `PlaybackQueueState`;
2. caches `0` in SharedPreferences;
3. asynchronously submits the Room `UPDATE` to `PlaybackPositionPersistence`.

Therefore there is a valid interval where the cache contains authoritative newer `0`, while the `HistoryItem.playbackPositionMs` loaded from Room still contains an older positive value.

During queue reconstruction, `resolveQueueStartPositionFromItems(...)` does:

```kotlin
val cachedPosition = getCachedPlaybackPosition(id)?.takeIf { it > 0L }
val itemSavedPosition = item.playbackPositionMs.takeIf { it > 0L }
val resolved = maxOf(requested ?: 0L, itemSavedPosition ?: 0L, cachedPosition ?: 0L)
```

The newer cached `0` is discarded as though absent. The stale positive Room value remains eligible and wins. An Activity recreated before the queued Room zero commits can therefore resume from the stale positive position even though the later logical event was a reset/completion zero.

This is not merely a process-death durability edge; it exists entirely within one process and follows directly from the candidate's intentionally asynchronous durable write.

### Cache/Room convergence semantics are incomplete

The candidate's tests demonstrate that the writer eventually commits zero and that in-memory queue state can contain zero, but no production-path test verifies that a cached zero outranks an older positive Room position during recreation. The restore consumer has no freshness/authority rule capable of expressing that ordering; it instead compares only positive magnitudes.

A later logical position is not necessarily numerically larger. `maxOf(...)` is therefore not a valid conflict-resolution rule for ordered playback-position state.

## Minimal correction boundary

Preserve the process-scoped ordered writer, but make the resume-side authority model compatible with submission ordering.

The correction should ensure that:

1. an explicitly cached/submitted zero is distinguishable from “no cached value”;
2. when a newer local submission exists for a History ID, that submitted value (including zero) has precedence over an older Room snapshot during Activity recreation;
3. an explicit launch/requested seek, live player position, or other intentionally higher-priority source retains its documented semantics rather than being accidentally overridden;
4. once ordered Room persistence catches up, cache and Room converge without resurrecting an earlier positive position;
5. History-ID transitions do not apply one item's pending value to another item.

This can likely be closed without schema change. The smallest safe design is to define one explicit resume-authority/precedence contract for requested/live/queued-cached/Room positions and test it at the production resolution boundary. Do not use numeric `max` as a proxy for recency.

## Required focused regression coverage

Add deterministic coverage for at least:

- Room contains `15_000`, later accepted/cache value is `0`, Room zero write is deliberately delayed, Activity/queue reconstruction resolves to `0` rather than `15_000`;
- the same scenario after the Room write completes converges to `0`;
- delayed earlier positive followed by later non-zero position still resolves to the later logical value, not merely the larger number;
- explicit requested/live-current precedence remains correct;
- History-ID transition does not cross-apply pending/cache state;
- existing writer ordering and Activity-lifecycle survival tests remain passing.

## Candidate disposition

`c5364c667c31c6d7031c6ca406f97eb4d457d156` is **NOT_CLEAN** and must not be replayed as-is. Its process-scoped ordered writer and tests are reusable, but the production resume consumer must be made zero/ordering-aware before `BUG-PLAYER-01` is closed.

INDEPENDENT EXECUTION: NOT EXECUTED
