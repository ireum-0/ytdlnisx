# Independent correctness review — F10 source + coverage fixed, execution open

- exact implementation SHA: `05e33771555aed85d3d271aa6a7f5545699e602b`
- implementation parent: `09cea8e1aa726f962ee73c30d01d0955c42ffce2`
- frozen Master Plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen Master Plan SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- frozen ledger ref-only SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Checklist v6 commit: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Checklist v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Verdict

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-NOT-VERIFIED`.

F10 is not yet canonically CLOSED/CLEAN because required actual Android production-wiring execution evidence is absent.

Canonical count delta: `0`.

CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Git verification

Implementation branch `checkpoint/pre-baseline-review` is independently verified at exact `05e33771555aed85d3d271aa6a7f5545699e602b`.

Its parent is exact `09cea8e1aa726f962ee73c30d01d0955c42ffce2`.

Compare from 09cea8e1: ahead 1 / behind 0 / total commits 1.

Commit:

`test: restore pre-binding cache-root coverage`

Trailer:

`Defect-ID: BUG-CLEANUP-01`

The only changed repository file is:

`app/src/androidTest/java/com/ireum/ytdl/work/CleanupScheduleCoordinatorProductionWiringTest.kt`

No production file changed in this wave.

## Coverage correction accepted

The previously weakened pre-binding restore regression is corrected.

The helper `assertSettingsRestorePreservesPreBindingRoot(...)` now:

1. sets destination `cache_path = R1`;
2. creates the exact carrier using `DownloadCacheOwnership.prepareAttempt(rootOne, target)` without Context, leaving it outside RootBindingStore;
3. records the exact manifest/owned file;
4. does not bind/capture R1 before generic restore;
5. forces `rootTransitionCaptureOverrideForTesting = { _, _ -> false }` before restore, so accidental generic capture would fail the scenario;
6. exercises Merge/Reset with imported R2 and Reset without imported cache_path through the shared helper;
7. proves destination raw/effective root remains R1 and imported R2 remains untouched;
8. explicitly clears RootBindingStore and process-local binding state before discovery;
9. proves `DownloadRepository.exactCacheCleanupBindings(listOf(target))` still discovers R1 from the unchanged current effective root plus exact marker/manifest;
10. simulates process restart, resets process-local binding state again, and proves `knownCacheCleanupBindings(target)` resolves R1;
11. runs the real cleanup occurrence and proves Room target, exact R1 owned artifact, marker and manifest converge while R2 remains untouched;
12. proves a valid successor is published.

This directly closes the recorded production-wiring definition gap without changing production behavior.

## Source-semantic closure preserved

The cumulative F10 source semantics accepted at 09cea8e1 remain unchanged:

- `cache_path` is destination-local/non-portable;
- generic backup excludes it;
- generic restore ignores imported/legacy cache_path;
- generic Merge/Reset does not require cache-root transition capture when the root is retained;
- Reset preserves destination-local cache_path through clear/replay;
- Folder picker/Folder reset/default initialization retain fail-closed transition capture;
- generic Reset still requires `CleanupScheduleCoordinator.prepareForSettingsReset()` before default-preference clear;
- cleanup coordinator critical namespace remains excluded from generic backup/restore;
- failed critical-store migration cannot be erased by Reset clear;
- current Download attempts bind the exact root before marker establishment;
- cleanup discovery uses registered roots plus current exact root, while destructive authority still requires exact marker/manifest proof;
- legacy ID-only journals remain fail-closed without a locator and resolve current exact R1 when it remains valid;
- frozen journals, partial retry, newer-owner protection, exact Room revalidation, D1-before-D2, generation/replay/successor/calendar semantics remain preserved.

## Persisted-generation compatibility

The previously resolved Checklist v6 Module F conclusion remains unchanged: published release state predates the remediation-only exact marker/RootBindingStore protocol, while cadence-only legacy preference state is covered by real coordinator migration/bootstrap wiring. No additional compatibility blocker is created here.

## Execution gate

Implementation-side report at 05e33771:

- `git diff --check`: PASS;
- committed-range diff check: PASS;
- `:app:compileDebugKotlin -x lint`: PASS;
- `:app:compileDebugAndroidTestKotlin`: PASS;
- focused JVM tests: not rerun because only Android-test code changed;
- `adb devices -l`: no device attached;
- `F10 instrumentation: NOT EXECUTED — DEVICE UNAVAILABLE`.

Independent exact-SHA GitHub evidence:

- combined status contexts: 0;
- associated GitHub Actions workflow runs: 0.

Therefore Checklist v6 actual production-wiring execution remains the only F10 closure gate identified by this review.

## Next F10 action

Do not make another source or test-definition change unless fresh evidence appears.

Run the relevant F10 Android production-wiring instrumentation against exact `05e33771555aed85d3d271aa6a7f5545699e602b` on a real usable emulator/device. Required execution should include the corrected settings restore/pre-binding scenarios and the already-established F10 production-wiring suite needed by Checklist v6.

If execution passes, independently verify the exact executed SHA/evidence before recording canonical F10 closure. If execution fails, classify the exact failure before changing production code.

F11 / `BUG-BACKUP-03` remains blocked until this F10 execution gate closes.

INDEPENDENT EXECUTION: NOT EXECUTED
