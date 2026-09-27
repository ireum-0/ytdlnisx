# BUG-BACKUP-11 clean-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 1216c8a749a42dcc45e33b1686bc0d41b240c510
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 18
primary_lens: L6 Cross-feature semantic propagation DEEP
supporting_lenses:
- L2 Identity & provenance
- L5 Platform contract closure
independent_execution: NOT EXECUTED

## Existing behavior to preserve

The ordinary Folder-settings producer is correct in one important respect: selecting `command_path` uses `ACTION_OPEN_DOCUMENT_TREE` with read/write/persistable flags, then calls `takePersistableUriPermission()` before persisting the selected locator.

Current restore infrastructure also correctly treats some destination-local state as non-portable. `BackupSettingsUtil.nonPortablePreferenceKeys` already excludes `cache_path`, and Reset restore preserves destination-local non-portable keys from the destination snapshot while clearing/replaying portable settings.

Those patterns should be extended, not bypassed.

## Same-root defect

`command_path` is not in `nonPortablePreferenceKeys`.

Therefore `backupSettings()` serializes a provider-backed `command_path` as an ordinary portable String. Both restore modes accept that String because `BackupSettingsUtil.isPortablePreferenceKey("command_path")` returns true:

- Merge restore writes it through the default SharedPreferences editor.
- Reset restore clears portable settings and replays it through `putPortable()`.

Neither restore path executes the original Folder picker or acquires an equivalent destination-side persisted URI permission.

At runtime, `TerminalCommandPlanFactory.create()` reads the restored `command_path` as the Terminal destination. `FileUtil.canWriteToDestination()` and later provider publication then consult the destination device's actual DocumentFile/content-resolver authority.

Thus restore can durably publish a locator whose external write authority was never established by that restore. String equality is not SAF grant provenance.

This is the established BUG-BACKUP-11 root and is distinct from:
- BUG-BACKUP-08 preference type support;
- BUG-BACKUP-10 SharedPreferences commit-result propagation;
- Terminal provider-generation/carrier identity fixes;
- `cache_path`, which is already destination-local.

## Exact invariant

A backup may restore an executable external destination only when the destination installation independently possesses the authority required to use that exact destination.

A serialized URI/path locator by itself is never proof of Android SAF authority.

For `command_path`, the default safe portable contract is destination-local:
- backup artifacts must not claim that the source installation's selected command destination is portable authority;
- Merge restore must not overwrite the destination installation's current `command_path` with a backup value;
- Reset restore must preserve the destination installation's current `command_path` exactly as destination-local state;
- if no destination-local value is configured, normal default/picker behavior owns later configuration.

A future explicit import workflow could ask the user to reselect/re-authorize a destination, but generic app-data restore must not synthesize or infer that grant.

## Narrow implementation boundary

Preferred correction:

1. Add `command_path` to `BackupSettingsUtil.nonPortablePreferenceKeys`.
2. Rely on the existing portability filter so new backups stop serializing it as a portable setting.
3. Rely on the same filter for old backups: Merge restore ignores imported `command_path` even if an older artifact contains it.
4. In Reset restore, preserve the destination snapshot's current `command_path` through the existing non-portable-key preservation path, exactly as destination-local state.
5. Do not add a fake grant token, URI-permission boolean, or serialized persisted-permission list to the backup schema.
6. Keep `FolderSettingsFragment` as the authority-establishing producer for an explicitly changed Terminal destination.

A more permissive design that restores a provider locator only when the destination already holds an exact persisted write grant is possible, but it is broader and easier to get wrong. It must additionally prove tree/document identity and current write authority before committing the setting. The narrow correction above avoids that unnecessary authority inference.

## Forbidden shortcuts

- restoring `command_path` and checking only that the URI parses
- checking only `DocumentFile.exists()` without proving write authority
- serializing source-device persisted URI permissions and replaying them
- treating the backup file as authority to call `takePersistableUriPermission()` without a destination-side grant from Android
- converting `content://` to a raw filesystem-looking path and treating that as equivalent authority
- fixing only Terminal publication failure handling while leaving restore free to publish unproved configuration
- clearing destination `command_path` during Reset when the correct destination-local configuration already exists
- broadening this root into unrelated music/video destination behavior without separate producer/consumer proof

## Acceptance matrix

- new backup with provider-backed `command_path`: artifact omits the key from portable settings
- new backup with raw-path `command_path`: artifact still treats the key as destination-local and omits it
- old backup containing provider URI command_path + Merge restore: destination command_path remains unchanged
- old backup containing raw command_path + Merge restore: destination command_path remains unchanged
- Reset restore with existing destination command_path: clear/replay preserves the destination value rather than importing the backup value
- Reset restore with destination provider command_path and a valid persisted write grant: both destination preference and Android grant remain usable after restore
- Reset restore with no destination command_path: restore does not invent the backup locator; normal default initialization/picker behavior owns configuration
- source and destination happen to contain the same URI string but destination lacks grant: generic restore does not treat equality as proof
- process restart after Merge/Reset: the surviving command_path is the destination-authorized value, not a transient imported source locator
- Terminal plan after restore reads only the preserved/default destination command_path; no restored source URI can become publication authority
- regression for BUG-BACKUP-08 still round-trips supported portable value types
- regression for BUG-BACKUP-10 still propagates preference commit failure
- production-path test exercises Folder-settings grant producer -> backup serializer -> Merge/Reset restore -> Terminal plan/publication eligibility, not only a standalone key-filter helper

## Test gap

The bounded CLEAN-basis search found no focused regression proving `command_path` is destination-local across backup creation plus both Merge and Reset restore.

The prior CLEAN-basis BUG-BACKUP-11 attribution checkpoint established the same producer/consumer mismatch at `74f57e69`. This refinement does not create a new finding ID; it makes the existing root implementation-ready.
