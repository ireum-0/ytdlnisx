# F10 completed-wave reconciliation — 2026-09-15 — `a5160ab5`

## Authority

- Implementation branch: `checkpoint/pre-baseline-review`
- Review base: `2bcffa78116aa086c645f029f8abeaef0d51b659`
- Reviewed completed head: `a5160ab51dbe3c6f8d5f87853c4e4037469b9684`
- Verified chain: exactly one straight commit, `2bcffa78 -> a5160ab5`, merge base exactly `2bcffa78`, ahead 1 / behind 0.
- Commit: `fix: close cleanup scheduling recovery gaps`
- Defect-ID: `BUG-CLEANUP-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: Review Checklist v6 (`4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`).
- Prior F10 completed-wave checkpoint: `9849c65852bb989d451149fa29f926911a699aae`.

## Verdict

**F10 / `BUG-CLEANUP-01`: NOT_CLEAN — existing P2 root remains OPEN.**

This wave source-level closes the three previously recorded A/B/C residuals, but full-scope review found another same-root retry/re-entry frontier after a successful destructive effect and before durable worker terminalization.

## Previously recorded residual A — source-level fixed

Missing-generation startup bootstrap now retains a process-local bootstrap replay owner when the atomic generation/anchor/initial-debt commit returns false.

The owner retries reconciliation while the exact legacy enabled cadence remains current, and disable/supersession stops the owner. A successful bootstrap transitions into the ordinary exact scheduling-debt recovery path.

Disposition: **source-level CLOSED at `a5160ab5`**, subject to the still-open broader F10 root and unexecuted instrumentation.

## Previously recorded residual B — source-level fixed

Download Settings Reset no longer lets the generic preference reset write cleanup cadence authority directly.

The production Reset path now:

1. invalidates any pending cleanup preference transition;
2. asynchronously awaits `CleanupScheduleCoordinator.configure(appContext, null)`;
3. only after that durable coordinator-owned disable succeeds, resets the remaining Download Settings preferences;
4. excludes `cleanup_leftover_downloads` from the generic reset, preserving coordinator ownership of that key;
5. recreates the Activity only after the coordinated disable/reset sequence.

The coordinator disable still participates in the same destructive-effect mutex and generation/debt retirement protocol.

Disposition: **source-level CLOSED at `a5160ab5`**, subject to the still-open broader F10 root and unexecuted instrumentation.

## Previously recorded residual C — narrow handoff-failure path source-level fixed

After a destructive cleanup effect succeeds, successor-handoff failure no longer causes `CleanUpLeftoverDownloads` to return `Result.retry()` solely for that scheduling failure.

Instead, the worker completes successfully with handoff-pending/stale output while the coordinator retains exact predecessor/successor durable/replay responsibility. This prevents the specific prior sequence in which a scheduling-only failure directly asked WorkManager to rerun `deleteCancelled()`, `deleteErrored()`, and temp cleanup.

Disposition of the previously recorded **successor-only handoff failure -> WorkManager retry -> repeated successful cleanup** path: **source-level CLOSED at `a5160ab5`**.

## Newly confirmed same-root residual — successful effect can still re-enter before durable terminalization

The broader once-per-occurrence destructive-effect boundary is still not durable.

Production sequence:

1. A scheduled occurrence D1 is current under the durable generation/cadence authority.
2. `CleanUpLeftoverDownloads.doWork()` enters `CleanupScheduleCoordinator.withCurrentDestructiveEffect(...)` and successfully performs the destructive cleanup body.
3. No durable occurrence-level `effect completed` / consumed phase is written before leaving that effect.
4. If the process dies or the worker otherwise re-enters before WorkManager has durably recorded D1's terminal success, D1 remains/reappears as unfinished WorkManager work.
5. Startup reconciliation can still preserve D1 as the current active unfinished occurrence; there is no durable evidence saying its destructive effect already completed.
6. On D1 re-entry, `withCurrentDestructiveEffect` / `isCurrentOccurrenceLocked` validates only generation and cadence. It does **not** validate the worker's exact occurrence timestamp or an effect phase.
7. Therefore the same D1 can enter the destructive body again.

There is an even later equivalent window: if D1 already published durable pending successor D2 but the process dies before D1's terminal result is durably accepted by WorkManager, a re-entered D1 still shares the same generation/cadence as D2. The current admission predicate can therefore authorize the stale predecessor's destructive body even though durable scheduling ownership has advanced to D2.

Concrete incorrect impact:

- the first D1 pass may successfully delete the then-current Cancelled/Error rows and temp cache;
- rows that become Cancelled/Error after that successful pass but before D1 re-entry can be deleted by the second D1 pass;
- that second destructive pass is caused by retry/re-entry of an already-consumed calendar occurrence rather than by the next scheduled occurrence.

This is the same `BUG-CLEANUP-01` durable recurring-schedule / retry identity root, not a new blocker root. Count delta: `0`.

### Required correction boundary

Before a successful destructive occurrence can be replayed, the implementation needs a durable/reconstructible occurrence-phase barrier that distinguishes at least:

- exact occurrence whose destructive effect is still eligible to run;
- exact occurrence whose destructive effect has already been consumed/completed enough that retry/restart must perform handoff/recovery only, not rerun the destructive body.

The correction must cover process death/restart and late same-generation predecessor re-entry, not only explicit `Result.retry()` returned by current code. Exact generation + cadence alone is insufficient once multiple occurrence phases exist within one generation.

The design must preserve:

- exact D1/D2 occurrence identity and calendar cadence;
- existing pending/active durable scheduling ownership and failed-write recovery;
- startup reconciliation and process-local replay;
- disable/supersession fencing;
- no duplicate successor;
- cleanup-failure retry semantics where the destructive effect genuinely did not complete;
- nonblocking settings transitions and Reset ordering;
- destructive-effect mutex ordering.

Focused production-wiring coverage should deterministically model successful destructive effect followed by restart/re-entry before terminal success, including the case where successor D2 is already durable but predecessor D1 re-enters. The assertion must prove the destructive body runs once for D1 while handoff/recovery still converges to exactly D2.

## Preserved prior D1/D2 scheduling ownership

No source regression was found in the prior D1/D2 scheduling-debt fixes:

- accepted pending occurrence promotion to durable active ownership remains present;
- failed promotion retains recovery responsibility;
- exact successor publication atomically advances durable occurrence ownership;
- failed successor publication retains recoverable predecessor/successor responsibility;
- restart still derives an exact successor from durable predecessor identity;
- stale predecessor scheduling callbacks remain fenced from overwriting newer occurrence debt;
- UNKNOWN WorkManager discovery remains non-authoritative;
- calendar daily/weekly/monthly semantics remain present.

The newly confirmed residual is specifically the destructive-effect consumption phase, not a reopening of those scheduling-debt closures.

## Execution evidence

Implementation-agent evidence reports:

- `git diff --check`: PASS;
- committed-range diff check: PASS;
- `:app:compileDebugKotlin -x lint`: PASS;
- `:app:compileDebugAndroidTestKotlin`: initial test-source compile failure, corrected rerun PASS;
- new F10 production-wiring tests: ADDED, NOT EXECUTED;
- F10 instrumentation: NOT EXECUTED — DEVICE UNAVAILABLE;
- existing F20 instrumentation: NOT EXECUTED — DEVICE UNAVAILABLE.

GitHub has no combined status checks and no workflow runs for exact SHA `a5160ab5...`. The reviewer did not independently execute builds or instrumentation.

Review Checklist v6 also requires actual executed production-wiring/integration evidence for blocker-relevant material semantic-contract changes before CLEAN. That execution gate remains unmet independently of the source residual above.

## Count / basis

- F10 remains one existing P2 root; count delta: `0`.
- Canonical blockers remain **P0 2 / P1 0 / P2 21**.
- Overall: `NOT_CLEAN`.
- Contiguous independently CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- F11 / `BUG-BACKUP-03` remains blocked and must not begin.
- F20 remains source-fixed / execution-not-verified and was not modified in this wave.

INDEPENDENT EXECUTION: NOT EXECUTED