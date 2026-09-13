# Independent correctness review checkpoint — F10 persistence-failure reconciliation

- implementation review range: `973424909fd97de758b62f639967c8bae7c0bad7..f6e7cf72e00c013ee9b770bf748cba7f38848256`
- exact implementation HEAD verified: `f6e7cf72e00c013ee9b770bf748cba7f38848256`
- prior review/remediation HEAD inspected before this checkpoint: `8456a38ed9acd47e554fc95ec5851a4e61e0c0e9`
- governing finding: F10 / `BUG-CLEANUP-01`
- verdict for F10 at this SHA: `NOT_CLEAN`
- severity/root relation: existing P2 root residual, count delta `0`
- canonical blocker count consequence: unchanged at P0 `2` / P1 `0` / P2 `23`
- contiguous independently CLEAN basis consequence: no advance from `90afaec157607669ea32fa41877e7f0efcdcca86`

## Reconciliation with earlier f6e7cf72 checkpoints

Earlier append-only checkpoints `3e4f0a145349ea6117ded11976392a730b08431e` and `8456a38ed9acd47e554fc95ec5851a4e61e0c0e9` classified the F10 source path as source-semantically fixed. Fresh review against the v6 first-write/non-exception failure rule identified a concrete residual that those checkpoints did not record. This checkpoint therefore narrows/revises only the F10 disposition; it does not create a new semantic root or change the canonical count.

## Exact production evidence

`CleanupScheduleCoordinator.configure()` first commits the new cadence/generation/anchor authority. It then calls `retirePendingDebtLocked(preferences)`. That helper removes the four persisted pending-debt fields using a second `SharedPreferences.Editor.commit()`, stops the process-local replay owner, and returns the second commit result.

If the first authority commit succeeds but the retirement commit returns `false`, `configure()` returns `false` immediately. This occurs before `WorkManager.cancelAllWorkByTag(TAG)` and before any current-generation enqueue. Therefore the new preference authority can be durable while the previously scheduled WorkManager occurrence remains live and no replacement schedule is established.

The production preference listener in `DownloadSettingsFragment` directly returns `CleanupScheduleCoordinator.configure(...)`. A `false` result can reject the UI preference change, but it does not roll back the authority value that `configure()` already committed itself, so it is not a compensation barrier.

`CleanUpLeftoverDownloads.doWork()` does not validate its input generation/cadence against current persisted authority before executing the cleanup body. Generation/cadence validation happens only when publishing the successor. Consequently, on the disable path, an older already-enqueued occurrence left behind by the early return can still execute destructive cleanup even though disable authority was successfully committed; it merely fails to append an old successor afterward. On an enabled cadence supersession, the same early return can strand the new cadence until a later reconciliation opportunity while old work remains scheduled.

This is a concrete violation of the F10 invariants that disable cancels the logical cleanup schedule and supersession retires older scheduling authority. It is also the v6 persistence/non-exception-failure case: a Boolean failure from a required durable write is handled by returning before authority convergence rather than by compensation or durable current-generation recovery.

## Preserved behavior already verified in source

The normal-success retirement path is materially improved: pending debt is cleared, the process-local replay owner is stopped, late enqueue callbacks are tuple-fenced, and stale replay loops re-check exact persisted debt before/after reconciliation. Those paths remain valid and should be preserved by the review fix.

## Required review-fix boundary

The fix must ensure that once the new cadence/generation authority commit succeeds, failure to persist old pending-debt removal cannot leave superseded WorkManager work authoritative or executable as if still current. In particular:

- disable must not leave an older tagged cleanup occurrence able to run cleanup after disable authority won;
- enabled supersession must either establish durable/current-generation scheduling responsibility or fail with an explicit recoverable current-generation state; it must not leave only the old occurrence plus new preference authority;
- stale callback/replay generation fencing already present must be preserved;
- do not weaken the calendar cadence/successor semantics or introduce duplicate successors;
- add deterministic regression coverage for the second persistence write failing after the new authority write has succeeded, including disable and enabled supersession.

No Room migration is implicated by this residual.

INDEPENDENT EXECUTION: NOT EXECUTED
