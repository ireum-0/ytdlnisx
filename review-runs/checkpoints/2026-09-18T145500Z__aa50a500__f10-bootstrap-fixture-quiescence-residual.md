# F10 bootstrap-owner full-suite failure: missing-generation fixture lacks async quiescence

## Authoritative implementation state

- Repository: `ireum-0/ytdlnisx`
- Implementation branch: `checkpoint/pre-baseline-review`
- Authoritative remote HEAD independently re-verified through GitHub as exactly:
  `aa50a500a47263f704914f0960543d6e92ff3aff`
- Parent:
  `f869315196b222f6919832181e1f98c29a7ccdfc`
- No implementation push occurred.

The implementation agent reported that its final local `git ls-remote` attempt failed because of GitHub connectivity, but the independent GitHub branch lookup succeeded and confirms the remote is still `aa50a500...`.

Protected local-only test chain:

1. `192e7d15f794493e3c361ec8cb8fdc4535cdfabe`
2. `a0cc16a755ad31deeaf4104397e0678ecc7dfb4a`
3. `711e914a9060671da5c96fb3c639b1342be50cbe`
4. `e76a4bcd07ed5c2d56ac71a95d767c431b410d67`
5. `7d72ce5ab6ccaa8835efcb8196720fcf9ad19054`
6. `eaf4d3058504f13dfe63099f0a01c88b99dc12cd`
7. `c0d0da802cdb0cbfab8fd33fdc0dde024a1573e2`
8. `3dfa9993b441a00d9afd06111ac9e61b29c58e60`
   - parent `c0d0da80...`
   - `test: retain cleanup gate through settings reset assertion`
   - `Defect-ID: BUG-CLEANUP-01`

All remain local-only/non-authoritative until normally pushed.

## Reported verification on local-only 3dfa9993

Implementation-agent evidence:

- diff/build checks PASS
- focused `downloadSettingsResetWaitsForAdmittedCleanupWithoutBlockingUi`: PASS 1/1
- corrected test proves admitted cleanup remains held before release, keeps DAILY authority, then completes reset after explicit release
- full coordinator class discovered 69
- first valid failure at 53/69:
  `startupMissingGenerationCommitFailureRetainsCurrentProcessBootstrapOwner`
- failure at the bounded wait for replay-created generation
- termination artifact reported 54 observed tests / 1 failure / 0 skipped; not a complete semantic full-class pass.

## Exact-source bootstrap replay contract

At authoritative remote `aa50a500...`, the missing-generation startup path in `reconcileSuspending(...)`:

1. observes enabled cadence with no generation;
2. creates or reuses a `BootstrapRecoveryIntent`;
3. tries one atomic authority commit that publishes generation + initial pending debt;
4. if that commit fails:
   - stores `replayBootstrapIntent`;
   - starts `ensureBootstrapReplayOwnerLocked(...)`;
5. that owner retries `reconcileSuspending(...)` with bounded backoff while the same cadence remains enabled and generation remains absent.

The relevant test seams are `@Volatile`, including:
- `authorityCommitOverrideForTesting`;
- replay delay overrides.

Therefore changing `authorityCommitOverrideForTesting` from `{ false }` to `null` is visible to the IO replay coroutine.

On a later successful authority commit, `commitCriticalSnapshot(...)` rebuilds from the last confirmed critical snapshot, performs the real editor commit, clears the durability fence, and atomically publishes generation plus initial debt.

Exact production source therefore contains a valid same-process recovery path for the scenario the test intends to exercise.

## The unstable test fixture

All missing-generation startup tests call:

`seedInitializedScheduleWithMissingGeneration()`.

At authoritative remote that helper currently does:

1. write legacy DAILY;
2. call `CleanupScheduleCoordinator.reconcile(context)`;
3. immediately call `workManager.cancelAllWork().result.get(...)`;
4. raw-remove generation, anchor, pending, active, phase, and journal keys from the dedicated critical store;
5. call `CleanupScheduleCoordinator.resetReplayOwnerForTesting()`.

The initial `reconcile(context)` is not a purely synchronous seed operation.

It may:
- enqueue an initial WorkRequest;
- register `observeAcceptance(...)` on the enqueue Operation;
- maintain a process-local scheduling replay owner until acceptance/promotion converges.

