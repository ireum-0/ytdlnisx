# F10 final cleanup failure: stale regression contract, exact predecessor recovery must remain authoritative

## Authoritative implementation state

- Repository: `ireum-0/ytdlnisx`
- Implementation branch: `checkpoint/pre-baseline-review`
- Authoritative remote HEAD remains exactly:
  `aa50a500a47263f704914f0960543d6e92ff3aff`
- Parent remains:
  `f869315196b222f6919832181e1f98c29a7ccdfc`
- No implementation push occurred.

Protected local-only test chain:

1. `192e7d15f794493e3c361ec8cb8fdc4535cdfabe`
2. `a0cc16a755ad31deeaf4104397e0678ecc7dfb4a`
3. `711e914a9060671da5c96fb3c639b1342be50cbe`
4. `e76a4bcd07ed5c2d56ac71a95d767c431b410d67`
5. `7d72ce5ab6ccaa8835efcb8196720fcf9ad19054`
   - parent `e76a4bcd...`
   - `test: scope successor discovery seam to predecessor`
   - `Defect-ID: BUG-CLEANUP-01`

All remain local-only/non-authoritative until normally pushed.

## Reported verification on local-only 7d72ce5a

Implementation-agent evidence:

- `git diff --check`: PASS
- committed-range diff check: PASS
- `:app:compileDebugKotlin -x lint`: PASS
- androidTest compile initially failed due a missing local helper reference, was corrected, then PASS
- focused `failedPredecessorClearAndSuccessorWriteAdvanceOnlyExactSuccessor`: PASS 1/1
- full coordinator class:
  - 69 discovered
  - runner reported 69 executed / 1 failure / 0 skipped before Gradle collection stop
  - first material failure:
    `finalCleanupFailurePreservesFutureOccurrenceAndReportsFailure`
  - timeout after 140000 ms
  - last observed work:
    `a0cd4188-9d1a-4a1c-9f07-c75dcc0d7820:ENQUEUED/attempt=2`
- no further correction or rerun occurred.

## Exact-source contract at authoritative remote aa50a500

The failing test currently configures immediate DAILY work, makes `cleanupOverrideForTesting` throw on every execution, and then waits for all of the following:

1. cleanup attempts reach `CleanUpLeftoverDownloads.MAX_ATTEMPTS`;
2. some future occurrence is ENQUEUED;
3. some work item is SUCCEEDED with `cleanup_failure=true`;
4. exactly one unfinished request remains.

That expected terminal model no longer matches the accepted exact-journal recovery contract.

### Production effect behavior

With `cleanupOverrideForTesting != null`, `prepareCleanupEffectJournal()` creates an exact occurrence journal.

`withCurrentDestructiveEffect(...)` marks that exact occurrence IN_PROGRESS and executes the effect through `executeJournaledEffect(...)`.

When the effect throws:

- `executeJournaledEffect(...)` does **not** reset the occurrence to ELIGIBLE;
- it retains the exact journal as the restart/retry carrier;
- it calls `ensureReplayOwnerLocked(...)`;
- it converts the arbitrary body exception into `EffectPhaseRecoveryRequired`.

The worker catches that typed failure and sets:

- `cleanupEffectIncomplete = true`;
- `cleanupEffectRecoveryRequired = true`;
- `cleanupFailure = recovery cause`.

It then takes the branch:

`if (cleanupEffectIncomplete || !cleanupEffectConsumed)`

and therefore:

- retries while `runAttemptCount < MAX_ATTEMPTS - 1`;
- at exhaustion returns `Result.failure(...)` with
  `cleanup_schedule_failure=true`,
  `cleanup_failure=true`,
  and `cleanup_effect_recovery_required=true`.

Crucially, it returns from this branch **before `scheduleSuccessor(...)`**.

### Why skipping to a future occurrence would be incorrect

The exact predecessor journal remains IN_PROGRESS/incomplete. It is the durable carrier for unfinished destructive responsibility.

Publishing D2 over that state would violate the accepted F10 recovery invariant by abandoning or skipping D1 responsibility.

The coordinator reconciliation/replay path explicitly distinguishes this case:

- terminal/restarted predecessor + complete effect -> successor recovery;
- terminal/restarted predecessor + incomplete exact journal -> recover/requeue the **same exact predecessor occurrence**.

Therefore the desired post-exhaustion behavior is not “D1 failed permanently, so advance to D2”.

