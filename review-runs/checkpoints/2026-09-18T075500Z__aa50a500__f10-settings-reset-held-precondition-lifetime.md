# F10 settings-reset admitted-cleanup failure: self-expiring test hold releases authority gate

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
6. `eaf4d3058504f13dfe63099f0a01c88b99dc12cd`
7. `c0d0da802cdb0cbfab8fd33fdc0dde024a1573e2`
   - parent `eaf4d305...`
   - `test: use WorkInfo attempt-count semantics`
   - `Defect-ID: BUG-CLEANUP-01`

All remain local-only/non-authoritative until normally pushed.

## Reported verification on local-only c0d0da80

Implementation-agent evidence:

- `git diff --check`: PASS
- committed-range diff check: PASS
- `:app:compileDebugKotlin -x lint`: PASS
- `:app:compileDebugAndroidTestKotlin`: PASS
- focused `finalCleanupFailureRetainsExactOccurrenceForRecovery`: PASS 1/1
- focused proof included:
  - RETRY -> RETRY -> FAILURE
  - terminal FAILED
  - terminal WorkInfo runAttemptCount 3
  - cleanup attempts 3
  - required failure flags
  - incomplete exact D1 journal retained
  - exact D1 recovery preserved
  - D2 excluded
- full coordinator class discovered 69
- first valid failure:
  `downloadSettingsResetWaitsForAdmittedCleanupWithoutBlockingUi`
- assertion: expected dedicated critical cadence `daily`, observed empty
- a second failure surfaced during termination but was not investigated under stop rule.

## Exact-source production synchronization contract

At authoritative remote `aa50a500...`:

`CleanupScheduleCoordinator.configure(context, cadence)`

executes inside:

`destructiveEffectMutex.withLock { ... }`

and commits cadence authority only while holding that mutex.

`CleanupScheduleCoordinator.withCurrentDestructiveEffect(...)`

also executes the admitted destructive cleanup body inside the same:

`destructiveEffectMutex.withLock { ... }`.

Therefore an already-admitted cleanup owns the gate from admission through its effect body. A concurrent `configure(context, null)` cannot commit the disabled/empty cadence until that cleanup releases the mutex.

The production comment in `DownloadSettingsFragment.resetDownloadingPreferences(...)` matches this behavior: the screen reset runs `configure(appContext, null)` on lifecycle-owned background work, allowing the UI callback to return while the coordinator waits for the admitted cleanup.

The generic downloading-preference reset runs only after `configure(null)` returns true and explicitly excludes `cleanup_leftover_downloads`.

`CleanupSchedulePreferenceController.cancelPendingRequest()` only invalidates/cancels an outstanding preference transition job. It does not write cleanup cadence authority.

Thus exact production source contains no path in this reset flow that should set the dedicated critical cadence empty while the admitted cleanup effect still owns the mutex.

## Exact-source test hold

The authoritative regression test currently installs:

`CleanUpLeftoverDownloads.cleanupOverrideForTesting = {`
`    cleanupStarted.countDown()`
`    check(releaseCleanup.await(10, TimeUnit.SECONDS)) {`
`        "cleanup effect did not release"`
`    }`
`}`

The scenario then performs all of the following before calling `releaseCleanup.countDown()`:

- waits for the worker to enter cleanup;
- launches `SettingsActivity`;
- navigates to `DownloadSettingsFragment`;
- executes pending fragment transactions;
- clicks the reset preference;
- drives the confirmation dialog through Espresso;
- only then asserts that the critical cadence is still `daily`.

The test's “hold the admitted cleanup” precondition therefore self-expires after 10 seconds.

If the activity/navigation/Espresso path takes more than 10 seconds in a full-class run:

1. `releaseCleanup.await(10s)` returns false;
2. the test seam throws;
3. the cleanup body exits and `destructiveEffectMutex` is released;
4. the already-launched asynchronous reset's `configure(null)` can acquire the mutex;
5. it durably commits the disabled/empty cadence;
6. by the time the test reaches its pre-release assertion, the dedicated critical store correctly reads empty.

