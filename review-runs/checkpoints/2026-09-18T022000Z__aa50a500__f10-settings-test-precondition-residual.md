# F10 settings commit failure: test precondition residual, production settings contract remains fixed

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

Both are local-only/non-authoritative until normally pushed.

## Reported verification on local-only a0cc16a

Implementation-agent evidence:

- diff checks: PASS
- `:app:compileDebugKotlin -x lint`: PASS
- `:app:compileDebugAndroidTestKotlin`: PASS
- `effectPhasePublicationFailureDoesNotRunCleanupBeforeRetry`: PASS 1/1
- `memoryVisibleEffectJournalFailureCannotGrantCleanupAuthority`: PASS 1/1
- full coordinator class discovered 69
- first material failure at case 39:
  `settingsCommitFailureLeavesPreviousDurableCadenceVisible`
- assertion: expected `daily`, actual `null`, around line 2640
- XML observed additional shutdown cases/failures after stop; those are not a complete semantic full-class result.

## Exact-source analysis at authoritative remote aa50a500

The test currently does:

1. write `cleanup_leftover_downloads = daily` only to `legacyPreferences`;
2. install `authorityCommitOverrideForTesting = { false }`;
3. create `CleanupSchedulePreferenceController`;
4. request `weekly`;
5. correctly wait until the UI callback applies `daily`;
6. then assert that the dedicated critical `preferences` store itself contains `daily`.

This mixes two different persistence states.

### Test lifecycle precondition

The class `setUp()`:

1. resolves `preferences = criticalPreferencesForTesting(context)`;
2. clears seams;
3. calls `CleanupScheduleCoordinator.configure(context, null)`;
4. later calls `clearSchedulePreferences()`.

`clearSchedulePreferences()` clears the dedicated critical preferences file and removes legacy cleanup keys.

Therefore, at the start of this test, writing `daily` only to `legacyPreferences` does **not** establish a durable initialized dedicated critical-store cadence.

### What the injected failure actually blocks

`CleanupScheduleCoordinator.configure(context, weekly)` first calls `criticalPreferencesOrNull(appContext)`.

Because the dedicated store is uninitialized/cleared, that function tries to migrate the legacy values into the dedicated store through `commitCriticalSnapshot(...)`.

But the test has already installed:

`authorityCommitOverrideForTesting = { false }`

so the migration commit itself fails.

Unlike the separate memory-visible failure tests, this test does not enable `commitFailureAppliesMemoryForTesting`; therefore the failed migration does not publish `daily` into the dedicated store even process-locally.

The dedicated `preferences.getString("cleanup_leftover_downloads", null)` consequently remains `null`.

### Why the UI-visible contract still returns daily

After the failed weekly request, `CleanupSchedulePreferenceController` calls:

`CleanupScheduleCoordinator.currentCadenceForSettings(appContext)`.

That method:

- uses the dedicated critical store when it can be initialized/resolved;
- otherwise falls back to the legacy/default `cleanup_leftover_downloads` value.

Since migration is still being forced to fail and the legacy store contains `daily`, the controller correctly applies `daily` to the UI callback.

Thus the observed sequence:

- UI callback sees `daily`;
- raw dedicated store remains `null`

is exactly what the current test setup requests.

## Classification

This is an **instrumentation test-precondition defect**, not a production settings/durability residual.

The test name/intent is:

`settingsCommitFailureLeavesPreviousDurableCadenceVisible`

To test that contract, the previous `daily` cadence must first be established durably through the coordinator/dedicated critical store **before** the weekly commit failure seam is installed.

The correct fix is not to weaken the final assertion to accept null.

Instead, establish a genuine durable previous `daily` coordinator state first, then inject failure for the later DAILY -> WEEKLY authority transition and prove:

- controller callback remains `daily`;
- dedicated/current coordinator authority remains `daily`;
- failed `weekly` does not become durable/current authority.

A narrow setup may use the real coordinator path (for example configure/reconcile as appropriate) before installing the failure override. Do not bypass production persistence by raw-writing the dedicated store unless exact existing test conventions require that for this boundary.

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

`aa50a500...` -> `192e7d15...` -> `a0cc16a...`

Run one test-only forward follow-up:

1. verify remote remains exact `aa50a500...`;
2. verify local HEAD exact `a0cc16a...`, parent exact `192e7d15...`, grandparent exact `aa50a500...`;
3. preserve both local-only commits without rewrite;
4. correct only the `settingsCommitFailureLeavesPreviousDurableCadenceVisible` precondition so `daily` is genuinely durable in coordinator-owned critical state before the weekly failure seam is installed;
5. preserve the strong expected `daily` behavior; do not change it to null;
6. audit only materially identical settings/controller tests for the same “legacy-only seed after dedicated reset but assert dedicated authority” precondition mistake;
7. production source remains out of scope;
8. create one new forward test-only commit with `Defect-ID: BUG-CLEANUP-01`;
9. run the focused settings test against the exact new committed SHA;
10. only on focused PASS run the full coordinator class exactly once;
11. on first valid failure/hang preserve diagnostics and stop without push;
12. only on nonzero full-class execution with zero failures push the exact tested three-commit local chain.

INDEPENDENT EXECUTION: NOT EXECUTED
