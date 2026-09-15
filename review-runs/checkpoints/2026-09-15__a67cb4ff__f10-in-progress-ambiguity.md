# Independent correctness review — F10 in-progress ambiguity residual

- exact_implementation_sha: `a67cb4ff8a367f3a4261eb816d4abfe1290b8e4f`
- review_range: `a5160ab51dbe3c6f8d5f87853c4e4037469b9684..a67cb4ff8a367f3a4261eb816d4abfe1290b8e4f`
- verified_chain: `a5160ab5 -> b19feef7 -> a67cb4ff`
- frozen_plan_sha: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen_ledger_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: v6 commit `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`, blob `7b553328dfcd9941d783658f49ecb16c71b98c56`
- review_parent_sha: `25a554d1768f8d3cdd09a6e384a89d915eeeace4`

## Verdict

`BUG-CLEANUP-01` remains `OPEN P2 / NOT_CLEAN`.

No canonical root-count delta. Counts remain `P0 2 / P1 0 / P2 22`.

The preceding final checkpoint at `25a554d1...` already establishes one still-open same-root residual: an arbitrary production-body `Exception` can occur after an earlier destructive subeffect committed, yet `withCurrentDestructiveEffect()` durably resets the same occurrence from `IN_PROGRESS` to `ELIGIBLE`; WorkManager retry can then recompute a broader fresh target set. This checkpoint preserves that decision and records an additional same-root recovery subcase that the preceding checkpoint did not spell out.

## Additional same-root residual — ambiguous `IN_PROGRESS` can permanently skip D1 work

At the final implementation SHA, the coordinator persists `IN_PROGRESS` before invoking the cleanup body. That state therefore has at least three semantically different meanings:

1. process death/cancellation occurred after the `IN_PROGRESS` commit but before the first destructive subeffect started;
2. one or more destructive subeffects committed but later subeffects did not complete;
3. the full cleanup body completed but the final `CONSUMED` phase commit did not complete.

The durable phase does not distinguish these cases.

`withCurrentDestructiveEffect()` treats persisted `IN_PROGRESS` (and `UNKNOWN`) as `AlreadyConsumed(recoveryRequired = true)`, so a re-entering worker skips the destructive body. `CleanUpLeftoverDownloads.doWork()` then proceeds to `scheduleSuccessor()` for the same D1 tuple. Startup/reconcile logic also treats a non-`ELIGIBLE` active occurrence as a reason to reconstruct the immediate successor owner rather than reconstructing the unfinished D1 effect.

Consequently, if process death occurs immediately after `IN_PROGRESS` is durably committed but before `deleteCancelled()` begins, restart can skip D1 cleanup entirely and advance to D2. If death occurs after only some destructive subeffects, the unfinished suffix can likewise be abandoned. This violates the already-recorded F10 requirement that unfinished subeffects remain recoverable rather than falsely skipped.

The added production-wiring test `restartWithInProgressEffectPhaseSkipsBodyAndRecoversExactSuccessor()` currently encodes this unsafe collapse as expected success: it seeds `in_progress`, asserts the cleanup body runs zero times, and waits for the exact successor occurrence. The test therefore demonstrates the residual rather than closing it.

## Existing partial-effect retry residual remains open

The production body still performs, in order, roughly:

`deleteCancelled()` -> `LowQualityRedownloadLedger.refresh(...)` -> `deleteErrored()` -> refresh -> temp cleanup.

An exception after an earlier destructive commit enters the generic non-cancellation catch in `withCurrentDestructiveEffect()`. If the phase-reset commit succeeds, the occurrence is returned to `ELIGIBLE` and the worker keeps the bounded `Result.retry()` behavior. No frozen D1 target/progress journal or equivalent exact subeffect carrier prevents the next attempt from discovering newly eligible rows.

Therefore both unsafe directions remain possible under the single coarse phase:

- reset to `ELIGIBLE` can repeat/widen a partially committed D1;
- retain `IN_PROGRESS` can skip an unstarted or partially unfinished D1.

A single `ELIGIBLE / IN_PROGRESS / CONSUMED` bit cannot resolve the ambiguity without additional exact durable progress/target semantics.

## Source-level preserved closures at `a67cb4ff`

The cumulative range does preserve the previously reviewed scheduling corrections unless contradicted by the residual above:

- exact occurrence identity now participates in destructive admission (`generation`, `cadence`, monthly anchor, occurrence time);
- fully `CONSUMED` D1 re-entry is fenced from re-running the destructive body;
- stale D1 cannot re-enter after D2 has replaced its exact occurrence ownership;
- pending -> active promotion carries the effect phase instead of silently resetting it;
- mixed simultaneous pending+active durable slots fail closed for destructive admission;
- successor debt still derives from the exact predecessor occurrence/calendar identity;
- failed durable scheduling transitions remain success-aware and retain replay responsibility;
- WorkManager discovery failure remains non-authoritative;
- disable/supersession still commits new authority before asynchronous cancellation and fences stale generations;
- DAILY/WEEKLY/MONTHLY calendar semantics and monthly-anchor behavior remain unchanged.

These source-level preserved properties do not close F10 while the partial/ambiguous effect carrier remains unsafe.

## Required review-fix boundary

Do not try to choose between `ELIGIBLE` replay and `IN_PROGRESS` skip based on a coarse occurrence phase alone.

The next F10 correction must provide exact durable/reconstructible destructive-effect responsibility so that, across exception, cancellation, and process death:

- D1 never discovers targets that were not within D1's authority after any D1 destructive subeffect has committed;
- an already committed subeffect is not repeated merely because a later sidecar/subeffect failed;
- an unstarted or unfinished D1 subeffect remains discoverable and recoverable rather than being silently abandoned in favor of D2;
- restart can distinguish enough progress/authority to resume or safely complete the same D1 responsibility;
- cache/filesystem cleanup retains defensible exact authority after earlier Room deletion;
- D2 publication occurs only after D1's exact destructive responsibility is semantically complete or durably handed to an equivalent exact recovery carrier;
- all prior D1/D2 scheduling, UNKNOWN-discovery, calendar, disable/supersession, and no-duplicate closures remain intact.

A frozen target journal, granular subeffect journal/claims, or another narrow F10-internal durable representation is acceptable; no particular storage design is preselected. The proof obligation is semantic, not the shape of the implementation.

## Execution evidence

The implementation-agent report claims compile/unit verification passed, but F10 production-wiring instrumentation was not executed because no device was available. Independent GitHub inspection found no commit status contexts and no associated workflow runs for `a67cb4ff`.

No tests were executed independently by this reviewer.

## Basis / next action

- `CLEAN_REVIEW_BASIS` remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- F10 remains the blocking implementation target.
- Do not start F11; its hard prerequisite F10 is not independently closed.
- `CLEANUP-STALE-DOWNLOAD-ROW-01` remains a separate open P2 and is not merged into this F10 root.

INDEPENDENT EXECUTION: NOT EXECUTED