`observeAcceptance(...)` is dispatched through the Android main executor.

The helper does not first prove that the enqueue acceptance callback and the associated pending->active/replay-owner transition have completed before it tears down the generated authority.

`cancelAllWork().result.get(...)` proves WorkManager cancellation completion; it does **not** prove that the already-registered coordinator acceptance callback/main-executor work and process-local replay transition have quiesced.

`resetReplayOwnerForTesting()` calls `stopReplayOwnerLocked()`, which cancels the current replay job but does not join/await its completion.

Thus the helper constructs its next semantic precondition:

“enabled dedicated critical store, but no generation/debt and no prior process-local scheduling actor”

without actually establishing the “no prior process-local actor/callback still in flight” portion.

This violates the review protocol's async-quiescence rule and is material specifically to tests that immediately rely on a newly-created bootstrap replay owner.

## Why this matches the current full-suite-only failure

The failing test itself:

1. calls the non-quiescent helper;
2. installs fast bootstrap replay delays;
3. forces the first bootstrap authority commit to fail;
4. confirms no generation/debt was published;
5. removes the commit-failure seam;
6. expects the newly-created bootstrap owner to publish generation/debt within 5 seconds.

If old seed-time acceptance/replay activity is still crossing the fixture boundary, the test has not isolated the lifetime of the bootstrap owner it is trying to prove.

The report does not include focused reproduction or replay-owner diagnostics, so the exact runtime interleaving is not independently proven. But the missing quiescence in the shared fixture is exact-source observable and must be corrected before treating this full-suite-only failure as a production bootstrap-replay residual.

## Safe fixture correction

Do not simply increase the 5-second generation wait.

Make `seedInitializedScheduleWithMissingGeneration()` first establish a quiescent initialized DAILY coordinator state before stripping generation/debt.

A preferred boundary is:

1. seed/reconcile DAILY;
2. capture the generated exact generation;
3. boundedly wait until the enqueue acceptance transition is fully reflected in coordinator state (for example the expected generation is in the active slot with pending cleared, or an equivalent exact accepted/quiescent state);
4. after observing that state, drain the main executor with the existing bounded `awaitMainLooperIdle()` so the `observeAcceptance` callback that produced that state has returned through its replay-owner cleanup;
5. cancel WorkManager work and await cancellation completion;
6. verify no unfinished WorkManager item remains;
7. only then raw-remove generation/debt/journal to create the intended missing-generation fixture;
8. reset the process-local replay owner;
9. establish/verify the post-seed fixture invariants before returning:
   - dedicated cadence remains DAILY;
   - generation is absent;
   - pending/active debt absent;
   - no unfinished WorkManager item.

Do not use an arbitrary sleep.

If a different exact quiescence boundary is used, it must prove both WorkManager and coordinator callback/replay activity from the seed generation have completed before the fixture returns.

## Sibling coverage

The same fixture is shared by at least:

- `startupMissingGenerationCommitFailureDoesNotPublishNewAuthorityOrDebt`;
- `startupMissingGenerationCommitFailureRetainsCurrentProcessBootstrapOwner`;
- `memoryVisibleBootstrapFailureIsFencedUntilSameProcessRecovery`.

Because the fixture changes, all relevant shared-fixture semantics should be focused-verified, with stop-on-first-valid-failure ordering.

## Classification

Current failure is classified:

**instrumentation fixture async-quiescence residual / production bootstrap replay NOT YET implicated**.

This is stronger than merely calling the test flaky: the fixture has a concrete missing lifecycle boundary.

Do not modify production bootstrap replay, durability fencing, or replay backoff from the current evidence.

If the quiescent fixture is established and a focused same-process bootstrap-owner test still fails to publish generation/debt after the failure seam is removed, that would reopen this boundary as a possible production residual.

## F10 disposition

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-HARNESS-RESIDUAL / EXECUTION-NOT-VERIFIED`

- new canonical production root: NO
- count delta: 0
- canonical totals remain P0=2 / P1=0 / P2=24
- Overall remains NOT_CLEAN
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`
- F11 remains blocked.

INDEPENDENT EXECUTION: NOT EXECUTED
