# Independent correctness review — F10 source fixed, coverage/execution open

- exact implementation SHA: `09cea8e1aa726f962ee73c30d01d0955c42ffce2`
- parent: `ef2d307b4e2c058719af5bb91623859fb3f9abce`
- frozen Master Plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen Master Plan SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- frozen ledger ref-only SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Checklist v6 commit: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Checklist v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Verdict

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-NOT-VERIFIED / EXECUTION-NOT-VERIFIED`.

No new P0/P1/P2 source root is created. Canonical count delta: `0`.

F10 is not canonically CLOSED/CLEAN because required production-wiring evidence is not yet adequate and actual instrumentation has not executed.

The contiguous CLEAN review basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Git verification

`checkpoint/pre-baseline-review` was independently verified at exact `09cea8e1aa726f962ee73c30d01d0955c42ffce2`.

Parent is exact `ef2d307b4e2c058719af5bb91623859fb3f9abce`.

Compare `ef2d307b..09cea8e1`: ahead 1 / behind 0 / one straight commit.

Commit:

`fix: remove cache capture from generic settings restore`

Trailer:

`Defect-ID: BUG-CLEANUP-01`

Changed repository paths are exactly:

- `app/src/main/java/com/ireum/ytdl/database/viewmodel/SettingsViewModel.kt`
- `app/src/androidTest/java/com/ireum/ytdl/work/CleanupScheduleCoordinatorProductionWiringTest.kt`

The implementation report's `.tmp_f10_generic_restore/...` prefixes are isolated-worktree filesystem prefixes, not repository paths.

## Source-semantic review

The prior ef2 residual is fixed.

Under the accepted destination-local `cache_path` policy:

- generic backup excludes `cache_path`;
- generic restore filters imported/legacy `cache_path`;
- Merge therefore cannot change the effective cache root;
- Reset snapshots and restores the existing destination-local raw `cache_path` in the same SharedPreferences clear/replay transaction;
- generic restore no longer calls or requires `DownloadCacheOwnership.captureEffectiveRootBeforePreferenceMutation()` merely because `data.settings` is present.

Thus ordinary settings Merge/Reset no longer falsely fails on an irrelevant cache-root capture failure.

The real cache-path mutation consumers remain fail-closed:

- Folder default initialization captures the current effective root before materializing the preference;
- Folder picker validates the selected path and captures the current root before writing the new path;
- Folder-screen Reset captures before resetting/defaulting the path.

Current Download execution still calls `DownloadCacheOwnership.prepareAttempt(..., context = applicationContext)`, so new exact marker creation remains preceded by durable root binding.

Cleanup candidate construction still includes the current effective root in addition to durable registered roots, and still requires exact marker/manifest responsibility before journaling deletion authority. Therefore a pre-binding exact carrier at the unchanged current R1 remains source-discoverable after generic restore even without RootBindingStore.

Legacy ID-only journal recovery likewise admits the current root only when exact ownership is still proven; absence of an exact locator remains recovery-required rather than being treated as success.

The accepted frozen-root journal, typed cache outcomes, partial-deletion retry, newer-owner protection, D1-before-D2, exact Room revalidation, replay/successor, and calendar-cadence semantics are unchanged at the final SHA.

## Cleanup critical-store restore barrier

The ef2 critical-store correction remains intact.

`BackupSettingsUtil.isPortablePreferenceKey()` excludes the coordinator canonical namespace via `CleanupScheduleCoordinator.isCoordinatorOwnedPreferenceKey()`.

Reset still calls `CleanupScheduleCoordinator.prepareForSettingsReset()` before generic default-preference clear.

That barrier runs under the coordinator destructive-effect mutex and only returns true when `criticalPreferencesOrNull()` has produced an initialized dedicated critical store. Failed migration remains fenced and Reset leaves the legacy default-preference authority untouched.

Thus imported cadence/generation/debt/occurrence/effect-phase/effect-journal/version state cannot become cleanup authority through generic restore.

## Checklist v6 Module F — supported persisted generation

The previously open compatibility question is resolved for the production-supported public release boundary.

The newest published GitHub release is `v1.8.8.7`, tag commit `96e8e51322649dbad2d3f95cc85bf17744f81b5a`.

That release has no `DownloadCacheOwnership.kt`; its cleanup worker directly deletes Cancelled/Error rows and performs generic cache cleanup. Therefore a public supported released generation cannot contain the remediation-only exact marker/manifest carrier while lacking RootBindingStore.

The release scheduler stores only the user cadence in ordinary default preferences and creates a delayed one-time WorkManager request. The current production-wiring helper seeds that cadence-only legacy preference and calls real `CleanupScheduleCoordinator.reconcile()` to initialize the dedicated store before exercising missing-generation bootstrap behavior.

Therefore:

- legacy released numeric cache remnants have no exact marker authority and remain unproven/preserved under the new contract;
- the released cadence-only preference generation is explicitly migratable;
- exact-marker-without-RootBindingStore is an intermediate remediation state, not a published supported release generation;
- current source nevertheless remains conservative for that intermediate state: unchanged current roots are directly discoverable by exact marker, and real Folder path transitions capture them before mutation.

No additional Module-F source blocker is confirmed.

## Remaining closure-evidence gap

The final test helper `assertSettingsRestorePreservesPreBindingRoot(...)` now calls `captureOwnedRootsForPathTransition(rootOne)` before invoking generic restore.

That means the tests named:

- `settingsMergeRestorePreservesPreBindingRootAndLegacyJournalAfterRestart`
- `settingsResetRestorePreservesPreBindingRootAndLegacyJournalAfterRestart`
- `settingsResetWithoutCachePathPreservesPreBindingRootAndLegacyJournal`

no longer directly prove their named pre-binding condition across generic restore; R1 has already been durably registered before restore.

This does not expose a production source defect because generic restore preserves R1 and `cleanupBindings` / `knownCacheBindings` inspect the unchanged current root directly. But it weakens the required production-wiring regression and should be corrected before instrumentation is treated as closure evidence.

Minimal correction:

- remove the pre-restore `captureOwnedRootsForPathTransition(rootOne)` from that helper, or add an equivalent separate real-production test that leaves the exact R1 carrier unregistered through Merge/Reset;
- keep `rootTransitionCaptureOverrideForTesting = false` so the test proves generic restore does not depend on capture;
- after restore and simulated restart, prove the unchanged current R1 is discovered through exact marker/manifest authority, the legacy ID-only journal converges, and imported/default R2 remains untouched;
- keep the separate Folder transition tests proving actual path mutation still captures/fails closed.

No production logic change is currently indicated by this review.

## Execution evidence

Implementation-side report:

- `git diff --check`: PASS;
- committed-range diff check: PASS;
- focused JVM tests: PASS;
- `:app:compileDebugKotlin -x lint`: PASS after an initial environment-only failure because the first clean worktree lacked ignored `local.properties`;
- `:app:compileDebugAndroidTestKotlin`: PASS;
- `F10 instrumentation: NOT EXECUTED — DEVICE UNAVAILABLE`.

These claims are implementation evidence, not independent execution.

Independent exact-SHA GitHub evidence:

- combined status contexts: 0;
- associated GitHub Actions workflow runs: 0.

Actual F10 Android production-wiring execution remains required.

## Dependency consequence

F11 / `BUG-BACKUP-03` remains blocked. Do not start it until the F10 production-wiring coverage gap is corrected and the required F10 execution gate is actually satisfied.

Separate canonical roots are unaffected.

INDEPENDENT EXECUTION: NOT EXECUTED
