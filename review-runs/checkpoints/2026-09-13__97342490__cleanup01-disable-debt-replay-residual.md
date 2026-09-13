# Independent correctness review — F10 / BUG-CLEANUP-01

- Reviewed implementation head: `973424909fd97de758b62f639967c8bae7c0bad7`
- Reviewed range for this wave: `a95868357ddd70ac990a79026daf8a571db1917b..973424909fd97de758b62f639967c8bae7c0bad7`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Verdict: **P2 OPEN / NOT_CLEAN**
- Root: `BUG-CLEANUP-01`
- Count delta: `0`
- Resulting canonical count at this checkpoint: **P0 2 / P1 0 / P2 25**
- CLEAN-basis consequence: no advance; remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Confirmed progress

The implementation adds a generation-bound, process-lifetime replay owner for pending cleanup scheduling debt, bounded exponential backoff, current-debt matching, WorkManager enqueue acceptance observation, and full test-seam cleanup. This closes the prior same-process no-owner residual for an enabled current generation under ordinary transient enqueue failure.

The reported exact-final external evidence includes the complete `CleanupScheduleCoordinatorProductionWiringTest` class passing 12/12 in one invocation. This is implementation-agent execution evidence, not independent reviewer execution.

## Remaining semantic residual

Disable does not invalidate/clear the surviving pending scheduling-debt carrier or stop its replay owner.

Concrete production sequence:

1. An enabled cadence commits current cadence/generation and generation-bound pending scheduling debt.
2. Enqueue fails, so the replay owner remains active for that pending tuple.
3. The user disables cleanup via `configure(context, null)`.
4. `configure` commits an empty cadence and a new generation, cancels WorkManager work, then returns `true`; it does not clear the existing `PREF_PENDING_*` tuple and does not cancel the corresponding replay job.
5. The replay loop tests whether the pending tuple still equals its expected old debt. Because that tuple remains unchanged, the test succeeds even though current cadence/generation authority has been superseded by disable.
6. The loop invokes `reconcile`. `reconcile` observes the disabled cadence, cancels work and returns, but does not clear the stale debt or stop the replay owner.
7. The process-lifetime replay loop therefore continues waking/backing off against stale disabled-generation debt.

This violates the F10 invariant that disable cancels the schedule and invalidates stale scheduling authority/recovery ownership. A disabled cadence must not retain an active replay owner for superseded debt.

This is a residual of the existing `BUG-CLEANUP-01` root, not a new canonical root.

## Checkpoint precedence

The scheduled checkpoint `2fc7b020633f62995fa715762592d124663463ca` assessed F10 source as clean. This focused exact-source review supersedes that F10 conclusion because it follows the disable -> pending durable debt -> replay-owner path that the scheduled checkpoint did not close. The scheduled checkpoint remains evidence for other inspected facts but does not control F10 disposition.

Required review-fix boundary: on disable or supersession, stale pending debt and its process-local replay ownership must be invalidated/retired without permitting stale acceptance/replay to clear or recreate newer authority. Add deterministic coverage for disable while old debt replay is pending.

INDEPENDENT EXECUTION: NOT EXECUTED