# F10 / BUG-CLEANUP-01 — independent re-review of d769351f

Reviewed implementation branch: `checkpoint/pre-baseline-review`
Review range: `5b6c5ed3dd8be0b525cb2790c5ac999601fb597b..d769351fcf7a4a0353b036bf095aa3036ba0e8a6`
Review head: `d769351fcf7a4a0353b036bf095aa3036ba0e8a6`
Verdict: `NOT_CLEAN`
Canonical root: `BUG-CLEANUP-01 / F10 / P2 / OPEN`
Canonical count delta: `0`
CLEAN basis consequence: unchanged at `90afaec157607669ea32fa41877e7f0efcdcca86`

## Exact Git state

Remote `checkpoint/pre-baseline-review` was independently verified identical to exact `d769351fcf7a4a0353b036bf095aa3036ba0e8a6`.

Straight chain from the prior completed head:

1. `7afbf6ce79fa2758ffc32651bbf99fc05caafafe`
   - parent `5b6c5ed3dd8be0b525cb2790c5ac999601fb597b`
   - `fix: bind cleanup cache roots across path changes`
   - `Defect-ID: BUG-CLEANUP-01`
2. `d769351fcf7a4a0353b036bf095aa3036ba0e8a6`
   - parent `7afbf6ce79fa2758ffc32651bbf99fc05caafafe`
   - `test: strengthen cleanup cache-root migration coverage`
   - `Defect-ID: BUG-CLEANUP-01`

Compare from 5b6c5ed3 is ahead 2 / behind 0.

## Accepted source improvements

Preserve these corrections from this wave:

- `DownloadCacheOwnership` has a dedicated durable per-operation/execution root-binding store.
- `DownloadWorker` binds the canonical cache root before `prepareAttempt` may establish/reuse the exact marker.
- new cleanup journals persist `CacheBinding(downloadId, rootPath)` entries rather than re-resolving mutable `cache_path` during cache suffix retry.
- `advanceExactCacheCleanup` invokes the exact helper with the journaled root, so post-journal R1 -> R2 preference changes cannot make R2 absence prove R1 completion.
- root bindings survive process-local reset/restart discovery and are only discovery hints; actual deletion still requires exact marker/manifest ownership.
- the ordinary cache-path picker captures valid pre-binding existing markers before changing `cache_path`, allowing an older unbound R1 carrier to become discoverable.
- the failed critical-store migration Android test now correctly treats the rejected migration target as process-visible, distinguishes the durable image on simulated restart, and includes an unrelated default-preference write.
- the previously accepted dedicated critical store, full critical-namespace rewrite, typed cache cleanup results, retry-safe marker/manifest carrier, D1/D2 ordering, exact Room target revalidation, Reset ordering for cleanup cadence, and recurring schedule semantics remain source-preserved.

## OPEN P2 subcase — Folder-settings Reset bypasses the cache-path transition capture contract

The remediation changes the semantic contract of `cache_path`: changing away from a root that may contain a valid exact marker requires the old root to be durably discoverable before the preference transition.

The direct folder-picker path follows that contract. `FolderSettingsFragment.changePath(..., CACHE_PATH_CODE)` resolves the current root and calls `DownloadCacheOwnership.captureOwnedRootsForPathTransition(...)`; on capture failure it refuses the preference change.

However the same screen has another real production writer of `cache_path`: `reset_preferences` invokes `resetPreferences(editor, R.xml.folders_preference)` directly.

`BaseSettingsFragment.resetPreferences(...)` removes every preference key on the screen (unless explicitly excluded), calls `editor.apply()`, and then calls `PreferenceManager.setDefaultValues(..., true)`. `folders_preference.xml` defines `cache_path` with default `""`. On screen recreation `FolderSettingsFragment.onCreatePreferences()` detects empty `cache_path` and writes `FileUtil.getCachePath(...)`, which resolves the app default root. No `captureOwnedRootsForPathTransition` call occurs on this Reset path.

This matters for the migration state the new test deliberately supports. The final test `preJournalCacheRootDiscoveryUsesRegisteredOldRootNotCurrentPreference` creates a valid carrier with `prepareAttempt(rootOne, target)` **without** a Context, explicitly modeling a marker created before durable root binding was introduced, and relies on settings-transition capture to register R1 before changing to R2.

Concrete failure sequence:

1. custom `cache_path` is R1;
2. a valid exact marker + manifest + owned files for frozen Cancelled/Error target X exist at R1, but the carrier predates root-binding registration (the migration case now explicitly supported by the test);
3. no cleanup journal has captured X yet;
4. user chooses Folder settings -> Reset;
5. generic reset removes `cache_path` and recreates it at the default/effective R2 without first registering valid R1 markers;
6. root-binding store therefore still has no R1 locator;
7. later `exactCacheCleanupBindings(X)` considers registered roots plus current R2; R2 has no exact X marker, so R1 is omitted;
8. Room cleanup may delete X and D1 can proceed without the genuine recoverable R1 suffix, leaving the exact R1 carrier/files orphaned.

The legacy id-only journal form is worse: if such a journal already requires X and the only exact R1 locator was never registered, `resolveLegacyCacheBindings()` cannot resolve R1 after Reset and intentionally throws recovery-required forever, leaving D1 unable to converge.

This is not a new canonical root. It is the same F10 exact cache-root durability/discoverability contract and a Checklist-v6 consumer-closure failure.

### Required correction

Inventory every current production mutation that can change/remove/reset `cache_path` and route it through one cache-root transition contract.

At minimum fix the Folder-settings Reset path so a valid current old root is durably captured before `cache_path` can be removed/defaulted. If root capture cannot be durably established, do not perform the cache-path portion of Reset in a way that loses the only locator.

Do not weaken the normal new-execution `prepareAttempt(..., context)` root binding. Do not delete malformed/legacy/unproven remnants. Do not use the new current root as proof that an old root is absent.

Required production-wiring regression:

- create an unbound pre-binding exact R1 marker+manifest+file;
- set `cache_path` to R1;
- invoke the actual Folder-settings reset/cache-path reset composition (or the smallest extracted production helper used by the real Reset consumer);
- establish the default/effective R2;
- simulate process-state reset/restart;
- prove `exactCacheCleanupBindings` / D1 still resolves R1 and never mutates unrelated R2;
- prove an old id-only required cache journal can also resolve/converge rather than retry forever after the Reset path.

Audit other direct current-production `cache_path` mutations under the same contract; do not broaden into unrelated settings behavior.

## Non-blocking hardening observation

The new `RootBindingStore` has no production retirement operation; each new execution identity can leave a durable binding key indefinitely, and every new bind rebuilds the complete binding namespace. Stale bindings cannot by themselves authorize deletion because cleanup still requires exact marker/manifest proof, so this review does not count the issue as a separate P2 blocker. If a safe retirement is added, it must occur only after no journal/recovery path can still require the binding; do not sacrifice recovery correctness merely to compact the store.

## Execution evidence

Implementation-side report claims compile/focused JVM verification PASS after corrected initial compile/test-compilation failures.

F10 instrumentation: `NOT EXECUTED — DEVICE UNAVAILABLE`.

Independent exact-SHA GitHub evidence:
- combined status contexts: 0;
- GitHub Actions workflow runs: 0.

Checklist v6 actual-execution gate therefore remains open independently of the source blocker above.

## Resulting workflow state

F10 remains `P2 / OPEN / NOT_CLEAN` at `d769351fcf7a4a0353b036bf095aa3036ba0e8a6`.

Count delta remains 0. F11 remains blocked and must not start.

INDEPENDENT EXECUTION: NOT EXECUTED