That exact sequence produces the reported `expected daily, actual empty` without any production mutex violation.

The current report does not independently timestamp the seam expiry, so the specific runtime timing is implementation-agent/runtime evidence rather than independently observed here. However, the self-expiring 10-second precondition is exact-source observable and is sufficient to make the test semantically unstable in a full UI/instrumentation sequence.

## Classification

This is an **instrumentation held-precondition lifetime defect**, not a confirmed production reset/cleanup synchronization residual.

Do not change:
- `destructiveEffectMutex`;
- `configure(null)`;
- `DownloadSettingsFragment.resetDownloadingPreferences`;
- the asynchronous UI reset design;
- cleanup authority semantics.

## Required test correction

The test must hold the admitted cleanup until the test itself explicitly releases it, with a bounded outer guard that cannot silently become the semantic release event before the intended assertion.

The correction should:

1. retain a deterministic `cleanupStarted` admission signal;
2. retain an explicit `releaseCleanup` controlled by the test;
3. give the hold a guard lifetime that dominates the test's own bounded UI scenario, or use a suspendable test gate whose lifetime is bounded by the surrounding focused-test timeout;
4. record a separate `cleanupExited` / equivalent signal in `finally`;
5. immediately before asserting the pre-release cadence, prove the cleanup seam has **not exited**;
6. assert dedicated critical cadence is still DAILY;
7. then explicitly release cleanup;
8. boundedly await the reset transition and assert cadence becomes disabled/empty;
9. assert pending/active generation state is cleared as the existing contract requires.

Do not merely increase the timeout and leave the test unable to tell whether the cleanup hold self-expired.

Do not use an arbitrary sleep as the proof that the reset is waiting.

## Narrow audit

Other 10-second latch uses exist in the same class, but they are not automatically defective.

Audit only cases where:
- the latch is supposed to hold a semantic precondition across a potentially long Activity/fragment/Espresso/async scenario; and
- expiry of the latch would itself release the very gate/state the later assertion assumes is still held.

Short, immediately-controlled concurrency tests where the latch is released directly after a bounded callback/transition assertion need not be rewritten merely because they also use 10 seconds.

## F10 disposition

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-HARNESS-RESIDUAL / EXECUTION-NOT-VERIFIED`

- new canonical production root: NO
- count delta: 0
- canonical totals remain P0=2 / P1=0 / P2=24
- Overall remains NOT_CLEAN
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`
- F11 remains blocked.

## Next narrow boundary

Preserve local-only chain through `c0d0da80...`.

Run one test-only forward follow-up:

1. verify authoritative remote remains exact `aa50a500...`;
2. verify local HEAD exact `c0d0da802cdb0cbfab8fd33fdc0dde024a1573e2` and complete ancestry;
3. preserve all seven local-only commits without rewrite;
4. correct only the held-cleanup precondition in `downloadSettingsResetWaitsForAdmittedCleanupWithoutBlockingUi`;
5. require explicit proof that cleanup remains inside the seam immediately before the DAILY assertion;
6. explicitly release cleanup only after that proof/assertion;
7. then boundedly verify the reset completes and cadence becomes empty;
8. audit only materially identical self-expiring held-precondition seams;
9. production files remain out of scope;
10. create one new forward-only test commit with `Defect-ID: BUG-CLEANUP-01`;
11. run diff/build checks;
12. run the focused settings-reset test against exact new committed SHA;
13. only on focused PASS run the full coordinator class exactly once;
14. on first valid failure/hang preserve cleanup-entered/exited/release state, critical cadence, reset job progress, WorkInfo, and teardown timing; stop without push;
15. only on nonzero full-class execution with zero failures push the exact tested eight-commit local chain.

INDEPENDENT EXECUTION: NOT EXECUTED
