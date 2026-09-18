# F10 successor-debt fallback failure: assertion observes incidental pending slot before initial acceptance settles

## Authoritative implementation state

- Repository: `ireum-0/ytdlnisx`
- Implementation branch: `checkpoint/pre-baseline-review`
- Authoritative remote HEAD independently re-verified as exactly:
  `aa50a500a47263f704914f0960543d6e92ff3aff`
- Expected parent:
  `f869315196b222f6919832181e1f98c29a7ccdfc`
- No implementation push occurred.

Protected local-only test chain:

1. `192e7d15f794493e3c361ec8cb8fdc4535cdfabe`
2. `a0cc16a755ad31deeaf4104397e0678ecc7dfb4a`
3. `711e914a9060671da5c96fb3c639b1342be50cbe`
4. `e76a4bcd07ed5c2d56ac71a95d767c431b410d67`
5. `7d72ce5ab6ccaa8835efcb8196720fcf9ad19054`
6. `eaf4d3058504f13dfe63099f0a01c88b99dc12cd`
7. `c0d0da802cdb0cbfab8fd33fdc0dde024a1573e2`
8. `3dfa9993b441a00d9afd06111ac9e61b29c58e60`
9. `c2159c213025ea347e2d5d6393cbc3cb2f7ee030`
   - parent `3dfa9993...`
   - `test: quiesce missing-generation bootstrap fixture`
   - `Defect-ID: BUG-CLEANUP-01`

All remain local-only/non-authoritative until normally pushed.

## Reported verification on local-only c2159c21

Implementation-agent evidence:

- static/build checks PASS
- missing-generation focused Stage 1: PASS 1/1
- Stage 2: PASS 1/1
- Stage 3: PASS 1/1
- full `CleanupScheduleCoordinatorProductionWiringTest`:
  - 69 discovered
  - 69 executed
  - 0 skipped
  - 1 failure
- first/only reported failure:
  `successorDebtPersistenceFailureUsesLiveFallbackUntilItCanPersist`
- failure assertion:
  expected `pending_generation == null`
  but observed generation UUID
  `3269f115-8c4f-4ecb-a091-b53f5b8732e0`
- no push occurred.

The local-only c2159c21 diff is not GitHub-authoritative. Its reported fixture correction and focused PASS results remain implementation-agent evidence until published.

## Exact-source test precondition at authoritative remote aa50a500

The failing test currently:

1. freezes `nowProviderForTesting`;
2. sets an initial delay and fast replay delays;
3. calls real `CleanupScheduleCoordinator.configure(context, DAILY)`;
4. reads generation/anchor;
5. asserts only `unfinishedCurrentWork().size == 1`;
6. computes predecessor occurrence;
7. calls raw test helper `markCurrentEffectConsumed(predecessorAt)`;
8. installs `authorityCommitOverrideForTesting = { false }`;
9. calls `scheduleSuccessor(...)`;
10. asserts that the call returned false;
11. immediately asserts raw `pending_generation == null`.

That precondition does **not** prove the initial DAILY request's asynchronous enqueue-acceptance callback has promoted the predecessor from pending to active.

Initial configure uses an asynchronous WorkManager enqueue Operation and `observeAcceptance(...)`, whose listener runs on the Android main executor. The test's observation that one unfinished WorkInfo exists is not equivalent to coordinator pending->active promotion completion.

## Exact-source production behavior on successor persistence failure

`scheduleSuccessor(...)` computes exact successor debt and calls:

`persistSuccessorDebtLocked(preferences, successor, completedOccurrenceAt)`.

That helper first reads the current predecessor from:

- active debt if present;
- otherwise pending debt.

When the predecessor matches and its exact effect is complete, it attempts one critical authority commit replacing predecessor debt with successor pending debt.

If that commit fails:

- the commit does not publish successor debt;
- the durability fence retains the previously confirmed predecessor state;
- `scheduleSuccessor(...)` calls `ensureReplayOwnerLocked(appContext, successorDebt)`;
- the process-local fallback is allowed when the exact durable predecessor is either pending or active and its effect is complete.

Therefore a failed successor persistence attempt is required to preserve the exact predecessor durable responsibility. It is **not** required to make the raw pending slot null.

If initial enqueue acceptance has already promoted the predecessor, raw pending may be null and active carries predecessor.

If acceptance has not yet promoted it, raw pending may legitimately still contain the predecessor generation/occurrence.

The observed UUID therefore does not by itself show that the successor was persisted. The decisive identity is occurrence/debt identity, not whether the incidental pending slot is empty.

## Why the current assertion is semantically wrong

The assertion:

`assertNull(preferences.getString(PREF_PENDING_GENERATION, null))`

conflates two distinct claims:

1. “the successor was not durably published” — semantic contract;
2. “the predecessor happened to already be in active rather than pending” — asynchronous observer state.

Only (1) is required.

The test should instead prove exact identities:

- capture predecessor exact debt/occurrence before injecting the successor persistence failure;
- after the failed commit, prove successor occurrence is absent from both durable pending and active slots;
- prove the exact predecessor debt remains the durable carrier in one valid slot;
- prove no mixed pending+active impossible state is created;
- after clearing the failure seam, prove the live fallback eventually publishes/schedules the exact successor and retires the predecessor without widening/duplicating responsibility.

This aligns with Checklist v6 asynchronous acceptance semantics and recovery exact-identity rules.

## Related regression-contract audit required

Protocol Section 4.1 now requires affected regression-contract inventory in the same wave when the contract is already established.

Audit the materially related successor persistence/replay tests in the same class, especially tests that:

- call real `configure(...)` / enqueue;
- then immediately raw-read or raw-mutate pending/active debt;
- rely only on WorkInfo existence rather than exact coordinator acceptance/promotion;
- use `markCurrentEffectConsumed(...)` while treating pending-vs-active slot placement as semantically fixed;
- infer “successor persisted/not persisted” from slot emptiness rather than exact occurrence identity.

Known nearby candidates include the successor discovery/persistence/replay group around:
- `failedPredecessorClearAndSuccessorWriteAdvanceOnlyExactSuccessor`;
- `terminalWorkerRetainsExactSuccessorAcrossReplayOwnerLoss`;
- `successorDiscoveryFailurePersistsExactDebtBeforeQueryAndRecovers`;
- `successorDebtPersistenceFailureUsesLiveFallbackUntilItCanPersist`;
- `startupRecoveryDebtFailureRetainsCurrentProcessOwnership`;
- `restartReconstructsReplayOwnerForUnmatchedSuccessorDebt`.

Do not mechanically edit all of them. Classify each as still-valid vs materially dependent on an unproved slot/acceptance assumption and only correct the affected ones.

## Classification

Current failure:

**instrumentation assertion-observability / async-acceptance precondition residual**.

Production successor persistence/fallback semantics are not implicated by the current evidence.

Do not change production successor logic, durability fencing, fallback selection, or replay behavior from this result.

If a corrected exact-identity test establishes a valid predecessor state and still shows the exact successor durably published despite the forced authority commit returning false, or shows predecessor responsibility lost with no valid fallback recovery, STOP and report possible production residual.

## F10 disposition

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-HARNESS-RESIDUAL / EXECUTION-NOT-VERIFIED`

- new canonical production root: NO
- count delta: 0
- canonical totals remain P0=2 / P1=0 / P2=24
- Overall remains NOT_CLEAN
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`
- F11 remains blocked.

INDEPENDENT EXECUTION: NOT EXECUTED
