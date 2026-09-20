# F11 third-wave governing addendum — startup/default preference writers

Date: 2026-09-21

Reviewed implementation: `7efd3fe2579e421c545d1ed6287713dec02e9225`
Review base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`
Reviewed ledger: `899328bc91e4008e39a658387396a0106c8666ec`

Verdict: `NOT_CLEAN`

Current canonical blocker:
- `[P0] BUG-BACKUP-03`

Residual disposition remains:
- F11-R1 = STILL_OPEN
- F11-R2 = STILL_OPEN
- F11-R3 = CLOSED
- F11-R4 = CLOSED

Canonical blocker-count delta from this addendum: `0`.

This addendum extends the already-open F11-R2 actual-write consumer closure. It does not create or reopen a separate canonical root.

## R2 — direct portable SharedPreferences writers remain outside admission

`RestoreAwarePreferenceDataStore` closes AndroidX framework auto-persistence, but exact final source still contains direct default-SharedPreferences writers for Reset-portable keys that bypass `RestoreMutationAdmission`.

Confirmed current examples include:
- `MainSettingsFragment`: `backup_path` via `editor.apply()`.
- `FolderSettingsFragment`: `music_path`, `video_path`, `command_path`, `cache_downloads`, `keep_cache`, `file_name_template`, `file_name_template_audio`, and folder-result writes through direct `editor.apply()`.
- `AdvancedSettingsFragment`: `format_importance_audio`, `format_importance_video` via direct `apply()`.
- `YoutubePlayerClientFragment`: `youtube_player_clients` via direct `apply()`.
- `UpdateSettingsFragment`: `ytdlp_source`, `ytdlp_source_label`, `ytdl-version` via direct `apply()`.

`BackupSettingsUtil.isPortablePreferenceKey()` makes these keys portable unless specifically excluded. Therefore these direct mutations remain in the F11 settings Reset authority graph.

## R2 — reset-to-default path escapes the boundary

`BaseSettingsFragment.resetPreferences()` removes keys and commits that editor through `RestoreMutationAdmission.applyOrdinaryPreferences(...)`, but then calls:

`PreferenceManager.setDefaultValues(..., true)`

after the shared admission boundary has been released.

That default-publication step can perform the final preference write outside the same ordering that serialized the preceding removal.

A complete fix must include the actual default-value publication boundary, not only the explicit remove editor.

## R2 / startup ordering — ordinary preference mutation occurs before Restore recovery

`App.onCreate()` currently executes `setDefaultValues()` synchronously before `RestoreTransactionCoordinator.recover(...)` is started.

`setDefaultValues()` can write preference defaults and updates the `spl` marker.

Also, `runtimeReadiness` begins before Restore recovery and may write the portable `version` preference while Restore recovery is running.

Therefore a process restart with an active durable Restore can still have ordinary preference writers mutate the same default SharedPreferences namespace before or concurrently with Restore recovery.

This conflicts with the accepted F11 startup invariant that Restore recovery owns the ordering boundary before conflicting ordinary startup reconcilers/writers inspect or mutate Reset-targeted state.

## Required correction

Inventory every direct default-SharedPreferences writer whose key is Reset-portable under the final `BackupSettingsUtil` policy.

For each such writer, make the actual durable mutation participate in the shared Restore publication/mutation ordering.

Additionally:
- serialize or defer `PreferenceManager.setDefaultValues(..., true)` so its actual default writes cannot race active Restore ownership;
- ensure startup default/version preference writes do not occur before active Restore recovery has resolved;
- preserve destination-local/nonportable keys and F10 cleanup-coordinator authority;
- preserve existing user-visible defaults, summaries, and settings behavior;
- do not globally route nonportable/runtime authority through the portable Restore path.

Add deterministic production-wiring proof for:
- one representative direct portable editor writer where ordinary wins;
- the same path where Restore publication wins and no late write occurs;
- screen reset/default publication versus Restore ownership;
- process restart with an active Restore proving startup default/version writers cannot mutate before Restore recovery resolves.

## F6 relationship

The existing F6 / `BUG-BACKUP-06` canonical closure is not reopened by this addendum.

However its established transient/nonportable preference policy remains a preserved prerequisite. The LocalAdd runtime/session namespaces identified in `57e6e7c0...` must not become generic portable backup authority.

## Evidence

- Exact final source reviewed at `7efd3fe2...`.
- Implementation-agent runtime evidence remains evidence only.
- Independent runtime execution was not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
