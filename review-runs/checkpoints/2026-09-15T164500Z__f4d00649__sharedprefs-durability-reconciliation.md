# F10 exact-SHA durability reconciliation — SharedPreferences false-write semantics

- implementation_sha: `f4d0064944b69c7c3afd55bfe9ee20ef2aabc8a8`
- implementation_parent_sha: `a67cb4ff8a367f3a4261eb816d4abfe1290b8e4f`
- review_parent_sha: `0ddd9609e23cfbc21586410b6c7f2fa0d95374b9`
- frozen_plan_sha: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen_ledger_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist_v6_commit: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- checklist_v6_blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Verdict

`BUG-CLEANUP-01 / F10` remains `OPEN P2 / NOT_CLEAN`.

Count delta: `0` — all residuals below are additional subcases of the already-counted F10 durable cleanup-authority root.

Canonical count remains `P0 2 / P1 0 / P2 22`.

CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Preserved parent-checkpoint residual

The parent exact-SHA review at `0ddd9609e23cfbc21586410b6c7f2fa0d95374b9` remains correct: the new effect journal marks a Cancelled/Error deletion step complete only after `deleteKnownUserRemoval(...)` returns, but that helper commits Room deletion before its per-item `DownloadCacheOwnership.deleteIfOwned(...)` suffix. Process death after the Room transaction but before cache cleanup/journal advancement leaves the journal incomplete; retry revalidates the frozen targets against Room, sees the already-deleted rows as absent, and can advance the deletion step without ever completing those rows' cache suffix. This is an unfinished destructive responsibility lost across a durable mutation boundary.

The repository's `deleteCache(items)` also wraps `DownloadCacheOwnership.deleteIfOwned(...)` in `runCatching` and ignores the returned Boolean, so a failed exact cache cleanup can be hidden from the journal step result. This is supporting evidence for the same post-Room/pre-cache residual, not a separate root.

## Additional F10 residual — real SharedPreferences `commit()==false` is memory-visible

The production coordinator uses `SharedPreferences.Editor.commit()` as the durability barrier for scheduling debt, effect phase, and effect journal writes. The test seams `authorityCommitOverrideForTesting = { false }` and `effectPhaseCommitOverrideForTesting = { false }` model failure as though the editor made no visible mutation.

That is not the Android platform contract implemented by `SharedPreferencesImpl`: `commit()` first calls `commitToMemory()`, then performs/awaits the disk write, and returns the disk-write result. Therefore a real `commit()==false` can leave the new values visible through the same in-process `SharedPreferences` object even though they were not durably written to disk.

This distinction is blocker-relevant because F10 recovery logic re-reads those same preferences as authority.

### Bootstrap concrete path

1. Durable state has enabled cleanup cadence but no generation/debt (legacy/upgrade bootstrap state).
2. `reconcileSuspending()` constructs generation + anchor + D1 pending debt and calls `commitAuthority(bootstrapEditor)`.
3. Real SharedPreferences updates its in-memory map, disk persistence fails, and `commit()` returns `false`.
4. Coordinator calls `ensureBootstrapReplayOwnerLocked(...)` and returns without enqueueing D1.
5. Bootstrap replay checks whether generation is still blank. The same in-process preference map now reports the non-durable generated value, so the replay owner can conclude bootstrap is no longer needed and exit.
6. No WorkManager occurrence was enqueued. The current process can remain enabled but unscheduled until a later process restart/reconfiguration reconstructs from durable state.

This violates the already-established F10 current-process bootstrap recovery invariant.

### Effect journal concrete path

1. Exact D1 is current and ELIGIBLE.
2. Worker prepares a frozen `CleanupEffectJournal`.
3. Coordinator calls one SharedPreferences `commit()` for `IN_PROGRESS + PREF_EFFECT_JOURNAL`.
4. Disk persistence fails but the same process can observe the in-memory `IN_PROGRESS + journal`; `commit()` returns `false`, so the first attempt does not run destructive work.
5. A subsequent WorkManager retry in the same process can re-read that memory-only journal and execute destructive Room/cache effects as though durable recovery authority existed.
6. If process death occurs before a later successful durable progress write, restart can reload the older on-disk state (for example ELIGIBLE/no journal), allowing D1 to prepare a new target snapshot after destructive effects already committed.

A memory-visible but non-durable journal therefore must never authorize irreversible cleanup work or be confused with restart-safe recovery authority.

## Additional F10 liveness residual — finite worker retries can lose the current-process owner

Even under the existing simplified test seam where a failed phase/journal commit leaves no in-memory mutation:

1. first `IN_PROGRESS + journal` publication fails;
2. `withCurrentDestructiveEffect()` returns `PhaseUnavailable`;
3. worker uses finite `MAX_ATTEMPTS` retry handling;
4. if publication keeps failing through the final attempt, the WorkManager occurrence terminalizes with failure;
5. this `PhaseUnavailable` path does not establish the same exact process-local replay/recovery owner used by `executeJournaledEffect()` failure;
6. accepted pending debt may already have been promoted to active and promotion stops the prior replay owner;
7. `App.onCreate()` runs cleanup reconciliation only once.

Thus D1 can remain durably active/incomplete but have no current-process actor that retries it after the finite WorkManager attempt is terminal. Process restart can rediscover it, but current-process convergence is not guaranteed.

This is the same F10 exact-occurrence durability/liveness root, not a new finding.

## Improvements at f4d00649 that remain accepted and must be preserved

- exact occurrence tuple and frozen Cancelled/Error targets are journaled before intended destructive execution;
- arbitrary body failure no longer resets D1 to ELIGIBLE and re-queries a widened target set;
- incomplete D1 journal blocks D2 publication;
- restart/reconcile retains D1 when journal responsibility is incomplete;
- exact `DOWNLOAD_TEMP` snapshot uses root/path confinement, metadata checks, and live-owner protection;
- exact status/operation/execution/start-time revalidation for cleanup rows occurs inside the Room deletion transaction;
- prior D1/D2 exact successor identity, UNKNOWN WorkManager discovery, disable/supersession fencing, bootstrap recovery design, Reset ordering, and calendar semantics remain expected preserved behavior.

The separate `CLEANUP-STALE-DOWNLOAD-ROW-01` root is not declared closed here even though this implementation incidentally strengthens cleanup-row revalidation; it requires its own exact-root closure review.

## Required next F10 correction boundary

A subsequent F10-only review-fix must, without weakening the accepted journal design:

1. split or otherwise durably represent the Room-deletion and per-item cache-cleanup suffix so process death after Room commit cannot lose frozen filesystem responsibility even when the row no longer exists;
2. propagate exact cache-cleanup success/failure instead of silently treating `deleteIfOwned` failure as completed work;
3. make durability authority robust to Android SharedPreferences' memory-before-disk commit semantics: a `commit()==false` write must not become destructive/scheduling authority merely because the same process can read the new in-memory value;
4. ensure bootstrap/journal/progress first-write failure retains an exact current-process recovery owner through finite WorkManager terminalization and converges when persistence recovers without requiring process restart;
5. prove process death after a memory-visible/disk-failed journal cannot cause a fresh D1 target snapshot after any destructive effect has committed;
6. preserve all earlier F10 exact occurrence, D1->D2, UNKNOWN discovery, disable/supersession, Reset, and calendar semantics.

## Execution evidence

Implementation-side compilation/unit evidence is report-only. New F10 production-wiring instrumentation was not executed because no device/emulator was available. Independent GitHub exact-SHA evidence shows no combined status contexts and no associated workflow runs for `f4d00649`.

INDEPENDENT EXECUTION: NOT EXECUTED