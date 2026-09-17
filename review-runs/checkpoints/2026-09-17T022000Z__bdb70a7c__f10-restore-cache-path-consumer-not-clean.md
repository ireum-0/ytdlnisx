# Independent correctness review — F10 restore cache-path consumer checkpoint

- exact implementation SHA: `bdb70a7c1b79d1f14347aee55fa726f9c845380b`
- implementation parent: `d769351fcf7a4a0353b036bf095aa3036ba0e8a6`
- frozen plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`, blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Verdict

`F10 / BUG-CLEANUP-01: P2 / OPEN / NOT_CLEAN`.

Canonical count delta: 0. This is a consumer-closure subcase of the already-counted F10 exact cache-root durability contract, not a new root.

The `bdb70a7c` Folder Settings Reset correction is accepted: picker, Folder-settings Reset, and Folder-settings default initialization now use the shared current-root capture helper, and Reset refuses the cache-path mutation when capture fails.

## Confirmed remaining P2 — backup/restore settings is an uncoordinated `cache_path` writer

The implementation-side mutation inventory reported only Folder Settings default initialization, picker selection, and screen Reset. Fresh current-source review found another reachable production writer.

`SettingsViewModel.restoreData()` restores `data.settings` through the default SharedPreferences editor. For every portable preference it dispatches by encoded type; a String uses `putString(key, prefValue)`. When `resetData == true`, the same editor calls `clear()` before restoring the payload.

`BackupSettingsUtil.isPortablePreferenceKey()` currently excludes only `app_language`, `history_visible_child_youtuber_groups`, and playback-position cache keys. `cache_path` is therefore backed up and admitted for restore.

`MainSettingsFragment` invokes the real production restore in both modes:
- Merge: `settingsViewModel.restoreData(restoreData, requireContext())`
- Reset: `settingsViewModel.restoreData(restoreData, requireContext(), true)`

No cache-root capture contract is invoked before the settings editor mutates/removes `cache_path`.

### Concrete accepted F10 migration-state failure

The d769/bdb F10 work explicitly supports a valid exact carrier that predates durable RootBindingStore registration. Existing regression coverage creates that state with `DownloadCacheOwnership.prepareAttempt(rootOne, target)` without a Context, then relies on a path-transition capture to register R1 before changing `cache_path`.

For the unhandled restore consumer:

1. current local `cache_path` = R1;
2. target X has a valid exact marker + manifest + owned file at R1;
3. this is a supported pre-binding/migration carrier, so RootBindingStore has no R1 locator yet;
4. user imports settings through Merge with backup `cache_path` = R2, or through Reset where `clear()` removes R1 before portable settings are reapplied;
5. `SettingsViewModel.restoreData()` changes/removes `cache_path` without `captureOwnedRootsForPathTransition()`;
6. RootBindingStore still has no R1;
7. later `DownloadCacheOwnership.cleanupBindings()` constructs candidates only from registered roots plus the current root when it still has the exact marker; current R2 does not prove X at R1;
8. a new cleanup journal can omit the real R1 responsibility and Room/D1/D2 can progress while the exact R1 carrier/files remain orphaned.

For an older ID-only F10 effect journal, the same lost locator instead causes `resolveLegacyCacheBindings()` to throw `EffectPhaseRecoveryRequired("cleanup cache root binding is unavailable for legacy journal")`, so the cleanup chain can remain blocked.

This is consumer closure under Checklist v6 core invariants 10, 11, and 18. It is not necessary to implement the full F11 restore protocol to close this F10 subcase; the `cache_path` portion of settings restore must consume the same old-root durability contract or be made non-mutating/non-portable in a way that preserves the existing local root and the accepted backup policy.

## Required next correction

Inventory all settings-import mutations that can write/remove/default `cache_path`, including both Merge and Reset restore.

Before any such mutation can make the current effective root undiscoverable, durably capture valid exact markers at that root through the established transition contract. If capture fails, do not lose the existing `cache_path` locator. Preserve unrelated restore behavior and do not broaden into full F11 implementation.

If the chosen design instead makes `cache_path` non-portable, prove both backup and restore semantics and ensure Reset restore does not remove the local key through an indiscriminate `clear()` before the non-portable exclusion can protect it.

Required regression coverage:
- Merge restore: pre-binding R1 exact carrier + imported R2 `cache_path` -> restart -> R1 still discovered and cleaned, R2 untouched;
- Reset restore: same carrier with `resetData=true`, including the `clear()` path -> restart -> R1 still discovered and cleaned;
- capture failure in Merge and Reset -> existing R1 locator is not lost;
- legacy ID-only journal through both restore modes -> R1 resolves after restart rather than permanent recovery-required;
- current root-bound executions remain unaffected and newer R2 owner remains protected.

## Additional compatibility observation

Checklist v6 Module F is triggered by the stronger durable root-binding identity contract. A supported older generation that could already contain exact markers without RootBindingStore requires explicit compatibility reasoning. Do not infer support solely from an intermediate remediation checkpoint; identify the oldest actually supported persisted generation before adding broad migration scans. This remains a required review question, not an independently counted additional blocker in this checkpoint.

## Execution evidence

Implementation-side report at bdb70a7c:
- `git diff --check`: PASS
- committed-range diff check: PASS
- `:app:compileDebugKotlin -x lint`: PASS
- `:app:compileDebugAndroidTestKotlin`: PASS
- focused CleanupSchedulePolicyTest / DownloadCacheOwnershipTest: PASS
- `F10 instrumentation: NOT EXECUTED — DEVICE UNAVAILABLE`

Independent GitHub evidence at bdb70a7c:
- combined status contexts: 0
- GitHub Actions workflow runs: 0

`INDEPENDENT EXECUTION: NOT EXECUTED`

F11 remains blocked. Do not start F11 until F10 source review and required execution gate are actually closed.