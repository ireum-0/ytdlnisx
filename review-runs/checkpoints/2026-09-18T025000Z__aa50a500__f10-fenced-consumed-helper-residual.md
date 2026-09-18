# F10 exact-successor replay timeout: fenced raw-consumed test precondition residual

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

All remain local-only/non-authoritative until normally pushed.

## Reported verification on local-only 711e914a

Implementation-agent evidence:

- `git diff --check`: PASS
- committed-range diff check: PASS
- `:app:compileDebugKotlin -x lint`: PASS
- `:app:compileDebugAndroidTestKotlin`: PASS
- focused `settingsCommitFailureLeavesPreviousDurableCadenceVisible`: PASS 1/1
- full coordinator class discovered 69
- first material failure at 42/69:
  `failedPredecessorClearAndSuccessorWriteAdvanceOnlyExactSuccessor`
- failure: `TimeoutCancellationException` after 5000 ms waiting for expected successor debt transition
- logcat showed request `ed4139d0-aac9-41ce-bbe2-a8be3867bbd0` scheduled and later cancelled during teardown
- no WorkInfo attempt/state or durable pending/active diagnostics were emitted before timeout
- XML shutdown observations are not a complete semantic full-class result.

## Exact-source analysis at authoritative remote aa50a500

The failing test intends to prove that after a predecessor transition/successor publication failure, the replay owner advances only the exact successor once the predecessor effect is complete.

Its exact sequence is:

1. configure DAILY with a controlled enqueue operation;
2. install `authorityCommitOverrideForTesting = { false }`;
3. complete the controlled enqueue operation;
4. wait for the predecessor to remain in the pending slot;
5. call raw test helper `markCurrentEffectConsumed(predecessorAt)`;
6. call `scheduleSuccessor(...)` and expect failure;
7. restore authority commits and remove enqueue override;
8. hide predecessor WorkInfo through `workInfoQueryOverrideForTesting = { emptyList() }`;
9. wait for durable debt to advance to the exact successor.

### The key durability-fence interaction

When the controlled enqueue operation succeeds, `observeAcceptance(...)` calls `promoteAcceptedDebt(...)`.

That calls `promotePendingDebtLocked(...)`, whose pending-to-active transition uses `commitAuthority(...)`.

Because the test has already installed:

`authorityCommitOverrideForTesting = { false }`

the promotion commit fails.

`commitCriticalSnapshot(...)` then establishes `criticalDurabilityFence = confirmedBefore`, so coordinator reads continue to use the last confirmed durable pending state.

At that point the test calls:

`markCurrentEffectConsumed(predecessorAt)`

but that helper directly executes:

`preferences.edit().putString(..., "consumed").commit()`

outside the coordinator critical-write path.

Raw SharedPreferences therefore contains `consumed`, but coordinator reads such as `readEffectPhase`, `criticalString`, and `criticalLong` continue to use the active `criticalDurabilityFence`.

From the coordinator's authoritative view the predecessor effect remains `ELIGIBLE`, not `CONSUMED`.

### Why replay never advances

`scheduleSuccessor(...)` calls `persistSuccessorDebtLocked(...)`.

Before attempting the successor authority commit it checks:

`isEffectCompleteForOccurrenceLocked(preferences, predecessor)`.

Because the durability fence still exposes the previous `ELIGIBLE` phase, the method returns false and successor publication stops before the intended authority-write failure boundary.

`ensureReplayOwnerLocked(..., successorDebt)` cannot repair this later because:

`isSchedulingDebtCurrent(expectedSuccessor)`

requires the pending predecessor to have `isEffectCompleteForOccurrenceLocked(...) == true`.

The active fence still says ELIGIBLE, so replay exits without advancing debt. The test's five-second timeout is therefore expected from its own precondition mismatch.

The request later being cancelled during teardown does not establish a production successor-replay defect; the durable successor condition was never made true in the coordinator authority model.

## Existing safe test boundary

The class already exposes:

`CleanupScheduleCoordinator.seedEffectJournalForTesting(...)`

which persists effect journal/phase through `commitEffectPhase(...)` and therefore through the coordinator's real `commitCritical(...)` / durability-fence machinery.

A structurally valid, complete `CleanupEffectJournal` for the exact predecessor, persisted with phase `"consumed"`, can establish the intended completed-predecessor precondition through the coordinator-owned critical write path.

Because `effectPhaseCommitOverrideForTesting` is not failed in this test, that effect-phase commit can succeed and clear the prior durability fence. The later `scheduleSuccessor(...)` call can then reach the intended authority-commit failure and replay boundary.

The generic raw `markCurrentEffectConsumed()` helper is not automatically wrong in every use. Its other observed uses mark consumed before a durability fence is introduced. The narrow defect here is using it **after** a failed critical authority commit has activated the fence.

## Classification

This is an **instrumentation test-precondition/helper-authority defect**, not a confirmed production successor-replay residual.

Do not change:
- production replay ownership;
- `criticalDurabilityFence`;
- `persistSuccessorDebtLocked`;
- `isSchedulingDebtCurrent`;
- retry/replay timing merely to satisfy this timeout.

The test must first establish the predecessor effect as durably complete through the same coordinator critical authority model that production uses.

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

`aa50a500...` -> `192e7d15...` -> `a0cc16a...` -> `711e914a...`

Run one test-only forward follow-up:

1. verify remote remains exact `aa50a500...`;
2. verify local HEAD exact `711e914a9060671da5c96fb3c639b1342be50cbe`, parent exact `a0cc16a...`, grandparent chain exact;
3. preserve all prior local-only commits without rewrite;
4. in `failedPredecessorClearAndSuccessorWriteAdvanceOnlyExactSuccessor`, replace the post-fence raw consumed-state injection with a coordinator-owned durable effect-completion setup;
5. prefer `seedEffectJournalForTesting` with an exact, structurally valid, complete predecessor journal and phase `"consumed"`, or an equivalent existing coordinator test boundary that performs the actual critical effect-phase commit;
6. prove after that setup that the coordinator itself observes predecessor completion before exercising the failed successor authority write;
7. do not globally rewrite `markCurrentEffectConsumed()` unless an audit proves another use has the same active-fence precondition;
8. production source remains out of scope;
9. create one new forward test-only commit with `Defect-ID: BUG-CLEANUP-01`;
10. run the focused exact-successor replay test against the exact new commit;
11. only on focused PASS run the full coordinator class exactly once;
12. on first valid failure/hang preserve durable pending/active/effect-phase/replay diagnostics before teardown and stop without push;
13. only on nonzero full-class execution with zero failures push the exact tested four-commit local chain.

INDEPENDENT EXECUTION: NOT EXECUTED