It is:

“D1 request reports terminal failure for that WorkManager attempt, while exact D1 recovery responsibility remains durable/discoverable and replay can re-establish D1 without widening or duplicating the destructive target set.”

## Master Plan / checklist compatibility

Pinned Master Plan F10 says:

- successful run schedules next only if cadence token matches;
- retry must not create duplicate successor;
- startup/reconciliation preserves exactly one correct logical schedule.

It does not require an incomplete/failed cleanup occurrence to advance to the next calendar occurrence.

Checklist v6 additionally requires unresolved authoritative journals/sidecars to remain exact, discoverable recovery responsibility rather than being hidden behind terminal success or skipped by a later occurrence.

Thus the current production contract is consistent with the governing recovery model.

## Stale test expectation

The test name and assertions:

`finalCleanupFailurePreservesFutureOccurrenceAndReportsFailure`

still encode an older “final cleanup failure can report success while preserving a future occurrence” model.

Under current exact-journal semantics, a body exception before the journal becomes complete cannot reach the worker's later “success with cleanup_failure sidecar after successor acceptance” branch.

The test predicate is therefore structurally incompatible with the intended current semantics and can time out indefinitely while WorkManager continues retry/recovery activity.

The observed `ENQUEUED/attempt=2` at timeout is consistent with a retry/recovery state and is not evidence that a future occurrence should have existed.

## Classification

This is an **instrumentation stale-regression-contract defect**, not a confirmed production cleanup/replay residual.

Do not change production to schedule a successor over an incomplete exact cleanup journal.

Do not weaken the exact journal/recovery barrier.

## Required test contract

Replace this stale regression with a test of the current intended contract.

The corrected test should prove, using exact occurrence identity:

1. cleanup effect begins for D1;
2. deterministic cleanup body failure leaves D1 exact journal IN_PROGRESS and incomplete;
3. WorkManager retries the same D1 within the retry budget;
4. no D2/future occurrence becomes authoritative while D1 responsibility is incomplete;
5. at retry exhaustion, the specific D1 WorkRequest reaches FAILED and reports:
   - `cleanup_schedule_failure=true`;
   - `cleanup_failure=true`;
   - `cleanup_effect_recovery_required=true`;
6. durable coordinator state still identifies D1 as the unresolved exact responsibility (pending or active as contract permits);
7. replay/reconciliation preserves or requeues **exact D1**, not D2;
8. no duplicate/widened D1 responsibility is created;
9. the frozen journal remains the exact recovery carrier.

Because the process-local replay owner may quickly recover/requeue D1 after the terminal request fails, the test must distinguish:
- the original request's terminal FAILED result by exact UUID;
- the durable D1 debt/journal;
- any later exact-D1 recovery request.

Do not infer terminal semantics from broad unique-work counts.

A long arbitrary timeout waiting for a future D2 must be removed.

## F10 disposition

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-HARNESS-RESIDUAL / EXECUTION-NOT-VERIFIED`

- new canonical production root: NO
- count delta: 0
- canonical totals remain P0=2 / P1=0 / P2=24
- Overall remains NOT_CLEAN
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`
- F11 remains blocked.

## Next narrow boundary

Preserve local-only chain through `7d72ce5a...`.

Run one forward test-only follow-up:

1. verify authoritative remote remains exact `aa50a500...`;
2. verify local HEAD exact `7d72ce5ab6ccaa8835efcb8196720fcf9ad19054` and its complete parent chain;
3. preserve all local-only commits without rewrite;
4. replace/reframe only the stale `finalCleanupFailurePreservesFutureOccurrenceAndReportsFailure` contract;
5. do not change production;
6. ensure the corrected test proves original D1 FAILED output and exact D1 recovery ownership, while explicitly proving D2 is not published over an incomplete journal;
7. use exact request UUID/occurrence tags and bounded observation;
8. audit only materially identical tests that still expect successor publication after an incomplete/recovery-required cleanup body failure;
9. create one new forward test-only commit with `Defect-ID: BUG-CLEANUP-01`;
10. run focused corrected test;
11. only on focused PASS run full coordinator class exactly once;
12. on first valid failure/hang preserve exact request/debt/journal/replay diagnostics and stop without push;
13. only on nonzero full-class execution with zero failures push the exact tested six-commit local chain.

INDEPENDENT EXECUTION: NOT EXECUTED
