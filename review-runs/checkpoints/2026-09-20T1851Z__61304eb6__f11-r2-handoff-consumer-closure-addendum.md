# F11 completion re-review — R2 consumer-closure addendum

Date: 2026-09-20

Exact final source: `61304eb6f11b10ac66057a1978d5b1f8f75019b0`.

## Disposition

`F11-R2` remains `STILL_OPEN / HIGH` for a second independently confirmed consumer-closure subcase in addition to the previously checkpointed AndroidX Preference auto-persistence gap.

## Ordinary WorkManager handoff carrier writers still bypass the shared admission authority

The remediation report claimed WorkManager handoff carrier mutation paths were covered by `RestoreMutationAdmission`. The exact final source does not establish that for ordinary callers.

Examples:

- `WorkManagerHandoffRecovery.prepareHardSub()` performs point-in-time `RestoreGate` checks and then calls `replaceOutstandingAndInsert(...)` without `RestoreMutationAdmission.withOrdinaryMutation`.
- `prepareSchedulerBoundary()` does the same for SCHEDULE_START / SCHEDULE_END.
- `prepareObserveRetryDownload()` performs repeated point-in-time `RestoreGate` checks around its Room insert, but it likewise does not hold the shared Restore publication/mutation authority across the final write.
- ordinary boundary cancellation paths also remain independent of the shared admission mutex.

`replaceOutstandingAndInsert()` performs more RestoreGate checks immediately before the DAO transaction, but those checks do not serialize with `RestoreMutationAdmission.withRestorePublication`.

## Same canonical race remains

For a scheduler carrier:

ordinary producer checks Restore ALLOWED
→ producer is preempted after the last independent gate check but before its Room transaction
→ Reset acquires shared publication authority and publishes the active pointer
→ Reset Room transaction deletes outstanding SCHEDULE_START/SCHEDULE_END carriers for the targeted Download reset
→ ordinary producer resumes and inserts its carrier after the authoritative Reset mutation
→ restored carrier state contains an ordinary pre-Reset mutation that did not win the shared admission ordering.

For a History Reset, the same pattern applies to HARD_SUB_SCAN because the authoritative Room transaction explicitly deletes outstanding HARD_SUB_SCAN carriers.

For an ObserveSources Reset, stale ordinary Observe-retry carrier publication can similarly race the source/carrier authoritative reset boundary.

These are not theoretical unrelated rows. `RestoreTransactionCoordinator.applyAuthoritativeState()` explicitly mutates the same WorkManager handoff carrier table:

- History reset: `deleteOutstandingForKinds(HARD_SUB_SCAN)`;
- Download-category reset: `deleteOutstandingForKinds(SCHEDULE_START, SCHEDULE_END)`;
- ObserveSources reset: `deleteOutstandingObserveRetryForSourceIds(...)`.

## Why repeated gate checks are insufficient

The canonical R2 review explicitly rejected the strategy of sprinkling additional point-in-time RestoreGate checks. A context switch can occur after the final check and before the Room mutation begins.

The selected R2 contract was:

ordinary final mutation authority wins and completes before active Restore publication
OR
Restore publication wins and the ordinary mutation is rejected/deferred before its durable write.

These ordinary carrier writers do not participate in that shared ordering.

## Required correction boundary

Route the actual ordinary carrier insert/replace/delete mutation boundary through the same `RestoreMutationAdmission` serialization used by Reset publication, while preserving existing boundary locks/generation semantics and avoiding AB/BA lock order.

Do not merely add another RestoreGate check.

Add deterministic production-wiring races where:

- ordinary HARD_SUB or scheduler carrier mutation wins before Reset publication and completes first;
- Reset publication wins and the carrier mutation cannot appear after authoritative carrier cleanup;
- Observe retry carrier publication cannot repopulate a source-reset carrier after Reset wins.

## Canonical reconciliation

- Same F11-R2 root; no new root/count.
- The existing R2 STILL_OPEN checkpoint remains governing and this addendum expands the confirmed consumer set.

INDEPENDENT EXECUTION: NOT EXECUTED
