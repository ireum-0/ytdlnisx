# F10 / BUG-CLEANUP-01 — independent re-review of 5b6c5ed3

Date: 2026-09-16
Reviewed implementation branch: `checkpoint/pre-baseline-review`
Review range: `4cf8ccf04c637d5a9c1066bfe5083b67b79a83bd..5b6c5ed3dd8be0b525cb2790c5ac999601fb597b`
Review head: `5b6c5ed3dd8be0b525cb2790c5ac999601fb597b`
Parent: `4cf8ccf04c637d5a9c1066bfe5083b67b79a83bd`
Verdict: `NOT_CLEAN`
Canonical root: `BUG-CLEANUP-01 / F10 / P2 / OPEN`
Canonical count delta: `0` (new evidence/subcases belong to the already-counted F10 root)
CLEAN basis consequence: unchanged at `90afaec157607669ea32fa41877e7f0efcdcca86`

## Exact Git state

Remote `checkpoint/pre-baseline-review` was independently verified at exact `5b6c5ed3dd8be0b525cb2790c5ac999601fb597b`. The commit has parent exactly `4cf8ccf04c637d5a9c1066bfe5083b67b79a83bd`, and compare is one straight commit ahead / zero behind. Commit message is `fix: isolate cleanup authority and preserve cache recovery` with trailer `Defect-ID: BUG-CLEANUP-01`.

The implementation changed only the reported F10 source/tests. No source evidence was found that requires reopening separate canonical roots.

## Source-accepted improvement — former residual A is structurally corrected

The critical cleanup schedule namespace is now isolated in dedicated SharedPreferences `cleanup_leftover_downloads_critical_state` rather than sharing Android's default preference file with ordinary UI settings.

A successful critical transition is encoded as a complete namespace replacement reconstructed from the coordinator's last confirmed critical snapshot plus the intended mutation. `writeCriticalNamespace` removes/replaces every known critical key instead of creating a delta editor over a possibly contaminated process map. After a memory-visible failed write, later critical writes therefore do not inherit rejected generation/cadence/debt/journal values merely because Android kept those values in the SharedPreferences process map.

The default `cleanup_leftover_downloads` preference is now a UI mirror after the dedicated critical store is initialized. `DownloadSettingsFragment` and `CleanupSchedulePreferenceController` continue to read coordinator-confirmed cadence, and downloading-preference Reset still disables cleanup through `CleanupScheduleCoordinator.configure(..., null)` before resetting the rest of the screen.

Disposition: the previously confirmed collateral-persistence source defect is source-fixed at this review head. Preserve this dedicated-store/full-snapshot protocol.

## Source-accepted improvement — self-destroyed cache proof subcase is corrected

`DownloadCacheOwnership` now distinguishes `Completed`, `RetryableFailure`, `Superseded`, and `Unproven`.

For an exact marker+manifest carrier, a failed manifest-listed file deletion returns `RetryableFailure` before removing the marker/manifest. Retry can therefore reconstruct the same exact authority. Malformed/unowned/legacy numeric remnants are not captured as mandatory exact responsibility, and a valid newer marker is positive supersession evidence rather than an old-owner deletion authorization.

`CleanUpLeftoverDownloads.advanceExactCacheCleanup` advances its journal only for Completed/Superseded/Unproven and retains an incomplete D1 for RetryableFailure. The added JVM tests exercise the real helper with partial deletion and retained marker/manifest.

Disposition: the former self-destroyed-proof subcase is source-fixed at this review head. Preserve these result distinctions and retry carrier semantics.

## OPEN P2 subcase — mutable cache root is not part of the frozen exact D1 responsibility

The new exact-cache journal still freezes only `DownloadItem` targets and required/completed Download IDs. It does not freeze the canonical cache root or otherwise carry an exact durable root identity.

Production capture currently does:

- `DownloadRepository.exactCacheCleanupRequired(targets)` -> `File(FileUtil.getCachePath(App.instance))` -> `DownloadCacheOwnership.hasCleanupResponsibility(...)`.

Production retry/deletion later independently does:

- `DownloadRepository.deleteExactCacheForTargetOutcome(target)` -> another `File(FileUtil.getCachePath(App.instance))` -> `deleteIfOwnedOrAlreadyAbsentResult(...)`.

`FileUtil.getCachePath()` reads mutable default preference `cache_path`. `FolderSettingsFragment.changePath()` allows a user-selected supported cache root and persists it with `editor.putString("cache_path", path).apply()`.

Concrete failure sequence:

1. exact cache responsibility for frozen target X exists at canonical root R1 with exact marker + manifest + files;
2. D1 captures X as required while `cache_path` resolves to R1;
3. Room deletion commits, or the process dies before exact cache suffix completion;
4. `cache_path` is changed to R2 before the cache suffix retry/deletion;
5. retry resolves R2 instead of the frozen R1;
6. if R2 has neither marker nor numeric directory for X, `deleteIfOwnedOrAlreadyAbsentResult(R2, X)` returns `Completed`;
7. D1 records X complete and can eventually allow D2;
8. the genuine exact R1 marker/manifest/files survive with no journal responsibility pointing back to R1.

