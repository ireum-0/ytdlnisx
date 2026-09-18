# F10 exact-successor replay: overbroad WorkInfo seam and async-observation residual

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
   - parent `aa50a500...`
   - `test: isolate cleanup successor harness across suite`
2. `a0cc16a755ad31deeaf4104397e0678ecc7dfb4a`
   - parent `192e7d15...`
   - `test: commit successful cleanup effect-phase retries`
3. `711e914a9060671da5c96fb3c639b1342be50cbe`
   - parent `a0cc16a...`
   - `test: establish durable cadence before settings failure`
4. `e76a4bcd07ed5c2d56ac71a95d767c431b410d67`
   - parent `711e914a...`
   - `test: persist predecessor completion through cleanup authority`

All remain local-only/non-authoritative until normally pushed.

## Reported verification on local-only e76a4bcd

Implementation-agent evidence:

- diff/build checks PASS
- focused `failedPredecessorClearAndSuccessorWriteAdvanceOnlyExactSuccessor` executed 1 / failed 1 / skipped 0
- the test now:
  - observes the failed pending->active authority commit;
  - persists a complete predecessor journal through `seedEffectJournalForTesting(..., phase = "consumed")`;
  - verifies the effect-phase editor commit succeeds;
  - verifies `scheduleSuccessor()` reaches exactly one additional authority commit attempt.
- the focused failure occurs only after the durable successor predicate became true:
  `assertEquals(1, unfinishedCurrentWork().size)`
  expected 1, actual 0.
- full class was not run.
- no rerun-to-green occurred.

## Exact-source analysis

At authoritative remote `aa50a500...`, after the intended failed successor authority write the test does:

`workInfoQueryOverrideForTesting = { emptyList() }`

with the comment that this models the predecessor no longer being discoverable.

The seam is global to coordinator WorkManager discovery:

`queryCurrentWork(workManager) = workInfoQueryOverrideForTesting?.invoke() ?: realQuery`

Therefore this override does not hide only the predecessor. It hides every WorkManager item from the coordinator, including a newly-created successor.

### Replay behavior with the blanket empty override

The replay owner for the exact successor executes:

1. `isSchedulingDebtCurrent(expectedSuccessor)`;
2. `isSchedulingDebtPersistedOrRepaired(expectedSuccessor)`;
3. `reconcileSuspending(context)`.

Once predecessor completion is correctly established, step 2 can durably advance the pending debt to the exact successor.

Then `reconcileSuspending()` calls `queryCurrentWork(workManager)`.

Because the test override permanently returns an empty list:

- `current` is always an empty non-null list;
- the coordinator never observes the successor it may have just enqueued;
- with pending successor debt and no observed unfinished work, reconciliation repeatedly enters the recovery path;
- that path retains the exact debt, calls `cancelAllWorkByTag(TAG)`, and issues a replacement enqueue for the same recovery occurrence;
- subsequent replay iterations still receive an empty list from the test seam and can repeat this recovery cycle.

Thus the test creates an artificial world in which coordinator discovery can never observe its own recovery successor.

### Async observation boundary

Even without the overbroad seam, the test's final assertion is too early.

In recovery, the coordinator durably publishes/retains recovery debt before it calls `enqueueNextLocked(...)`.

WorkManager enqueue is asynchronous and returns an `Operation`.

Therefore this sequence is valid:

1. durable successor debt becomes visible;
2. enqueue has been requested but its WorkManager row is not yet observable;
3. an immediate external `unfinishedCurrentWork()` query returns 0;
4. the enqueue operation completes shortly afterward.

The test currently waits only for the durable debt predicate and then immediately snapshots WorkManager state.

That does not establish the promised WorkManager successor.

### WorkManager ordering check

The project uses `androidx.work:work-runtime-ktx:2.11.0`.

Upstream WorkManager implementation dispatches both cancellation-by-tag and unique-work enqueue operations through its serial task executor. The exact production call order `cancelAllWorkByTag(TAG)` followed by enqueue is therefore not, by itself, evidence of an unordered cancellation/enqueue race.

Do not change production cancellation/replay logic from the current evidence.

## Classification

This focused failure is an **instrumentation WorkInfo-seam / asynchronous-observation defect**, not a confirmed production replay residual.

Two harness corrections are required together:

1. model “predecessor no longer discoverable” without permanently hiding the successor from coordinator discovery;
2. after durable successor transition, boundedly await the exact successor WorkManager item rather than performing an immediate count snapshot.

## Required narrow semantics

The WorkInfo seam should hide only the intended stale predecessor condition.

Safe options include a stateful/selective seam that:
- returns a discovery result without the predecessor for the recovery decision;
- allows later coordinator queries to observe the exact successor;
- or delegates to real WorkManager discovery while filtering only the predecessor occurrence identity.

Do not leave `{ emptyList() }` active indefinitely.

When verifying final convergence:
- await the exact `occurrenceTag(generation, expectedSuccessorAt)`;
- require that exact successor to be unfinished/current;
- require no unfinished predecessor occurrence;
- require exactly one logical relevant successor;
- use bounded polling over real WorkManager state.

Do not merely sleep or immediately snapshot a count.

## F10 disposition

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-HARNESS-RESIDUAL / EXECUTION-NOT-VERIFIED`

- new canonical root: NO
- count delta: 0
- canonical totals remain P0=2 / P1=0 / P2=24
- Overall remains NOT_CLEAN
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`
- F11 remains blocked.

## Next narrow boundary

Preserve local-only chain:

`aa50a500...` -> `192e7d15...` -> `a0cc16a...` -> `711e914a...` -> `e76a4bcd...`

Run one test-only forward follow-up:

1. verify remote remains exact `aa50a500...`;
2. verify local HEAD exact `e76a4bcd07ed5c2d56ac71a95d767c431b410d67` and complete parent chain;
3. preserve all prior local-only commits without rewrite;
4. in `failedPredecessorClearAndSuccessorWriteAdvanceOnlyExactSuccessor`, replace the permanent global-empty WorkInfo seam with a predecessor-specific or stateful discovery seam that allows the exact successor to become observable;
5. after durable successor transition, boundedly await the exact expected successor WorkInfo/tag and predecessor absence instead of immediately asserting `unfinishedCurrentWork().size == 1`;
6. retain the already-correct proof that predecessor completion is coordinator-durable and the intended successor authority commit is actually reached/rejected;
7. audit materially identical uses of `workInfoQueryOverrideForTesting = { emptyList() }` only for the same “hide one old occurrence but later expect new work to be discoverable” semantic mismatch;
8. production source remains out of scope;
9. create one new forward test-only commit with `Defect-ID: BUG-CLEANUP-01`;
10. run the focused exact-successor test against exact new committed SHA;
11. only on focused PASS run the full coordinator class exactly once;
12. on first valid failure/hang preserve durable debt plus exact WorkInfo/operation diagnostics and stop without push;
13. only on nonzero full-class execution with zero failures push the exact tested five-commit local chain.

INDEPENDENT EXECUTION: NOT EXECUTED
