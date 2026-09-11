# Independent correctness review final checkpoint

Timestamp (UTC): 2026-09-11T20:42:00Z

## Frozen exact basis
- implementation: `checkpoint/pre-baseline-review@9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- plan/master-plan: `plan/remediation@fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review bootstrap: `review/remediation@7cc586926628b613985a3109af638c9c2789a3dc`
- ledger: `ledger/remediation@899328bc91e4008e39a658387396a0106c8666ec`
- v6 checklist blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Review-complete scope
- Fresh-fetch and SHA freeze completed.
- Latest v6 checklist applied as execution checklist; Master Plan/governance used for invariant, severity, root reconciliation, and gate.
- Current production source re-traced for the two governance deltas since the preceding review; no diff-only verdicting.
- `BUG-TERMINAL-05` independently reproduced from persisted Terminal intent through WorkManager carrier establishment and startup recovery.
- `BUG-FORMAT-01` independently reproduced from format extraction through Result/Download publication, per-item outcome handling, progress, final WorkManager result, and notification.
- Adjacent Terminal execution/publication recovery and WorkManager handoff recovery were opened to distinguish downstream/existing recovery from the missing pre-execution Terminal handoff carrier.
- Frozen dependency versions confirmed: WorkManager 2.11.0, Room 2.8.4, kotlinx-coroutines 1.10.2.
- Frozen SHA status evidence checked: commit statuses 0, check-runs 0, workflow runs for the exact SHA 0.
- Final branch recount performed: implementation, plan, ledger unchanged. Review branch advanced only by this run's append-only checkpoint commits after the frozen review bootstrap.

## Final severity inventory
- P0: 2
- P1: 1
- P2: 33
- overall gate: NOT_CLEAN

Material delta versus the preceding user-visible run: P2 `31 -> 33` due to existing historical `BUG-TERMINAL-05` and `BUG-FORMAT-01` being promoted into the current canonical blocker inventory and independently reproduced. No brand-new canonical root was discovered in this run.

## Findings / disposition
### BUG-TERMINAL-05
- class: EXISTING historical root, newly promoted
- severity: P2
- disposition: OPEN / CONFIRMED
- violated invariant: durable intent must retain a recoverable exact execution-carrier responsibility across async enqueue acceptance/failure and process death; async request is not completion.
- evidence: Room Terminal row commits before detached scheduling; WorkManager enqueue Operation is discarded; no ordinary Terminal handoff carrier exists; startup execution/publication recovery begins downstream of this gap.

### BUG-FORMAT-01
- class: EXISTING historical root, newly promoted
- severity: P2
- disposition: OPEN / CONFIRMED
- violated invariant: per-item failure/cancellation and multi-ledger publication must remain truthful; sibling isolation does not permit silently success-labelling a failed item; persistence outcomes may not be discarded.
- evidence: inner `runCatching` Result is ignored, count/progress advance after failure, final success remains reachable, Result and Download writes are separate durable operations, success notification can include failed ids.

## Confirmed fixed invariants / non-regressions
- No evidence in the reviewed paths reopens closed `BUG-TERMINAL-04`; its execution/publication recovery is downstream and remains distinct.
- Existing current-state refresh before the bulk-format Download write remains present, but it does not close `BUG-FORMAT-01`'s truthful-outcome/atomic-publication root.

## Open candidates/questions
- None requiring a new finding in this run.
- Actual independent device/Gradle execution was not performed; runtime manifestations beyond source semantics remain NOT_VERIFIED where applicable.

## Remaining review scope
- None for this frozen run. Later production or governance changes require a new exact-SHA run.

## Exact upstream semantic basis
- AndroidX WorkManager 2.11.0 in frozen source. Official API contract: enqueue returns an `Operation` that determines when enqueue has completed; initiating enqueue without observing the Operation does not prove accepted/completed handoff.
- Kotlin stdlib `runCatching`: catches any `Throwable` from its block and returns failure; an ignored Result suppresses the semantic distinction rather than propagating it.
- kotlinx-coroutines 1.10.2 in frozen source: detached/viewModelScope coroutine lifetime is not durable scheduling acceptance.
- Room 2.8.4 in frozen source: distinct DAO calls are not made atomic by proximity; no enclosing worker-level transaction covers Result + Download publication here.
- yt-dlp upstream behavior is not material to either confirmed root.

INDEPENDENT EXECUTION: NOT EXECUTED.