There is also a pre-journal discoverability form: changing from R1 to R2 before `prepareCleanupEffectJournal()` makes `exactCacheCleanupRequired()` inspect only R2, so a genuine exact carrier at R1 can be omitted entirely. Current `DownloadItem` does not carry cache-root identity, and the cleanup journal has no alternate durable root locator.

This violates Checklist v6 recovery-discoverability, multi-ledger/process-death, semantic-granularity/provenance, and filesystem-mutation-authority requirements. It is a subcase of the existing `BUG-CLEANUP-01` exact-cache recovery root, not a new canonical blocker count.

Required correction boundary:

- do not re-resolve mutable `cache_path` as the identity of an already-owned exact suffix;
- establish a durable exact cache-root binding/discovery contract sufficient for both journal creation and retry/restart;
- at minimum, one journal occurrence must resolve its canonical root once and persist that identity before deciding exact required IDs, then use the frozen root on every retry;
- additionally close the pre-journal path-change case: an exact R1 carrier that belongs to a frozen Cancelled/Error target must not become undiscoverable merely because the current preference is R2. Use the smallest architecture-consistent durable binding/root-history/gating design; do not guess R1 from the current preference;
- never delete R2/new-owner contents as compensation for losing R1;
- D2 may proceed only after each real recoverable D1 responsibility is complete/superseded/unproven under the exact root contract.

Required tests include R1->R2 changes both before journal capture and after journal persistence/process restart, plus positive proof that R1 is cleaned/reconciled while unrelated/new R2 ownership is preserved.

## Verification blocker — migration instrumentation assertion contradicts its own memory-visible failure model

The new Android production-wiring test `failedCriticalStoreMigrationKeepsLegacyStateForRestartRecovery` enables `commitFailureAppliesMemoryForTesting = true`, forces the dedicated migration commit to report false, then immediately expects the dedicated store version key to be null.

But the production test seam deliberately invokes `editor.apply()` on the failed target snapshot when that flag is true, in order to model Android's memory-visible failed-commit shape. `SharedPreferences.Editor.apply()` updates the in-process SharedPreferences state immediately. Therefore the test's immediate `assertNull(...critical_store_version...)` contradicts the required failure model and should fail when actually executed.

Do not repair this by removing process-visible mutation from the seam. Correct the test to distinguish process-visible rejected state from the separately modeled durable restart image, then verify restart/retry converges from the last confirmed durable authority.

The implementation report explicitly says the new Android production-wiring tests were compiled but not executed. The final GitHub SHA has zero commit-status contexts and zero GitHub Actions runs. Checklist v6 requires actual execution evidence where the triggered production-wiring gate is material, so F10 cannot be canonically CLEAN on compile/source evidence alone even after source blockers are removed.

## Preserved prior F10 semantics

No concrete regression was found in the reviewed source for the already-accepted F10 semantics: exact occurrence/generation/cadence identity, D1->D2 ordering, incomplete-D1 successor blocking, startup/replay ownership, pending->active recovery, Reset ordering, calendar cadence, stale callback fencing, WorkManager UNKNOWN discovery, ordinary settings latest-wins behavior, exact Room target revalidation, exact DOWNLOAD_TEMP snapshot behavior, or newer cache-owner fail-closed semantics.

The claim->marker publication interval was rechecked. The new cache `ownershipLock` does not itself serialize process-local owner publication, but current production attempt preparation and old exact-cache mutation share the cache ownership lock, and no concrete destructive newer-owner takeover was established from that interval at this review head. Do not count it as a separate finding without stronger evidence.

## Verification evidence

Implementation report claims:
- `git diff --check`: PASS
- `git diff --check 4cf8ccf0..HEAD`: PASS
- `:app:compileDebugKotlin -x lint`: PASS after correcting an intermediate syntax error
- `:app:compileDebugAndroidTestKotlin`: PASS
- focused CleanupSchedulePolicyTest and DownloadCacheOwnershipTest: PASS
- F10 instrumentation: NOT EXECUTED — DEVICE UNAVAILABLE

Independent GitHub evidence:
- final SHA has no status contexts;
- final SHA has no GitHub Actions workflow runs.

The implementation-side claims remain evidence only and were not independently executed by this reviewer.

## Resulting workflow state

F10 remains `P2 / OPEN / NOT_CLEAN` at `5b6c5ed3dd8be0b525cb2790c5ac999601fb597b`.

The next implementation wave must remain scoped to F10 / `BUG-CLEANUP-01`: close exact cache-root durability/discoverability across mutable `cache_path`, correct the contradictory migration instrumentation expectation without weakening the real memory-visible failure model, preserve all accepted dedicated-store and cache-carrier improvements, and execute the applicable production-wiring instrumentation on a real device/emulator when one is available.

F11 remains blocked and must not start.

INDEPENDENT EXECUTION: NOT EXECUTED
